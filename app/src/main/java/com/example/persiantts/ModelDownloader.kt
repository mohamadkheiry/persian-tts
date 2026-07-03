package com.example.persiantts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloader"

/**
 * توضیح یک فایل مدل که باید دانلود شود: مسیر نسبی داخل مخزن گیت‌هاب + نام فایل مقصد.
 */
data class ModelFile(
    /** مسیر فایل نسبت به ریشه‌ی مخزن، مثلاً "models/ganji/fa_IR-ganji-medium.onnx" */
    val repoPath: String,
    /** نام فایل روی دیسک مقصد، مثلاً "fa_IR-ganji-medium.onnx" */
    val destFileName: String,
    /**
     * آیا این فایل با Git LFS ردیابی می‌شود (فقط "*.onnx" طبق .gitattributes)؛ فایل‌های LFS از
     * media.githubusercontent.com و بقیه (مثل tokens.txt) از raw.githubusercontent.com گرفته می‌شوند.
     */
    val isLfs: Boolean
)

/** پیشرفت کلی دانلود یک مجموعه فایل (مثلاً همه‌ی فایل‌های یک صدا). */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val currentFileIndex: Int,
    val totalFiles: Int
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

class ModelDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * دانلودکننده‌ی مشترک مدل‌های ONNX/tokens.txt از مخزن گیت‌هاب عمومی پروژه (به‌عنوان CDN رایگان،
 * چون فایل‌های تبدیل‌شده از قبل در همان مخزن با Git LFS commit شده‌اند — نیازی به هاست/توکن جدید نیست).
 *
 * شیء ساده‌ی Kotlin (object) هم‌سو با سبک EspeakDataInstaller: بدون coroutines، فقط
 * HttpURLConnection خام (بدون کتابخانه‌ی HTTP جدید)، synchronized روی مسیر مقصد برای جلوگیری از
 * دانلود هم‌زمان تکراری وقتی چند صفحه/ترد هم‌زمان به یک مدل نیاز دارند.
 */
object ModelDownloader {

    private const val REPO_OWNER = "mohamadkheiry"
    private const val REPO_NAME = "persian-tts"
    private const val BRANCH = "main"
    private const val LFS_MEDIA_BASE = "https://media.githubusercontent.com/media/$REPO_OWNER/$REPO_NAME/$BRANCH"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$BRANCH"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024

    // یک قفل به‌ازای هر پوشه‌ی مقصد؛ از دانلود هم‌زمان دوباره‌ی همان مدل توسط دو ترد جلوگیری می‌کند.
    private val locks = HashMap<String, Any>()

    private fun lockFor(destDir: File): Any = synchronized(locks) {
        locks.getOrPut(destDir.absolutePath) { Any() }
    }

    fun urlFor(file: ModelFile): String {
        val base = if (file.isLfs) LFS_MEDIA_BASE else RAW_BASE
        return "$base/${file.repoPath}"
    }

    /**
     * بررسی می‌کند آیا همه‌ی فایل‌های داده‌شده از قبل در destDir با اندازه‌ی معتبر (>۰ بایت) موجودند.
     * این یک چک سریع بدون تماس شبکه‌ای است (برای تصمیم «نیاز به دانلود هست یا نه» قبل از نمایش UI).
     */
    fun isFullyDownloaded(destDir: File, files: List<ModelFile>): Boolean {
        return files.all { f ->
            val file = File(destDir, f.destFileName)
            file.exists() && file.length() > 0L
        }
    }

    /**
     * همه‌ی فایل‌های لیست‌شده را در destDir دانلود می‌کند (اگر از قبل با اندازه‌ی صحیح موجود نباشند).
     * پیشرفت تجمعی (همه‌ی فایل‌ها با هم) از طریق onProgress گزارش می‌شود.
     * این متد **بلاک‌کننده** است؛ باید روی Thread جدا صدا زده شود (نه UI thread).
     *
     * @throws ModelDownloadException با پیام فارسیِ روشن در صورت شکست شبکه/سرور.
     */
    @Throws(ModelDownloadException::class)
    fun downloadAll(destDir: File, files: List<ModelFile>, onProgress: ((DownloadProgress) -> Unit)? = null) {
        synchronized(lockFor(destDir)) {
            if (!destDir.exists()) destDir.mkdirs()

            // مرحله‌ی اول: اندازه‌ی واقعی هر فایل را از سرور بپرس (Content-Length) تا درصد کلی درست باشد.
            val sizes = LongArray(files.size)
            for (i in files.indices) {
                val destFile = File(destDir, files[i].destFileName)
                sizes[i] = if (destFile.exists() && destFile.length() > 0L) {
                    destFile.length()
                } else {
                    fetchContentLength(files[i])
                }
            }
            val totalBytes = sizes.sum().coerceAtLeast(1L)

            var bytesDoneBefore = 0L
            for (i in files.indices) {
                val file = files[i]
                val destFile = File(destDir, file.destFileName)
                val expectedSize = sizes[i]

                if (destFile.exists() && destFile.length() == expectedSize && expectedSize > 0L) {
                    // از قبل با اندازه‌ی صحیح موجود است؛ دانلود دوباره لازم نیست.
                    bytesDoneBefore += expectedSize
                    onProgress?.invoke(DownloadProgress(bytesDoneBefore, totalBytes, i + 1, files.size))
                    continue
                }

                val fileStartBytes = bytesDoneBefore
                downloadOneFile(file, destFile) { downloadedInFile ->
                    onProgress?.invoke(
                        DownloadProgress(
                            bytesDownloaded = fileStartBytes + downloadedInFile,
                            totalBytes = totalBytes,
                            currentFileIndex = i + 1,
                            totalFiles = files.size
                        )
                    )
                }
                bytesDoneBefore += destFile.length()
            }
        }
    }

    private fun fetchContentLength(file: ModelFile): Long {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlFor(file))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ModelDownloadException(NETWORK_ERROR_MESSAGE)
            }
            val len = connection.contentLengthLong
            return if (len > 0) len else 0L
        } catch (e: IOException) {
            Log.e(TAG, "HEAD request failed for ${file.repoPath}", e)
            throw ModelDownloadException(NETWORK_ERROR_MESSAGE, e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadOneFile(file: ModelFile, destFile: File, onFileProgress: (Long) -> Unit) {
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlFor(file))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ModelDownloadException(NETWORK_ERROR_MESSAGE)
            }
            val expectedLength = connection.contentLengthLong

            var downloaded = 0L
            connection.inputStream.use { input: InputStream ->
                tmpFile.outputStream().use { output: OutputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onFileProgress(downloaded)
                    }
                    output.flush()
                }
            }

            // بررسی صحت پایه: اگر سرور Content-Length داد، باید با اندازه‌ی نهایی فایل یکی باشد.
            if (expectedLength > 0 && tmpFile.length() != expectedLength) {
                tmpFile.delete()
                throw ModelDownloadException(NETWORK_ERROR_MESSAGE)
            }
            if (tmpFile.length() <= 0L) {
                tmpFile.delete()
                throw ModelDownloadException(NETWORK_ERROR_MESSAGE)
            }

            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                // Fallback در صورتی که renameTo بین پارتیشن‌ها/فایل‌سیستم‌ها fail کند
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: ModelDownloadException) {
            tmpFile.delete()
            throw e
        } catch (e: IOException) {
            tmpFile.delete()
            Log.e(TAG, "Download failed for ${file.repoPath}", e)
            throw ModelDownloadException(NETWORK_ERROR_MESSAGE, e)
        } finally {
            connection?.disconnect()
        }
    }

    /** پیام خطای فارسی مشترک؛ در Activity ها هم مستقیم قابل استفاده است. */
    const val NETWORK_ERROR_MESSAGE = "دانلود مدل با خطا مواجه شد؛ اتصال اینترنت را بررسی کنید"

    // -----------------------------------------------------------------
    // تعریف مدل‌های موجود پروژه (مسیرهای دقیق مطابق ساختار فعلی مخزن گیت‌هاب)
    // -----------------------------------------------------------------

    /** پوشه‌ی مقصد یک صدای Piper در filesDir. */
    fun voiceDestDir(context: Context, voiceAssetDir: String): File =
        File(File(context.filesDir, "voices"), voiceAssetDir)

    /** پوشه‌ی مقصد مدل‌های شبیه‌سازی صدا در filesDir. */
    fun voiceCloneDestDir(context: Context): File =
        File(context.filesDir, "voice_clone")

    fun voiceModelFiles(voiceAssetDir: String, onnxFileName: String): List<ModelFile> = listOf(
        ModelFile(
            repoPath = "models/$voiceAssetDir/$onnxFileName",
            destFileName = onnxFileName,
            isLfs = true
        ),
        ModelFile(
            repoPath = "models/$voiceAssetDir/tokens.txt",
            destFileName = "tokens.txt",
            isLfs = false
        )
    )

    val voiceCloneModelFiles: List<ModelFile> = listOf(
        ModelFile(
            repoPath = "models/voice_clone/tone_color_extract_model.onnx",
            destFileName = "tone_color_extract_model.onnx",
            isLfs = true
        ),
        ModelFile(
            repoPath = "models/voice_clone/tone_clone_model.onnx",
            destFileName = "tone_clone_model.onnx",
            isLfs = true
        )
    )

    /** پوشه‌ی مقصد مدل STT (تشخیص گفتار فارسی، Whisper large-v3-turbo) در filesDir. */
    fun sttDestDir(context: Context): File =
        File(context.filesDir, "stt")

    // Whisper large-v3-turbo (نسخه‌ی decoder-هرس‌شده‌ی large-v3، به‌همان‌اندازه چندزبانه ولی چند
    // برابر سریع‌تر روی CPU) — به‌جای base، طبق درخواست کاربر برای بهترین دقت/سرعت با گوشی ۱۶ گیگ رم.
    val sttModelFiles: List<ModelFile> = listOf(
        ModelFile(
            repoPath = "models/stt/whisper-turbo-encoder.int8.onnx",
            destFileName = "whisper-turbo-encoder.int8.onnx",
            isLfs = true
        ),
        ModelFile(
            repoPath = "models/stt/whisper-turbo-decoder.int8.onnx",
            destFileName = "whisper-turbo-decoder.int8.onnx",
            isLfs = true
        ),
        ModelFile(
            repoPath = "models/stt/tokens.txt",
            destFileName = "tokens.txt",
            isLfs = false  // tokens.txt یک فایل متنی معمولی است، مثل بقیه‌ی tokens.txt های صداهای Piper
        )
    )
}
