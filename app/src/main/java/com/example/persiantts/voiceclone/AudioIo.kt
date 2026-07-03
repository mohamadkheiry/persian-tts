package com.example.persiantts.voiceclone

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ابزارهای کمکیِ ورودی/خروجی صدا برای بخش «شبیه‌سازی صدا»:
 *  - نوشتن هدر WAV دستی (برای خروجی AudioRecord که PCM خام می‌دهد)
 *  - دیکود فایل‌های صوتی دلخواه (mp3/m4a/aac/ogg/...) به PCM با MediaExtractor + MediaCodec
 *    (بدون هیچ وابستگی خارجی؛ هر دو کلاس، API استاندارد اندروید هستند)
 *  - تبدیل PCM 16-bit به آرایه‌ی float در بازه‌ی [-1, 1] (فرمتی که sherpa-onnx/ONNX Runtime می‌خواهند)
 */
object AudioIo {

    /**
     * هدر استاندارد WAV (PCM 16-bit, مونو) را جلوی داده‌ی PCM خام می‌نویسد.
     * برای خروجی AudioRecord استفاده می‌شود که خودش فقط بایت خام می‌دهد.
     */
    fun writeWavHeader(file: File, pcmDataSize: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16) {
        RandomAccessFile(file, "rw").use { raf ->
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val totalDataLen = pcmDataSize + 36

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // اندازه‌ی بلوک fmt
            header.putShort(1) // فرمت PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(pcmDataSize)

            raf.seek(0)
            raf.write(header.array())
        }
    }

    /**
     * یک فایل PCM خام (بدون هدر، تولیدشده توسط AudioRecord) را به فایل WAV معتبر تبدیل می‌کند.
     * فایل مقصد از نو نوشته می‌شود.
     */
    fun pcmFileToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int = 1) {
        pcmFile.inputStream().use { input ->
            FileOutputStream(wavFile).use { output ->
                // جای خالی برای هدر؛ بعداً پر می‌شود
                output.write(ByteArray(44))
                input.copyTo(output)
            }
        }
        writeWavHeader(wavFile, pcmFile.length().toInt(), sampleRate, channels)
    }

    /** یک آرایه‌ی PCM 16-bit little-endian را به FloatArray در بازه‌ی [-1,1] تبدیل می‌کند. */
    fun shortsToFloats(bytes: ByteArray, length: Int): FloatArray {
        val shortBuffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val result = FloatArray(shortBuffer.remaining())
        for (i in result.indices) {
            result[i] = shortBuffer.get(i) / 32768.0f
        }
        return result
    }

    /**
     * نتیجه‌ی دیکود یک فایل صوتی دلخواه: نمونه‌های مونوی float در بازه‌ی [-1,1] + نرخ نمونه‌برداری اصلی.
     */
    data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    /**
     * فایل صوتی دلخواه (mp3, m4a/aac, ogg, wav و ...) را با MediaExtractor+MediaCodec دیکود می‌کند
     * و در صورت چندکاناله بودن، به مونو تبدیل می‌کند (میانگین کانال‌ها).
     * اگر فایل قابل دیکود نباشد یا هیچ ترک صوتی نداشته باشد، استثنا پرتاب می‌شود.
     */
    fun decodeToMonoFloat(path: String): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("فایل صوتی معتبری در مسیر انتخاب‌شده پیدا نشد")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        // این‌ها فرمت اعلام‌شده‌ی track هستند؛ ممکن است دیکودر واقعی فرمت متفاوتی خروجی بدهد
        // (INFO_OUTPUT_FORMAT_CHANGED)، که در آن صورت زیر به‌روزرسانی می‌شوند.
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 22050
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val output = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        continue
                    }
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outputIndex)!!
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        val floats = shortsToFloats(chunk, chunk.size)
                        for (v in floats) output.add(v)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val interleaved = output.toFloatArray()
        val mono = if (channelCount <= 1) {
            interleaved
        } else {
            val frames = interleaved.size / channelCount
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channelCount) sum += interleaved[i * channelCount + c]
                sum / channelCount
            }
        }
        return DecodedAudio(mono, sampleRate)
    }
}
