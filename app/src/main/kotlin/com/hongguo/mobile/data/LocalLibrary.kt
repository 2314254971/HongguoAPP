// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import android.content.Context
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject

/** 本机收藏与观看进度；不写账号、不联网。 */
class LocalLibrary(context: Context) {

    data class Progress(
        val seriesId: String,
        val title: String,
        val cover: String,
        val index: Int,
        val position: Long,
        val duration: Long,
        val updatedAt: Long,
    )

    private val prefs = context.applicationContext.getSharedPreferences("library_v1", Context.MODE_PRIVATE)

    fun favorites(): List<Series> = runCatching {
        val array = JSONArray(prefs.getString(KEY_FAVORITES, "[]"))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Series.fromJson(it) }
        }
    }.getOrDefault(emptyList())

    fun isFavorite(id: String): Boolean = favorites().any { it.id == id }

    /** @return 操作后是否已收藏 */
    fun toggleFavorite(series: Series): Boolean {
        val current = favorites().toMutableList()
        val existing = current.indexOfFirst { it.id == series.id }
        if (existing >= 0) current.removeAt(existing) else current.add(0, series)
        writeFavorites(current)
        return existing < 0
    }

    private fun writeFavorites(items: List<Series>) {
        val array = JSONArray()
        items.take(MAX_FAVORITES).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    fun saveProgress(progress: Progress) {
        if (progress.duration <= 0) return
        val root = JSONObject(prefs.getString(KEY_PROGRESS, "{}") ?: "{}")
        root.put(
            progress.seriesId,
            JSONObject()
                .put("id", progress.seriesId)
                .put("title", progress.title)
                .put("cover", progress.cover)
                .put("index", progress.index)
                .put("position", progress.position)
                .put("duration", progress.duration)
                .put("updatedAt", progress.updatedAt),
        )
        // 只保留最近 200 部，避免 JSON 无限增长。
        val keys = root.keys().asSequence().toList()
        if (keys.size > MAX_PROGRESS) {
            keys.sortedBy { root.optJSONObject(it)?.optLong("updatedAt") ?: 0L }
                .take(keys.size - MAX_PROGRESS)
                .forEach { root.remove(it) }
        }
        prefs.edit().putString(KEY_PROGRESS, root.toString()).apply()
    }

    fun progressFor(seriesId: String): Progress? = runCatching {
        val o = JSONObject(prefs.getString(KEY_PROGRESS, "{}") ?: "{}").optJSONObject(seriesId)
            ?: return null
        Progress(
            seriesId = o.optString("id"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            index = o.optInt("index"),
            position = o.optLong("position"),
            duration = o.optLong("duration"),
            updatedAt = o.optLong("updatedAt"),
        )
    }.getOrNull()

    fun recent(): List<Progress> = runCatching {
        val root = JSONObject(prefs.getString(KEY_PROGRESS, "{}") ?: "{}")
        root.keys().asSequence().mapNotNull { key -> progressFor(key) }
            .sortedByDescending { it.updatedAt }
            .toList()
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_PROGRESS = "progress"
        const val MAX_FAVORITES = 500
        const val MAX_PROGRESS = 200
    }
}
