package com.example.persiantts

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream

/**
 * کپی یک‌بارِ پوشه‌ی espeak-ng-data از assets به یک مسیر واقعی روی دیسک (filesDir)،
 * چون espeak-ng (کد C) با fopen مسیر واقعی می‌خواهد، نه AssetManager. این پوشه بین
 * موتور اصلی (MainActivity) و موتور شبیه‌سازی صدا (VoiceCloneActivity) مشترک است؛
 * چون هر دو اکتیویتی می‌توانند هم‌زمان روی ترد جدا این متد را صدا بزنند، با
 * synchronized روی یک قفل مشترک از کپی/حذف هم‌زمان و ناقص ماندن پوشه جلوگیری می‌شود.
 */
object EspeakDataInstaller {
    private val lock = Any()

    fun ensure(context: Context): String {
        synchronized(lock) {
            val destRoot = File(context.filesDir, "espeak-ng-data")
            val markerFile = File(context.filesDir, "espeak-ng-data.copied")
            if (destRoot.exists() && markerFile.exists()) {
                return destRoot.absolutePath
            }

            if (destRoot.exists()) {
                destRoot.deleteRecursively()
            }
            destRoot.mkdirs()
            copyAssetDir(context.assets, "espeak-ng-data", destRoot)
            markerFile.writeText("ok")
            return destRoot.absolutePath
        }
    }

    private fun copyAssetDir(assetManager: AssetManager, assetPath: String, destDir: File) {
        val entries = assetManager.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // این یک فایل است، نه پوشه
            copyAssetFile(assetManager, assetPath, File(destDir.parentFile, destDir.name))
            return
        }

        destDir.mkdirs()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries != null && childEntries.isNotEmpty()) {
                copyAssetDir(assetManager, childAssetPath, File(destDir, entry))
            } else {
                copyAssetFile(assetManager, childAssetPath, File(destDir, entry))
            }
        }
    }

    private fun copyAssetFile(assetManager: AssetManager, assetPath: String, destFile: File) {
        assetManager.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
