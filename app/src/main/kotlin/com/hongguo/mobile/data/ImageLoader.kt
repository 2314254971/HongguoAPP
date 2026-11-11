// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 海报加载：内存 LRU + 磁盘缓存，按目标宽度降采样，避免整张大图进堆。 */
object ImageLoader {

    private val client = OkHttpClient()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.cacheDir, "posters").apply { mkdirs() }
    }

    fun load(url: String?, view: ImageView, targetPx: Int) {
        val key = url?.takeIf { it.isNotBlank() }
        if (key == null) {
            view.tag = null
            view.setImageDrawable(null)
            return
        }
        view.tag = key
        val cached = memory.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bitmap = decode(key, targetPx) ?: return@execute
            memory.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    private fun decode(url: String, targetPx: Int): Bitmap? = runCatching {
        val file = File(dir, name(url))
        val bytes = if (file.exists() && file.length() > 0) file.readBytes() else download(url)?.also {
            runCatching { file.writeBytes(it) }
        }
        bytes ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, targetPx.coerceAtLeast(1))
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }.getOrNull()

    private fun sampleSize(width: Int, target: Int): Int {
        var sample = 1
        while (width / sample > target * 2) sample *= 2
        return sample
    }

    private fun name(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
