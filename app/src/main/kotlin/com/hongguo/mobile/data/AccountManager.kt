// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import android.content.Context
import com.hongguo.mobile.App
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import com.hongguotv.core.CloudProfile
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 账号状态：扫码换到的 Cookie 加密存本地，云端收藏另存一份明文列表做离线展示。
 * 所有网络方法都是阻塞的，调用方放在后台线程执行。
 */
class AccountManager(context: Context) {

    private val app = context.applicationContext as App
    private val store = AccountStore(context)
    private val cache =
        context.applicationContext.getSharedPreferences("account_cache", Context.MODE_PRIVATE)
    private val client by lazy { CloudAccountClient(app.http) }

    @Volatile
    private var current: AccountCredentials? = null

    @Volatile
    private var restored = false

    private val restoreLock = Any()

    /** Keystore 读取放在首次使用时，避免拖慢冷启动。 */
    private fun ensureRestored() {
        if (restored) return
        synchronized(restoreLock) {
            if (restored) return
            current = store.read()
            restored = true
        }
    }

    val credentials: AccountCredentials?
        get() {
            ensureRestored()
            return current
        }

    val isLoggedIn: Boolean get() = credentials != null

    var nickname: String = cache.getString(KEY_NICKNAME, "").orEmpty()
        private set

    /** 本机取消收藏过的云端剧集：刷新收藏时不要让它又冒出来。 */
    private val hiddenCloud: MutableSet<String> = runCatching {
        val array = JSONArray(cache.getString(KEY_HIDDEN, "[]"))
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            .toMutableSet()
    }.getOrDefault(mutableSetOf())

    /** 扫码确认后：注册一台设备 → 校验资料 → 保存会话。 */
    fun completeLogin(web: AccountCredentials): CloudProfile {
        val device = client.registerDevice() ?: throw IOException("账号设备初始化失败")
        val next = web.copy(deviceId = device.deviceId, installId = device.installId)
        val profile = client.profile(next)
        store.save(next)
        current = next
        restored = true
        nickname = profile.nickname
        cache.edit().putString(KEY_NICKNAME, profile.nickname).apply()
        return profile
    }

    fun refreshFavorites(): List<Series> {
        val session = credentials ?: return cachedFavorites()
        val series = client.favorites(session).map { it.series }
        writeCache(series)
        cache.edit().putLong(KEY_FAVORITES_AT, System.currentTimeMillis()).apply()
        return series.filterNot { it.id in hiddenCloud }
    }

    fun refreshHistory(): List<HistoryEntry> {
        val session = credentials ?: return cachedHistory()
        val rows = client.history(session).map {
            HistoryEntry(
                series = it.series,
                episodeIndex = it.episodeIndex,
                position = it.position,
                duration = it.duration,
                updatedAt = it.updatedAt,
            )
        }
        writeHistory(rows)
        cache.edit().putLong(KEY_HISTORY_AT, System.currentTimeMillis()).apply()
        return rows
    }

    fun cachedHistory(): List<HistoryEntry> = runCatching {
        val array = JSONArray(cache.getString(KEY_HISTORY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { HistoryEntry.fromJson(it) }
        }
    }.getOrDefault(emptyList())

    fun favoritesRefreshedAt(): Long = cache.getLong(KEY_FAVORITES_AT, 0L)

    fun historyRefreshedAt(): Long = cache.getLong(KEY_HISTORY_AT, 0L)

    fun cachedFavorites(): List<Series> = runCatching {
        val array = JSONArray(cache.getString(KEY_FAVORITES, "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { Series.fromJson(it) }
        }
    }.getOrDefault(emptyList()).filterNot { it.id in hiddenCloud }

    fun hideCloudFavorite(seriesId: String) {
        if (hiddenCloud.add(seriesId)) persistHidden()
    }

    fun unhideCloudFavorite(seriesId: String) {
        if (hiddenCloud.remove(seriesId)) persistHidden()
    }

    private fun persistHidden() {
        val array = JSONArray()
        hiddenCloud.forEach { array.put(it) }
        cache.edit().putString(KEY_HIDDEN, array.toString()).apply()
    }

    fun logout() {
        store.clear()
        current = null
        restored = true
        nickname = ""
        hiddenCloud.clear()
        cache.edit().clear().apply()
    }

    private fun writeCache(series: List<Series>) {
        val array = JSONArray()
        series.take(MAX_CACHE).forEach { array.put(it.toJson()) }
        cache.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private fun writeHistory(rows: List<HistoryEntry>) {
        val array = JSONArray()
        rows.take(MAX_HISTORY).forEach { array.put(it.toJson()) }
        cache.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val KEY_NICKNAME = "nickname"
        const val KEY_FAVORITES = "favorites"
        const val KEY_FAVORITES_AT = "favorites_at"
        const val KEY_HISTORY = "history"
        const val KEY_HISTORY_AT = "history_at"
        const val KEY_HIDDEN = "hidden_cloud"
        const val MAX_CACHE = 500
        const val MAX_HISTORY = 200
    }
}

/** 历史条目：云端与本地统一的展示模型。 */
data class HistoryEntry(
    val series: Series,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("series", series.toJson())
        .put("episodeIndex", episodeIndex)
        .put("position", position)
        .put("duration", duration)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry = HistoryEntry(
            series = Series.fromJson(o.getJSONObject("series")),
            episodeIndex = o.optInt("episodeIndex"),
            position = o.optLong("position"),
            duration = o.optLong("duration"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}
