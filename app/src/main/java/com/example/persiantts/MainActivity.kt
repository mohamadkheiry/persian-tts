package com.example.persiantts

import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PersianTTS"

/**
 * توضیح صدای موجود در برنامه: نام نمایشی فارسی + پوشه‌ی assets مربوط به آن.
 */
private data class VoiceOption(
    val displayName: String,
    val assetDir: String,
    val modelFileName: String
)

class MainActivity : AppCompatActivity() {

    private val voices = listOf(
        VoiceOption("گنجی", "ganji", "fa_IR-ganji-medium.onnx"),
        VoiceOption("گایرو", "gyro", "fa_IR-gyro-medium.onnx")
    )

    private var tts: OfflineTts? = null
    private var currentVoiceIndex: Int = 0

    private lateinit var voiceSpinner: Spinner
    private lateinit var inputText: TextInputEditText
    private lateinit var errorText: TextView
    private lateinit var statusText: TextView
    private lateinit var convertButton: MaterialButton
    private lateinit var progressSpinner: ProgressBar
    private lateinit var playbackContainer: View
    private lateinit var playPauseButton: ImageButton
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var saveButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private var mediaPlayer: MediaPlayer? = null
    private var lastGeneratedFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var seekUpdateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupVoiceSpinner()
        setupListeners()

        loadTtsEngine(voices[currentVoiceIndex])
    }

    private fun bindViews() {
        voiceSpinner = findViewById(R.id.voiceSpinner)
        inputText = findViewById(R.id.inputText)
        errorText = findViewById(R.id.errorText)
        statusText = findViewById(R.id.statusText)
        convertButton = findViewById(R.id.convertButton)
        progressSpinner = findViewById(R.id.progressSpinner)
        playbackContainer = findViewById(R.id.playbackContainer)
        playPauseButton = findViewById(R.id.playPauseButton)
        playbackSeekBar = findViewById(R.id.playbackSeekBar)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)
    }

    private fun setupVoiceSpinner() {
        val names = voices.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
        voiceSpinner.setSelection(0) // پیش‌فرض: گنجی

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != currentVoiceIndex) {
                    currentVoiceIndex = position
                    resetPlaybackState()
                    loadTtsEngine(voices[currentVoiceIndex])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        convertButton.setOnClickListener { onConvertClicked() }
        playPauseButton.setOnClickListener { onPlayPauseClicked() }
        saveButton.setOnClickListener { onSaveClicked() }
        shareButton.setOnClickListener { onShareClicked() }

        playbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadTtsEngine(voice: VoiceOption) {
        setBusy(true, getString(R.string.status_generating))
        Thread {
            try {
                val assetManager: AssetManager = application.assets

                // espeak-ng-data شامل فایل‌های باینری‌ست که موتور espeak-ng (کد C) باید
                // با fopen مسیر واقعی روی دیسک باز کند؛ خواندن مستقیم از AssetManager
                // پشتیبانی نمی‌شود. پس یک‌بار آن را از assets به حافظه‌ی داخلی کپی می‌کنیم.
                val dataDirPath = ensureEspeakDataDir()

                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "${voice.assetDir}/${voice.modelFileName}",
                        lexicon = "",
                        tokens = "${voice.assetDir}/tokens.txt",
                        dataDir = dataDirPath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val config = OfflineTtsConfig(model = modelConfig)
                val newTts = OfflineTts(assetManager = assetManager, config = config)

                mainHandler.post {
                    tts?.free()
                    tts = newTts
                    setBusy(false, getString(R.string.status_ready))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTS engine", e)
                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    showError(getString(R.string.error_engine_init_failed))
                }
            }
        }.start()
    }

    /**
     * پوشه‌ی espeak-ng-data را (در صورت نیاز) یک‌بار از assets به یک مسیر واقعی
     * در حافظه‌ی داخلی برنامه کپی می‌کند و مسیر مطلق آن را برمی‌گرداند.
     * این پوشه بین همه‌ی صداها مشترک است؛ اگر قبلاً کپی شده، دوباره کپی نمی‌شود.
     */
    private fun ensureEspeakDataDir(): String {
        val destRoot = File(filesDir, "espeak-ng-data")
        val markerFile = File(filesDir, "espeak-ng-data.copied")
        if (destRoot.exists() && markerFile.exists()) {
            return destRoot.absolutePath
        }

        if (destRoot.exists()) {
            destRoot.deleteRecursively()
        }
        destRoot.mkdirs()
        copyAssetDir("espeak-ng-data", destRoot)
        markerFile.writeText("ok")
        return destRoot.absolutePath
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val assetManager = application.assets
        val entries = assetManager.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // این یک فایل است، نه پوشه
            copyAssetFile(assetPath, File(destDir.parentFile, destDir.name))
            return
        }

        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries != null && childEntries.isNotEmpty()) {
                copyAssetDir(childAssetPath, File(destDir, entry))
            } else {
                copyAssetFile(childAssetPath, File(destDir, entry))
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        application.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun onConvertClicked() {
        hideError()
        val text = inputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            showError(getString(R.string.error_empty_text))
            return
        }

        val engine = tts
        if (engine == null) {
            showError(getString(R.string.error_engine_init_failed))
            return
        }

        resetPlaybackState()
        setBusy(true, getString(R.string.status_generating))

        Thread {
            try {
                val audio: GeneratedAudio = engine.generate(text = text, sid = 0, speed = 1.0f)
                if (audio.samples.isEmpty()) {
                    mainHandler.post {
                        setBusy(false, getString(R.string.status_ready))
                        showError(getString(R.string.error_synthesis_failed))
                    }
                    return@Thread
                }

                val outFile = File(cacheDir, "tts_output.wav")
                val saved = audio.save(outFile.absolutePath)

                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    if (saved) {
                        lastGeneratedFile = outFile
                        showPlaybackControls()
                    } else {
                        showError(getString(R.string.error_synthesis_failed))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis failed", e)
                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    showError(getString(R.string.error_synthesis_failed))
                }
            }
        }.start()
    }

    private fun onPlayPauseClicked() {
        val file = lastGeneratedFile ?: return

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    stopSeekUpdates()
                    playbackSeekBar.progress = 0
                }
                playbackSeekBar.max = duration
            }
            mediaPlayer?.start()
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            startSeekUpdates()
            return
        }

        val player = mediaPlayer!!
        if (player.isPlaying) {
            player.pause()
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            stopSeekUpdates()
        } else {
            player.start()
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            startSeekUpdates()
        }
    }

    private fun startSeekUpdates() {
        stopSeekUpdates()
        seekUpdateRunnable = object : Runnable {
            override fun run() {
                val player = mediaPlayer ?: return
                try {
                    playbackSeekBar.progress = player.currentPosition
                } catch (e: IllegalStateException) {
                    return
                }
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(seekUpdateRunnable!!)
    }

    private fun stopSeekUpdates() {
        seekUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        seekUpdateRunnable = null
    }

    private fun onSaveClicked() {
        val file = lastGeneratedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.status_save_failed), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "persian_tts_$timestamp.wav"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, getString(R.string.status_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.status_save_failed), Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) musicDir.mkdirs()
                val destFile = File(musicDir, fileName)
                FileOutputStream(destFile).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                Toast.makeText(this, getString(R.string.status_saved), Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save audio", e)
            Toast.makeText(this, getString(R.string.status_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onShareClicked() {
        val file = lastGeneratedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.error_synthesis_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "com.example.persiantts.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    private fun resetPlaybackState() {
        mediaPlayer?.release()
        mediaPlayer = null
        lastGeneratedFile = null
        stopSeekUpdates()
        playbackSeekBar.progress = 0
        playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        playbackContainer.visibility = View.GONE
        saveButton.isEnabled = false
        shareButton.isEnabled = false
    }

    private fun showPlaybackControls() {
        playbackContainer.visibility = View.VISIBLE
        saveButton.isEnabled = true
        shareButton.isEnabled = true
    }

    private fun setBusy(busy: Boolean, statusMessage: String) {
        progressSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        convertButton.isEnabled = !busy
        voiceSpinner.isEnabled = !busy
        statusText.text = statusMessage
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSeekUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.free()
        tts = null
    }
}
