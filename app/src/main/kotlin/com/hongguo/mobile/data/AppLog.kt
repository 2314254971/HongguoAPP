// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 本机日志：只写应用私有目录，不上传任何地方。
 * 位置：Android/data/<包名>/files/logs/app.log，超过 512 KB 时轮换为 app.log.1。
 */
object AppLog {

    private const val MAX_BYTES = 512 * 1024L
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    @Volatile
    private var target: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        val file = File(dir, "app.log")
        runCatching {
            if (file.length() > MAX_BYTES) {
                val old = File(dir, "app.log.1")
                old.delete()
                file.renameTo(old)
            }
        }
        target = file
        d("HGApp", "start v${version(context)}")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            w("HGCrash", "uncaught in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun file(): File? = target?.takeIf { it.exists() }

    /** 调系统分享面板把日志发出去；没有日志文件时返回 false。 */
    fun share(context: Context): Boolean {
        val log = file() ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", log)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出日志"))
            true
        }.getOrDefault(false)
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        Log.println(if (level == "W") Log.WARN else Log.DEBUG, tag, message)
        val file = target ?: return
        synchronized(lock) {
            runCatching {
                file.appendText("${LocalDateTime.now().format(formatter)} $level/$tag: $message\n")
                if (error != null) file.appendText(Log.getStackTraceString(error) + "\n")
            }
        }
    }
}
