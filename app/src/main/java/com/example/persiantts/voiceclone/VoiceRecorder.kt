package com.example.persiantts.voiceclone

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

/**
 * ضبط صدا از میکروفون با AudioRecord (نه MediaRecorder) چون هدف، PCM خام ۱۶بیتی است که
 * می‌توان مستقیم آن را در قالب WAV نوشت — بدون نیاز به دیکود کردن دوباره‌ی خروجی فشرده‌ی
 * MediaRecorder (که پیش‌فرض AAC/3GP تولید می‌کند).
 *
 * ضبط روی یک Thread جدا انجام می‌شود (سازگار با الگوی بدون-coroutine باقی پروژه).
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 22050
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    val recording: Boolean get() = isRecording

    /**
     * ضبط را روی یک ترد جدا شروع می‌کند. فایل PCM خام (بدون هدر WAV) در pcmOutputFile نوشته می‌شود.
     * @param onError اگر AudioRecord قابل مقداردهی نباشد (مثلاً میکروفون در دسترس نیست) صدا زده می‌شود.
     */
    fun start(pcmOutputFile: File, onError: (Exception) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            onError(IllegalStateException("پیکربندی ضبط صدا روی این دستگاه پشتیبانی نمی‌شود"))
            return
        }
        val bufferSize = minBufferSize * 2

        val record: AudioRecord
        try {
            @Suppress("MissingPermission")
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError(IllegalStateException("راه‌اندازی میکروفون ناموفق بود"))
                return
            }
        } catch (e: SecurityException) {
            onError(e)
            return
        } catch (e: Exception) {
            onError(e)
            return
        }

        audioRecord = record
        isRecording = true

        recordThread = Thread {
            try {
                FileOutputStream(pcmOutputFile).use { out ->
                    val buffer = ByteArray(bufferSize)
                    record.startRecording()
                    while (isRecording) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
        recordThread?.start()
    }

    /** ضبط را متوقف می‌کند و منابع AudioRecord را آزاد می‌کند. باید از ترد UI صدا زده شود. */
    fun stop() {
        isRecording = false
        // audioRecord.stop() را قبل از join صدا می‌زنیم تا اگر ترد ضبط داخل یک read() مسدود
        // مانده باشد (مثلاً به‌خاطر فشار روی HAL صوتی)، متوقف‌شدن ضبط باعث برگشتن آن read شود؛
        // در غیر این صورت release() هم‌زمان با یک read فعال روی ترد دیگر فراخوانی می‌شد که
        // می‌تواند کرش بومی یا IllegalStateException ایجاد کند.
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // ضبط شروع نشده بود؛ بی‌خطر
        }
        try {
            recordThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordThread = null
        audioRecord?.release()
        audioRecord = null
    }
}
