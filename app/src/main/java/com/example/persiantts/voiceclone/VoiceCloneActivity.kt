package com.example.persiantts.voiceclone

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.persiantts.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "VoiceCloneActivity"

/**
 * صفحه‌ی «شبیه‌سازی صدا» (Voice Cloning) — قابلیتی مستقل از موتور اصلی Piper در MainActivity.
 *
 * معماری (کاملاً آفلاین، بدون هیچ تماس شبکه‌ای):
 *  ۱. کاربر یک نمونه صدا ضبط می‌کند (AudioRecord → WAV) یا فایل صوتی دلخواه انتخاب می‌کند
 *     (دیکود با MediaExtractor/MediaCodec در AudioIo.decodeToMonoFloat).
 *  ۲. متن فارسی با همان موتور Piper موجود در پروژه (صدای «گنجی»، ثابت) به گفتار ۲۲۰۵۰هرتزی تبدیل
 *     می‌شود — این صدا محتوا/تلفظ/آهنگ کلام درست فارسی را تضمین می‌کند.
 *  ۳. مدل ONNX «مبدل رنگ صدا»ی OpenVoice (ToneColorConverter) رنگ صدای نمونه‌ی کاربر را استخراج
 *     و آن را جایگزین رنگ صدای خروجی Piper می‌کند — نتیجه: محتوای گفتاریِ فارسیِ درستِ Piper،
 *     با تُن/طنین صدای نمونه‌ی کاربر.
 *
 * توجه: کیفیت شبیه‌سازی به کیفیت/طول نمونه‌ی مرجع بستگی دارد و best-effort است — این یک مدل
 * تبدیل تن صدا (voice conversion) سبک‌وزن است، نه یک کلون‌کننده‌ی کامل صدا مثل مدل‌های بزرگ ابری.
 */
class VoiceCloneActivity : AppCompatActivity() {

    companion object {
        // صدای پایه‌ی Piper که برای تولید محتوای گفتاری فارسی استفاده می‌شود؛ ثابت و همیشه «گنجی».
        private const val BASE_VOICE_DIR = "ganji"
        private const val BASE_VOICE_MODEL = "fa_IR-ganji-medium.onnx"
        private const val MIN_REFERENCE_SECONDS = 2.0
    }

    private lateinit var recordButton: MaterialButton
    private lateinit var pickFileButton: MaterialButton
    private lateinit var referenceStatusText: TextView
    private lateinit var referencePlaybackContainer: View
    private lateinit var referencePlayButton: ImageButton
    private lateinit var cloneInputText: TextInputEditText
    private lateinit var cloneErrorText: TextView
    private lateinit var cloneGenerateButton: MaterialButton
    private lateinit var cloneProgressSpinner: ProgressBar
    private lateinit var cloneStatusText: TextView
    private lateinit var clonePlaybackContainer: View
    private lateinit var clonePlayPauseButton: ImageButton
    private lateinit var clonePlaybackSeekBar: SeekBar
    private lateinit var cloneSaveButton: MaterialButton
    private lateinit var cloneShareButton: MaterialButton

    private var baseTts: OfflineTts? = null
    private var toneConverter: ToneColorConverter? = null

    private val recorder = VoiceRecorder()
    private var referenceSamples: FloatArray? = null
    private var referenceSampleRate: Int = ToneColorConverter.SAMPLE_RATE
    private var referenceWavFile: File? = null

    private var referenceMediaPlayer: MediaPlayer? = null
    private var outputMediaPlayer: MediaPlayer? = null
    private var lastGeneratedFile: File? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var seekUpdateRunnable: Runnable? = null

    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginRecording()
        } else {
            showError(getString(R.string.error_record_permission_denied))
        }
    }

    private val pickAudioFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            handlePickedFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_clone)

        bindViews()
        setupListeners()
        loadEngines()
    }

    private fun bindViews() {
        recordButton = findViewById(R.id.recordButton)
        pickFileButton = findViewById(R.id.pickFileButton)
        referenceStatusText = findViewById(R.id.referenceStatusText)
        referencePlaybackContainer = findViewById(R.id.referencePlaybackContainer)
        referencePlayButton = findViewById(R.id.referencePlayButton)
        cloneInputText = findViewById(R.id.cloneInputText)
        cloneErrorText = findViewById(R.id.cloneErrorText)
        cloneGenerateButton = findViewById(R.id.cloneGenerateButton)
        cloneProgressSpinner = findViewById(R.id.cloneProgressSpinner)
        cloneStatusText = findViewById(R.id.cloneStatusText)
        clonePlaybackContainer = findViewById(R.id.clonePlaybackContainer)
        clonePlayPauseButton = findViewById(R.id.clonePlayPauseButton)
        clonePlaybackSeekBar = findViewById(R.id.clonePlaybackSeekBar)
        cloneSaveButton = findViewById(R.id.cloneSaveButton)
        cloneShareButton = findViewById(R.id.cloneShareButton)
    }

    private fun setupListeners() {
        recordButton.setOnClickListener { onRecordClicked() }
        pickFileButton.setOnClickListener { pickAudioFile.launch("audio/*") }
        referencePlayButton.setOnClickListener { onReferencePlayClicked() }
        cloneGenerateButton.setOnClickListener { onGenerateClicked() }
        clonePlayPauseButton.setOnClickListener { onOutputPlayPauseClicked() }
        cloneSaveButton.setOnClickListener { onSaveClicked() }
        cloneShareButton.setOnClickListener { onShareClicked() }

        clonePlaybackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) outputMediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadEngines() {
        setBusy(true, getString(R.string.status_generating))
        Thread {
            try {
                val assetManager = application.assets
                val dataDirPath = com.example.persiantts.EspeakDataInstaller.ensure(applicationContext)

                val modelConfig = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$BASE_VOICE_DIR/$BASE_VOICE_MODEL",
                        lexicon = "",
                        tokens = "$BASE_VOICE_DIR/tokens.txt",
                        dataDir = dataDirPath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val ttsConfig = OfflineTtsConfig(model = modelConfig)
                val tts = OfflineTts(assetManager = assetManager, config = ttsConfig)
                val converter = ToneColorConverter(assetManager)

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        // اکتیویتی قبل از پایان بارگذاری بسته شده؛ منابع بومی را همین‌جا آزاد کن
                        // وگرنه در onDestroy() (که زودتر و با فیلدهای null اجرا شده) رها می‌مانند.
                        tts.free()
                        converter.close()
                    } else {
                        baseTts = tts
                        toneConverter = converter
                        setBusy(false, getString(R.string.status_ready))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize voice clone engines", e)
                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    showError(getString(R.string.error_clone_engine_init_failed))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // ضبط نمونه صدا
    // ---------------------------------------------------------------------

    private fun onRecordClicked() {
        if (recorder.recording) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecording()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecording() {
        hideError()
        val pcmFile = File(cacheDir, "voice_ref_raw.pcm")
        recorder.start(pcmFile) { error ->
            mainHandler.post {
                Log.e(TAG, "Recording failed", error)
                showError(getString(R.string.error_record_failed))
                recordButton.text = getString(R.string.btn_start_record)
            }
        }
        recordButton.text = getString(R.string.btn_stop_record)
        referenceStatusText.text = getString(R.string.status_recording)
        referencePlaybackContainer.visibility = View.GONE
    }

    private fun stopRecording() {
        recorder.stop()
        recordButton.text = getString(R.string.btn_start_record)

        val pcmFile = File(cacheDir, "voice_ref_raw.pcm")
        if (!pcmFile.exists() || pcmFile.length() <= 0) {
            showError(getString(R.string.error_record_failed))
            return
        }

        val wavFile = File(cacheDir, "voice_ref.wav")
        try {
            AudioIo.pcmFileToWav(pcmFile, wavFile, VoiceRecorder.SAMPLE_RATE)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write reference wav", e)
            showError(getString(R.string.error_record_failed))
            return
        }

        val wave = WaveReader.readWave(wavFile.absolutePath)
        applyReferenceSamples(wave.samples, wave.sampleRate, wavFile)
    }

    private fun handlePickedFile(uri: Uri) {
        hideError()
        Thread {
            try {
                // فایل انتخاب‌شده را به یک مسیر محلی کپی می‌کنیم چون MediaExtractor به مسیر فایل نیاز دارد
                val localCopy = File(cacheDir, "voice_ref_picked")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localCopy).use { output -> input.copyTo(output) }
                } ?: throw IOException("امکان باز کردن فایل انتخاب‌شده نبود")

                val decoded = AudioIo.decodeToMonoFloat(localCopy.absolutePath)

                mainHandler.post {
                    applyReferenceSamples(decoded.samples, decoded.sampleRate, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode picked audio file", e)
                mainHandler.post { showError(getString(R.string.error_pick_file_failed)) }
            }
        }.start()
    }

    private fun applyReferenceSamples(samples: FloatArray, sampleRate: Int, wavFile: File?) {
        val durationSeconds = samples.size.toDouble() / sampleRate.toDouble()
        if (durationSeconds < MIN_REFERENCE_SECONDS) {
            showError(getString(R.string.error_reference_too_short))
            return
        }

        var sumSquares = 0.0
        for (s in samples) sumSquares += (s * s).toDouble()
        val rms = kotlin.math.sqrt(sumSquares / samples.size)
        if (rms < 0.001) {
            showError(getString(R.string.error_reference_silent))
            return
        }

        referenceSamples = samples
        referenceSampleRate = sampleRate
        referenceWavFile = wavFile
        referenceStatusText.text = getString(R.string.status_reference_ready)
        referencePlaybackContainer.visibility = View.VISIBLE
    }

    private fun onReferencePlayClicked() {
        val file = referenceWavFile
        if (file != null && file.exists()) {
            startReferencePlayback(file.absolutePath)
            return
        }

        // نمونه از فایل انتخاب‌شده آمده؛ برای پیش‌گوش دادن، دوباره آن را به WAV موقت بنویسیم.
        // نوشتن روی دیسک ممکن است چند ده میلی‌ثانیه طول بکشد؛ روی ترد جدا انجام می‌شود تا UI فریز نشود.
        val samples = referenceSamples ?: return
        referencePlayButton.isEnabled = false
        Thread {
            val tempWav = File(cacheDir, "voice_ref_preview.wav")
            writeFloatSamplesAsWav(samples, referenceSampleRate, tempWav)
            mainHandler.post {
                referencePlayButton.isEnabled = true
                startReferencePlayback(tempWav.absolutePath)
            }
        }.start()
    }

    /** MediaPlayer.prepareAsync() مسدودکننده نیست؛ آماده شدن با onPreparedListener اعلام می‌شود. */
    private fun startReferencePlayback(path: String) {
        referenceMediaPlayer?.release()
        referenceMediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(path)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { referencePlayButton.setImageResource(android.R.drawable.ic_media_play) }
            prepareAsync()
        }
        referencePlayButton.setImageResource(android.R.drawable.ic_media_pause)
    }

    private fun writeFloatSamplesAsWav(samples: FloatArray, sampleRate: Int, outFile: File) {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            bytes.putShort((clamped * 32767f).toInt().toShort())
        }
        FileOutputStream(outFile).use { out ->
            out.write(ByteArray(44))
            out.write(bytes.array())
        }
        AudioIo.writeWavHeader(outFile, bytes.array().size, sampleRate)
    }

    // ---------------------------------------------------------------------
    // شبیه‌سازی + تولید
    // ---------------------------------------------------------------------

    private fun onGenerateClicked() {
        hideError()
        val text = cloneInputText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            showError(getString(R.string.error_empty_text))
            return
        }
        val refSamples = referenceSamples
        if (refSamples == null) {
            showError(getString(R.string.error_no_reference))
            return
        }
        val tts = baseTts
        val converter = toneConverter
        if (tts == null || converter == null) {
            showError(getString(R.string.error_clone_engine_init_failed))
            return
        }

        resetOutputPlaybackState()
        setBusy(true, getString(R.string.status_synthesizing_base))

        Thread {
            try {
                // ۱. تولید گفتار پایه با Piper (صدای گنجی)، ۲۲۰۵۰ هرتز — بدون نیاز به resample
                val baseAudio: GeneratedAudio = tts.generate(text = text, sid = 0, speed = 1.0f)
                if (baseAudio.samples.isEmpty()) {
                    mainHandler.post {
                        setBusy(false, getString(R.string.status_ready))
                        showError(getString(R.string.error_clone_failed))
                    }
                    return@Thread
                }

                mainHandler.post { setBusy(true, getString(R.string.status_extracting_tone)) }

                // ۲. استخراج رنگ صدای نمونه‌ی کاربر و رنگ صدای خروجی Piper (برای src_tone)
                val targetTone = converter.extractToneColor(refSamples, referenceSampleRate)
                val sourceTone = converter.extractToneColor(baseAudio.samples, baseAudio.sampleRate)

                mainHandler.post { setBusy(true, getString(R.string.status_converting_tone)) }

                // ۳. اعمال تن صدای نمونه روی محتوای گفتاریِ Piper
                val converted = converter.convert(baseAudio.samples, sourceTone, targetTone)

                val outFile = File(cacheDir, "voice_clone_output.wav")
                writeFloatSamplesAsWav(converted, ToneColorConverter.SAMPLE_RATE, outFile)

                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    lastGeneratedFile = outFile
                    showOutputPlaybackControls()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice cloning failed", e)
                mainHandler.post {
                    setBusy(false, getString(R.string.status_ready))
                    showError(getString(R.string.error_clone_failed))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // پخش/ذخیره/اشتراک‌گذاریِ خروجی (مطابق الگوی MainActivity)
    // ---------------------------------------------------------------------

    private fun onOutputPlayPauseClicked() {
        val file = lastGeneratedFile ?: return

        if (outputMediaPlayer == null) {
            outputMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    clonePlayPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    stopSeekUpdates()
                    clonePlaybackSeekBar.progress = 0
                }
                clonePlaybackSeekBar.max = duration
            }
            outputMediaPlayer?.start()
            clonePlayPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            startSeekUpdates()
            return
        }

        val player = outputMediaPlayer!!
        if (player.isPlaying) {
            player.pause()
            clonePlayPauseButton.setImageResource(android.R.drawable.ic_media_play)
            stopSeekUpdates()
        } else {
            player.start()
            clonePlayPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            startSeekUpdates()
        }
    }

    private fun startSeekUpdates() {
        stopSeekUpdates()
        seekUpdateRunnable = object : Runnable {
            override fun run() {
                val player = outputMediaPlayer ?: return
                try {
                    clonePlaybackSeekBar.progress = player.currentPosition
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
            val fileName = "voice_clone_$timestamp.wav"

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
            Toast.makeText(this, getString(R.string.error_clone_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "com.example.persiantts.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    private fun resetOutputPlaybackState() {
        outputMediaPlayer?.release()
        outputMediaPlayer = null
        lastGeneratedFile = null
        stopSeekUpdates()
        clonePlaybackSeekBar.progress = 0
        clonePlayPauseButton.setImageResource(android.R.drawable.ic_media_play)
        clonePlaybackContainer.visibility = View.GONE
        cloneSaveButton.isEnabled = false
        cloneShareButton.isEnabled = false
    }

    private fun showOutputPlaybackControls() {
        clonePlaybackContainer.visibility = View.VISIBLE
        cloneSaveButton.isEnabled = true
        cloneShareButton.isEnabled = true
    }

    private fun setBusy(busy: Boolean, statusMessage: String) {
        cloneProgressSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        cloneGenerateButton.isEnabled = !busy
        recordButton.isEnabled = !busy
        pickFileButton.isEnabled = !busy
        cloneStatusText.text = statusMessage
    }

    private fun showError(message: String) {
        cloneErrorText.text = message
        cloneErrorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        cloneErrorText.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recorder.recording) recorder.stop()
        stopSeekUpdates()
        referenceMediaPlayer?.release()
        referenceMediaPlayer = null
        outputMediaPlayer?.release()
        outputMediaPlayer = null
        baseTts?.free()
        baseTts = null
        toneConverter?.close()
        toneConverter = null
    }
}
