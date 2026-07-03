package com.example.persiantts.stt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.persiantts.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "SttActivity"

/**
 * صفحه‌ی «تبدیل گفتار به نوشتار» (Speech-to-Text) — قابلیتی مستقل از موتور اصلی Piper در MainActivity
 * و از صفحه‌ی شبیه‌سازی صدا، اما ورودی صدا را از همان کلاس‌های مشترک [com.example.persiantts.voiceclone.VoiceRecorder]
 * و [com.example.persiantts.voiceclone.AudioIo] می‌گیرد (هر دو public در پکیج voiceclone هستند و
 * از قبل با import کاملاً مشخص از پکیج‌های دیگر استفاده می‌شوند — مثل ارجاع VoiceCloneActivity به
 * com.example.persiantts.ModelDownloader؛ نیازی به کپی/جابه‌جایی فایل نبود).
 *
 * معماری (کاملاً آفلاین بعد از دانلود مدل، بدون هیچ تماس شبکه‌ای دیگر):
 *  ۱. کاربر یک نمونه صدا ضبط می‌کند (AudioRecord → WAV، ۲۲۰۵۰ هرتز) یا فایل صوتی دلخواه انتخاب
 *     می‌کند (دیکود با AudioIo.decodeToMonoFloat، نرخ نمونه‌برداری هر چیزی می‌تواند باشد).
 *  ۲. چون Whisper دقیقاً ۱۶۰۰۰ هرتز مونو انتظار دارد، نمونه‌ها با یک resample خطی ساده به ۱۶۰۰۰
 *     هرتز تبدیل می‌شوند (مشابه resampleLinear در ToneColorConverter، اما چون آن متد private و
 *     مخصوص هدف ۲۲۰۵۰ است، این‌جا نسخه‌ی معادل و عمومی‌تر بازنویسی شده).
 *  ۳. مدل Whisper large-v3-turbo (int8، از طریق sherpa-onnx OfflineRecognizer) گفتار فارسی را به
 *     متن تبدیل می‌کند (language="fa", task="transcribe").
 *
 * منبع مدل: sherpa-onnx Whisper "large-v3-turbo" (چندزبانه، decoder هرس‌شده‌ی large-v3، int8) —
 * csukuangfj/sherpa-onnx-whisper-turbo روی HuggingFace. جزئیات کامل انتخاب مدل (چرا Whisper به‌جای
 * Vosk، چرا turbo به‌جای base/tiny/...) در بخش «تبدیل گفتار به نوشتار» CLAUDE.md مستند شده.
 */
class SttActivity : AppCompatActivity() {

    companion object {
        /** Whisper همیشه ۱۶۰۰۰ هرتز مونو انتظار دارد (نرخ نمونه‌برداری استخراج مل-اسپکتروگرام). */
        private const val WHISPER_SAMPLE_RATE = 16000
        private const val MIN_AUDIO_SECONDS = 0.5
    }

    private lateinit var recordButton: MaterialButton
    private lateinit var pickFileButton: MaterialButton
    private lateinit var inputStatusText: TextView
    private lateinit var errorText: TextView
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var progressSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextInputEditText
    private lateinit var copyButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private var recognizer: OfflineRecognizer? = null

    private val recorder = com.example.persiantts.voiceclone.VoiceRecorder()
    private var lastTranscript: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

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
        setContentView(R.layout.activity_stt)

        bindViews()
        setupListeners()
        loadEngine()
    }

    private fun bindViews() {
        recordButton = findViewById(R.id.sttRecordButton)
        pickFileButton = findViewById(R.id.sttPickFileButton)
        inputStatusText = findViewById(R.id.sttInputStatusText)
        errorText = findViewById(R.id.sttErrorText)
        downloadProgressBar = findViewById(R.id.sttDownloadProgressBar)
        progressSpinner = findViewById(R.id.sttProgressSpinner)
        statusText = findViewById(R.id.sttStatusText)
        transcriptText = findViewById(R.id.sttTranscriptText)
        copyButton = findViewById(R.id.sttCopyButton)
        shareButton = findViewById(R.id.sttShareButton)
    }

    private fun setupListeners() {
        recordButton.setOnClickListener { onRecordClicked() }
        pickFileButton.setOnClickListener { pickAudioFile.launch("audio/*") }
        copyButton.setOnClickListener { onCopyClicked() }
        shareButton.setOnClickListener { onShareClicked() }
    }

    // ---------------------------------------------------------------------
    // بارگذاری موتور (دانلود مدل در صورت نیاز + ساخت OfflineRecognizer)
    // ---------------------------------------------------------------------

    private fun loadEngine() {
        hideError()
        val assetEncoderPath = "stt/whisper-turbo-encoder.int8.onnx"
        val assetDecoderPath = "stt/whisper-turbo-decoder.int8.onnx"
        val assetTokensPath = "stt/tokens.txt"
        val bundled = com.example.persiantts.ModelDownloader.existsInAssets(applicationContext, assetEncoderPath)

        val destDir = com.example.persiantts.ModelDownloader.sttDestDir(applicationContext)
        val files = com.example.persiantts.ModelDownloader.sttModelFiles

        Thread {
            try {
                // اگر مدل Whisper داخل APK بسته‌بندی شده مستقیم از assets بارگذاری می‌شود؛ وگرنه
                // طبق معماری on-demand (بخش ۱۰ CLAUDE.md) قبل از اولین استفاده دانلود می‌شود.
                if (!bundled && !com.example.persiantts.ModelDownloader.isFullyDownloaded(destDir, files)) {
                    mainHandler.post { setDownloading(true, 0) }
                    com.example.persiantts.ModelDownloader.downloadAll(destDir, files) { progress ->
                        mainHandler.post { setDownloading(true, progress.percent) }
                    }
                    mainHandler.post { setDownloading(false, 100) }
                }

                mainHandler.post { setBusy(true, getString(R.string.status_loading_voice)) }

                val modelConfig: OfflineModelConfig
                val recognizerAssetManager: android.content.res.AssetManager?
                if (bundled) {
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = assetEncoderPath,
                            decoder = assetDecoderPath,
                            language = "fa",
                            task = "transcribe"
                        ),
                        tokens = assetTokensPath,
                        modelType = "whisper",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    recognizerAssetManager = applicationContext.assets
                } else {
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = File(destDir, "whisper-turbo-encoder.int8.onnx").absolutePath,
                            decoder = File(destDir, "whisper-turbo-decoder.int8.onnx").absolutePath,
                            language = "fa",
                            task = "transcribe"
                        ),
                        tokens = File(destDir, "tokens.txt").absolutePath,
                        modelType = "whisper",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                    recognizerAssetManager = null
                }
                val recognizerConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = WHISPER_SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig
                )
                // assetManager غیر-null ⇒ newFromAsset، null ⇒ newFromFile (تأیید در CLAUDE.md).
                val newRecognizer = OfflineRecognizer(assetManager = recognizerAssetManager, config = recognizerConfig)

                mainHandler.post {
                    if (isFinishing || isDestroyed) {
                        // اکتیویتی قبل از پایان بارگذاری بسته شده؛ منبع بومی را همین‌جا آزاد کن
                        // (همان رفع باگ نشتی حافظه‌ی مستندشده برای VoiceCloneActivity در CLAUDE.md).
                        newRecognizer.release()
                    } else {
                        recognizer = newRecognizer
                        setBusy(false, getString(R.string.status_stt_ready))
                    }
                }
            } catch (e: com.example.persiantts.ModelDownloadException) {
                Log.e(TAG, "Failed to download STT model", e)
                mainHandler.post {
                    setDownloading(false, 0)
                    setBusy(false, getString(R.string.status_stt_ready))
                    showError(e.message ?: getString(R.string.error_download_failed))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize STT engine", e)
                mainHandler.post {
                    setDownloading(false, 0)
                    setBusy(false, getString(R.string.status_stt_ready))
                    showError(getString(R.string.error_stt_engine_init_failed))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // ضبط/انتخاب صدای ورودی
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
        val pcmFile = File(cacheDir, "stt_input_raw.pcm")
        recorder.start(pcmFile) { error ->
            mainHandler.post {
                Log.e(TAG, "Recording failed", error)
                showError(getString(R.string.error_record_failed))
                recordButton.text = getString(R.string.btn_start_record)
            }
        }
        recordButton.text = getString(R.string.btn_stop_record)
        inputStatusText.text = getString(R.string.status_recording)
        resetTranscript()
    }

    private fun stopRecording() {
        recorder.stop()
        recordButton.text = getString(R.string.btn_start_record)

        val pcmFile = File(cacheDir, "stt_input_raw.pcm")
        if (!pcmFile.exists() || pcmFile.length() <= 0) {
            showError(getString(R.string.error_stt_empty_audio))
            return
        }

        // نوشتن WAV و خواندن/پارس آن هر دو I/O دیسک هستند؛ روی ترد جدا انجام می‌شوند تا UI فریز نشود
        // (همان الگوی رفع‌شده در VoiceCloneActivity.onReferencePlayClicked — CLAUDE.md بخش ۹.۱).
        Thread {
            val wavFile = File(cacheDir, "stt_input.wav")
            try {
                com.example.persiantts.voiceclone.AudioIo.pcmFileToWav(
                    pcmFile,
                    wavFile,
                    com.example.persiantts.voiceclone.VoiceRecorder.SAMPLE_RATE
                )
                val wave = WaveReader.readWave(wavFile.absolutePath)
                mainHandler.post { processAudioSamples(wave.samples, wave.sampleRate) }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write recorded wav", e)
                mainHandler.post { showError(getString(R.string.error_record_failed)) }
            }
        }.start()
    }

    private fun handlePickedFile(uri: Uri) {
        hideError()
        resetTranscript()
        Thread {
            try {
                // فایل انتخاب‌شده را به یک مسیر محلی کپی می‌کنیم چون MediaExtractor به مسیر فایل نیاز دارد.
                val localCopy = File(cacheDir, "stt_input_picked")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localCopy).use { output -> input.copyTo(output) }
                } ?: throw IOException("امکان باز کردن فایل انتخاب‌شده نبود")

                val decoded = com.example.persiantts.voiceclone.AudioIo.decodeToMonoFloat(localCopy.absolutePath)

                mainHandler.post {
                    processAudioSamples(decoded.samples, decoded.sampleRate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode picked audio file", e)
                mainHandler.post { showError(getString(R.string.error_pick_file_failed)) }
            }
        }.start()
    }

    private fun processAudioSamples(samples: FloatArray, sampleRate: Int) {
        val durationSeconds = samples.size.toDouble() / sampleRate.toDouble()
        if (durationSeconds < MIN_AUDIO_SECONDS) {
            showError(getString(R.string.error_stt_empty_audio))
            return
        }

        var sumSquares = 0.0
        for (s in samples) sumSquares += (s * s).toDouble()
        val rms = kotlin.math.sqrt(sumSquares / samples.size)
        if (rms < 0.001) {
            showError(getString(R.string.error_stt_silent_audio))
            return
        }

        inputStatusText.text = getString(R.string.status_reference_ready)
        transcribe(samples, sampleRate)
    }

    // ---------------------------------------------------------------------
    // تشخیص گفتار (تبدیل به متن)
    // ---------------------------------------------------------------------

    private fun transcribe(samples: FloatArray, sampleRate: Int) {
        val engine = recognizer
        if (engine == null) {
            showError(getString(R.string.error_stt_engine_init_failed))
            return
        }

        resetTranscript()
        setBusy(true, getString(R.string.status_transcribing))

        Thread {
            try {
                val resampled = if (sampleRate == WHISPER_SAMPLE_RATE) {
                    samples
                } else {
                    resampleLinear(samples, sampleRate, WHISPER_SAMPLE_RATE)
                }

                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(resampled, WHISPER_SAMPLE_RATE)
                    engine.decode(stream)
                    val result = engine.getResult(stream)
                    val text = result.text.trim()

                    mainHandler.post {
                        setBusy(false, getString(R.string.status_stt_ready))
                        if (text.isEmpty()) {
                            showError(getString(R.string.error_transcription_failed))
                        } else {
                            showTranscript(text)
                        }
                    }
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post {
                    setBusy(false, getString(R.string.status_stt_ready))
                    showError(getString(R.string.error_transcription_failed))
                }
            }
        }.start()
    }

    /**
     * resample خطی ساده (linear interpolation) — معادل کوچک‌شده‌ی resampleLinear در
     * ToneColorConverter، اما آن متد private و مخصوص هدف ثابت ۲۲۰۵۰ هرتز است؛ این‌جا نرخ مقصد
     * پارامتر عمومی (همیشه ۱۶۰۰۰ برای Whisper) است. کیفیت حرفه‌ای هدف نیست، فقط کافی برای گفتار.
     */
    private fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val newLength = (samples.size * ratio).toInt().coerceAtLeast(1)
        val result = FloatArray(newLength)
        for (i in 0 until newLength) {
            val srcPos = i / ratio
            val idx0 = srcPos.toInt().coerceIn(0, samples.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(samples.size - 1)
            val frac = (srcPos - idx0).toFloat()
            result[i] = samples[idx0] * (1 - frac) + samples[idx1] * frac
        }
        return result
    }

    // ---------------------------------------------------------------------
    // خروجی: نمایش/کپی/اشتراک‌گذاری متن
    // ---------------------------------------------------------------------

    private fun showTranscript(text: String) {
        lastTranscript = text
        transcriptText.setText(text)
        copyButton.isEnabled = true
        shareButton.isEnabled = true
    }

    private fun resetTranscript() {
        lastTranscript = null
        transcriptText.setText(getString(R.string.status_no_transcript))
        copyButton.isEnabled = false
        shareButton.isEnabled = false
    }

    private fun onCopyClicked() {
        val text = lastTranscript ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(this, getString(R.string.status_copied), Toast.LENGTH_SHORT).show()
    }

    private fun onShareClicked() {
        val text = lastTranscript ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    // ---------------------------------------------------------------------
    // وضعیت UI مشترک (busy/downloading/error) — هم‌سو با الگوی VoiceCloneActivity
    // ---------------------------------------------------------------------

    private fun setBusy(busy: Boolean, statusMessage: String) {
        progressSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        recordButton.isEnabled = !busy
        pickFileButton.isEnabled = !busy
        statusText.text = statusMessage
    }

    /**
     * نمایش/پنهان‌سازی نوار پیشرفت درصدیِ دانلود مدل — جدا از [progressSpinner] (که برای
     * بارگذاری موتور/تبدیل گفتار نامعین است) تا وضعیت «دانلود» با «تبدیل»/«بارگذاری» قاطی نشود.
     */
    private fun setDownloading(downloading: Boolean, percent: Int) {
        downloadProgressBar.visibility = if (downloading) View.VISIBLE else View.GONE
        downloadProgressBar.progress = percent
        recordButton.isEnabled = !downloading
        pickFileButton.isEnabled = !downloading
        if (downloading) {
            statusText.text = getString(R.string.status_downloading_stt_model, percent)
        }
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
        if (recorder.recording) recorder.stop()
        recognizer?.release()
        recognizer = null
    }
}
