package com.example.persiantts.voiceclone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * پیاده‌سازی «تبدیل رنگ صدا» (tone color conversion) بر پایه‌ی مدل OpenVoice
 * (نسخه‌ی ONNX جامعه: seasonstudio/openvoice_tone_clone_onnx روی HuggingFace).
 *
 * این کلاس مستقیماً با ONNX Runtime (کتابخانه‌ی جاوا/کاتلین رسمی مایکروسافت) کار می‌کند،
 * نه با sherpa-onnx — چون sherpa-onnx فقط برای مدل‌های ASR/TTS/VAD خودش binding کاتلین دارد
 * و اجرای یک گراف ONNX دلخواه (مثل مبدل رنگ صدا) را پشتیبانی نمی‌کند.
 *
 * جریان کار:
 *  ۱. صدای نمونه (مرجع) و صدای تولیدشده توسط Piper هر دو باید طیف‌نگاشت خطی (linear spectrogram)
 *     شوند — دقیقاً همان STFT که OpenVoice اصلی با تابع `spectrogram_torch` انجام می‌دهد
 *     (n_fft=۱۰۲۴, hop=۲۵۶, win=۱۰۲۴, پنجره‌ی Hann, padding از نوع reflect, بدون فشرده‌سازی لگاریتمی).
 *  ۲. مدل tone_color_extract_model.onnx از روی طیف‌نگاشت هر صدا یک بردار «رنگ صدا» ۲۵۶بعدی می‌سازد.
 *  ۳. مدل tone_clone_model.onnx صدای مبدأ (خروجی Piper) را با بردار رنگ صدای مقصد (نمونه‌ی کاربر)
 *     ترکیب می‌کند و موج خروجی را با تن‌رنگ نمونه ولی محتوای گفتاریِ Piper تولید می‌کند.
 *
 * نکته‌ی حیاتیِ سازگاری: نرخ نمونه‌برداری مدل tone_clone معادل ۲۲۰۵۰ هرتز است — که دقیقاً برابر
 * است با نرخ خروجی مدل‌های صوتی Piper بسته‌شده در این اپ (بررسی‌شده در
 * assets/ganji/fa_IR-ganji-medium.onnx.json → "sample_rate": 22050). یعنی صدای Piper نیازی
 * به resample شدن قبل از ورود به مبدل ندارد؛ فقط صدای مرجعِ کاربر (که ممکن است در هر نرخی
 * ضبط/انتخاب شده باشد) باید به ۲۲۰۵۰ هرتز resample شود.
 *
 * توجه: این دو مدل ONNX دیگر داخل assets بسته‌بندی نمی‌شوند — هنگام نیاز با [com.example.persiantts.ModelDownloader]
 * به filesDir دانلود می‌شوند (بخش ۱۰ CLAUDE.md)، پس این کلاس مسیر مطلق فایل‌سیستم می‌گیرد،
 * نه AssetManager.
 */
class ToneColorConverter(extractModelPath: String, cloneModelPath: String) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 22050
        private const val N_FFT = 1024
        private const val HOP_LENGTH = 256
        private const val WIN_LENGTH = 1024
        private const val TONE_EMBEDDING_SIZE = 256
        private const val DEFAULT_TAU = 0.3f

        /** پنجره‌ی Hann از پیش محاسبه‌شده (معادل torch.hann_window). */
        private val hannWindow: DoubleArray = DoubleArray(WIN_LENGTH) { i ->
            0.5 - 0.5 * cos(2.0 * PI * i / WIN_LENGTH)
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val extractSession: OrtSession
    private val cloneSession: OrtSession

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        extractSession = env.createSession(extractModelPath, options)
        cloneSession = env.createSession(cloneModelPath, options)
    }

    /**
     * از روی نمونه صدا (مونو، فلوت، هر نرخ نمونه‌برداری) بردار «رنگ صدا» ۲۵۶بعدی استخراج می‌کند.
     * اگر نرخ نمونه‌برداری برابر ۲۲۰۵۰ نباشد، ابتدا resample خطی انجام می‌شود.
     */
    fun extractToneColor(samples: FloatArray, sampleRate: Int): FloatArray {
        val resampled = if (sampleRate == SAMPLE_RATE) samples else resampleLinear(samples, sampleRate, SAMPLE_RATE)
        val spec = computeLinearSpectrogram(resampled)
        val shape = longArrayOf(1, spec.size.toLong(), spec[0].size.toLong())
        val flat = flatten2D(spec)

        OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(flat), shape).use { inputTensor ->
            extractSession.run(mapOf("input" to inputTensor)).use { result ->
                val output = result[0].value as Array<*>
                // خروجی به شکل [1, 256] است
                @Suppress("UNCHECKED_CAST")
                val row = output[0] as FloatArray
                return row
            }
        }
    }

    /**
     * صدای مبدأ (خروجی Piper، ۲۲۰۵۰ هرتز) را با رنگ صدای مقصد ترکیب می‌کند.
     * @param sourceSamples صدای Piper (مونو، فلوت، ۲۲۰۵۰ هرتز)
     * @param sourceTone بردار رنگ صدای همان صدای مبدأ (از extractToneColor روی خودِ خروجی Piper)
     * @param targetTone بردار رنگ صدای نمونه‌ی کاربر
     */
    fun convert(sourceSamples: FloatArray, sourceTone: FloatArray, targetTone: FloatArray, tau: Float = DEFAULT_TAU): FloatArray {
        val spec = computeLinearSpectrogram(sourceSamples)
        val numFrames = spec[0].size
        val shape = longArrayOf(1, spec.size.toLong(), numFrames.toLong())
        val flat = flatten2D(spec)

        val audioTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(flat), shape)
        val lengthTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1))
        val srcToneTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(sourceTone), longArrayOf(1, TONE_EMBEDDING_SIZE.toLong(), 1))
        val dstToneTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(targetTone), longArrayOf(1, TONE_EMBEDDING_SIZE.toLong(), 1))
        val tauTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatArrayOf(tau)), longArrayOf(1))

        try {
            val inputs = mapOf(
                "audio" to audioTensor,
                "audio_length" to lengthTensor,
                "src_tone" to srcToneTensor,
                "dest_tone" to dstToneTensor,
                "tau" to tauTensor
            )
            cloneSession.run(inputs).use { result ->
                // خروجی شماره‌ی صفر، شکل [1, 1, num_samples] است: موج صوتی نهایی
                val output = result[0].value
                return extractWaveform(output)
            }
        } finally {
            audioTensor.close()
            lengthTensor.close()
            srcToneTensor.close()
            dstToneTensor.close()
            tauTensor.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractWaveform(output: Any?): FloatArray {
        // output: Array<Array<FloatArray>> با شکل [1, 1, N]
        val batch = output as Array<*>
        val channel = batch[0] as Array<*>
        return channel[0] as FloatArray
    }

    private fun flatten2D(data: Array<FloatArray>): FloatArray {
        val freqBins = data.size
        val frames = data[0].size
        val flat = FloatArray(freqBins * frames)
        for (f in 0 until freqBins) {
            System.arraycopy(data[f], 0, flat, f * frames, frames)
        }
        return flat
    }

    /**
     * طیف‌نگاشت خطی (magnitude spectrogram) دقیقاً هم‌ارز با تابع spectrogram_torch در OpenVoice:
     * reflect padding به‌اندازه‌ی (n_fft-hop)/2 در دو طرف، سپس STFT با پنجره‌ی Hann و center=false،
     * و در پایان magnitude = sqrt(real^2 + imag^2 + 1e-6).
     * خروجی: آرایه‌ی [freq_bins][num_frames] با freq_bins = n_fft/2 + 1 = 513.
     */
    private fun computeLinearSpectrogram(samples: FloatArray): Array<FloatArray> {
        val pad = (N_FFT - HOP_LENGTH) / 2
        val padded = reflectPad(samples, pad)

        val numFrames = 1 + (padded.size - N_FFT) / HOP_LENGTH
        val freqBins = N_FFT / 2 + 1
        val spec = Array(freqBins) { FloatArray(maxOf(numFrames, 1)) }

        if (numFrames <= 0) {
            return Array(freqBins) { FloatArray(1) }
        }

        val real = DoubleArray(N_FFT)
        val imag = DoubleArray(N_FFT)

        for (frameIdx in 0 until numFrames) {
            val offset = frameIdx * HOP_LENGTH
            for (n in 0 until N_FFT) {
                val sampleVal = if (n < WIN_LENGTH) padded[offset + n] * hannWindow[n] else 0.0
                real[n] = sampleVal
                imag[n] = 0.0
            }
            dft(real, imag)
            for (k in 0 until freqBins) {
                val re = real[k]
                val im = imag[k]
                spec[k][frameIdx] = sqrt(re * re + im * im + 1e-6).toFloat()
            }
        }
        return spec
    }

    /**
     * DFT ساده (O(n^2)) برای یک فریم ۱۰۲۴نمونه‌ای. چون طول فریم ثابت و کوچک است (۱۰۲۴)
     * و سنتز صدای کوتاه (چند ثانیه تا حداکثر چند ده‌ثانیه) هدف است، DFT ساده به‌جای FFT
     * بهینه‌شده برای سادگی کد انتخاب شد؛ در صورت کند بودن روی گوشی‌های ضعیف، اولین گزینه‌ی
     * بهینه‌سازی جایگزینی آن با یک پیاده‌سازی Cooley-Tukey FFT است (n_fft=1024 توان دو است).
     */
    private fun dft(real: DoubleArray, imag: DoubleArray) {
        fft(real, imag)
    }

    /** پیاده‌سازی Cooley-Tukey FFT درجا (in-place)، برای طول‌های توان دو (اینجا همیشه ۱۰۲۴). */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curRe - imag[i + k + len / 2] * curIm
                    val vIm = real[i + k + len / 2] * curIm + imag[i + k + len / 2] * curRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun reflectPad(samples: FloatArray, pad: Int): FloatArray {
        val result = FloatArray(samples.size + 2 * pad)
        for (i in 0 until pad) {
            // بازتاب: نمونه‌ی ایندکس (pad - i) به‌جای منفی
            result[pad - 1 - i] = samples[min(i + 1, samples.size - 1)]
            result[result.size - pad + i] = samples[samples.size - 2 - min(i, samples.size - 2)]
        }
        System.arraycopy(samples, 0, result, pad, samples.size)
        return result
    }

    /** resample خطی ساده (کافی برای صدای گفتار؛ کیفیت حرفه‌ای هدف نیست). */
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

    override fun close() {
        extractSession.close()
        cloneSession.close()
    }
}
