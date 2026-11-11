// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguo.mobile.data.HistoryEntry
import com.hongguotv.core.Series
import java.util.concurrent.Executors

/**
 * 我的：登录状态 + 收藏/历史两个页签。
 * 先渲染缓存，超过 5 分钟或手动刷新才请求网络；列表按页追加，不一次性铺满。
 */
class AccountActivity : Activity() {

    private enum class Tab { FAVORITES, HISTORY }

    private val io = Executors.newSingleThreadExecutor()
    private val app get() = application as App

    private lateinit var listView: RecyclerView
    private lateinit var loading: View
    private lateinit var empty: TextView
    private lateinit var nameView: TextView
    private lateinit var subView: TextView
    private lateinit var loginBtn: TextView
    private lateinit var logoutBtn: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var tabHistory: TextView
    private lateinit var refreshBtn: TextView

    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var tab = Tab.FAVORITES
    private var favorites: List<Series> = emptyList()
    private var history: List<HistoryEntry> = emptyList()
    private var favoritesShown = 0
    private var historyShown = 0
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_account)
        applySystemBarPadding(findViewById(R.id.root))

        listView = findViewById(R.id.list)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        nameView = findViewById(R.id.name)
        subView = findViewById(R.id.sub)
        loginBtn = findViewById(R.id.loginBtn)
        logoutBtn = findViewById(R.id.logoutBtn)
        tabFavorites = findViewById(R.id.tabFavorites)
        tabHistory = findViewById(R.id.tabHistory)
        refreshBtn = findViewById(R.id.refreshBtn)

        seriesAdapter = SeriesAdapter { openDetail(it.id, it.title, it.cover, -1, 0L) }
        historyAdapter = HistoryAdapter {
            openDetail(it.series.id, it.series.title, it.series.cover, it.episodeIndex, it.position)
        }

        listView.layoutManager = GridLayoutManager(this, FAVORITE_COLUMNS)
        listView.adapter = seriesAdapter
        listView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val manager = recyclerView.layoutManager ?: return
                    val last = if (manager is GridLayoutManager) {
                        manager.findLastVisibleItemPosition()
                    } else {
                        (manager as LinearLayoutManager).findLastVisibleItemPosition()
                    }
                    val total = recyclerView.adapter?.itemCount ?: return
                    if (last >= total - 5) loadMore()
                }
            },
        )

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        tabFavorites.setOnClickListener { switchTab(Tab.FAVORITES) }
        tabHistory.setOnClickListener { switchTab(Tab.HISTORY) }
        loginBtn.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        refreshBtn.setOnClickListener { refresh(tab) }
        logoutBtn.setOnClickListener { confirmLogout() }
    }

    override fun onResume() {
        super.onResume()
        updateHeader()
        loadFromCache()
        ensureFresh(tab, force = false)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 数据 ----------------

    private fun updateHeader() {
        val loggedIn = app.account.isLoggedIn
        nameView.text = if (loggedIn) app.account.nickname.ifBlank { "已登录" } else "未登录"
        subView.text =
            if (loggedIn) "云端收藏与观看历史已同步"
            else "登录后同步云端收藏；历史先记录本机观看"
        loginBtn.visibility = if (loggedIn) View.GONE else View.VISIBLE
        logoutBtn.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }

    private fun loadFromCache() {
        val localFavorites = app.library.favorites()
        favorites =
            if (app.account.isLoggedIn) merge(localFavorites, app.account.cachedFavorites())
            else localFavorites
        history =
            if (app.account.isLoggedIn) app.account.cachedHistory()
            else localHistory()
        render(reset = true)
    }

    private fun localHistory(): List<HistoryEntry> =
        app.library.recent().map {
            HistoryEntry(
                series = Series(id = it.seriesId, title = it.title, cover = it.cover),
                episodeIndex = it.index,
                position = it.position,
                duration = it.duration,
                updatedAt = it.updatedAt,
            )
        }

    private fun merge(local: List<Series>, cloud: List<Series>): List<Series> =
        (local + cloud).distinctBy { it.id }

    private fun ensureFresh(target: Tab, force: Boolean) {
        if (!app.account.isLoggedIn) return
        val now = System.currentTimeMillis()
        val last =
            if (target == Tab.FAVORITES) app.account.favoritesRefreshedAt()
            else app.account.historyRefreshedAt()
        if (!force && now - last < CACHE_TTL_MS) return
        refresh(target)
    }

    private fun refresh(target: Tab) {
        if (refreshing) return
        refreshing = true
        if (currentEmpty()) {
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
        io.execute {
            if (target == Tab.FAVORITES) {
                val result = runCatching { app.account.refreshFavorites() }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshing = false
                    loading.visibility = View.GONE
                    result.onSuccess { cloud ->
                        favorites = merge(app.library.favorites(), cloud)
                        if (tab == Tab.FAVORITES) render(reset = true)
                    }.onFailure {
                        if (currentEmpty()) showEmpty("收藏读取失败：${it.message ?: "请稍后重试"}")
                    }
                    // 刷新期间切到了另一个页签：把它补上。
                    if (tab != Tab.FAVORITES) ensureFresh(tab, force = false)
                }
            } else {
                val result = runCatching { app.account.refreshHistory() }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshing = false
                    loading.visibility = View.GONE
                    result.onSuccess { rows ->
                        history = rows
                        if (tab == Tab.HISTORY) render(reset = true)
                    }.onFailure {
                        if (currentEmpty()) showEmpty("历史读取失败：${it.message ?: "请稍后重试"}")
                    }
                    if (tab != Tab.HISTORY) ensureFresh(tab, force = false)
                }
            }
        }
    }

    private fun currentEmpty(): Boolean =
        (tab == Tab.FAVORITES && favorites.isEmpty()) || (tab == Tab.HISTORY && history.isEmpty())

    private fun showEmpty(message: String) {
        empty.text = message
        empty.visibility = View.VISIBLE
    }

    // ---------------- 列表 ----------------

    private fun render(reset: Boolean) {
        if (tab == Tab.FAVORITES) {
            if (reset) {
                seriesAdapter.clear()
                favoritesShown = minOf(PAGE_SIZE, favorites.size)
                seriesAdapter.addAll(favorites.take(favoritesShown))
            } else {
                if (favoritesShown >= favorites.size) return
                val next = favorites.drop(favoritesShown).take(PAGE_SIZE)
                favoritesShown += next.size
                seriesAdapter.addAll(next)
            }
            updateEmptyState(
                favorites.isEmpty(),
                if (app.account.isLoggedIn) "还没有收藏" else "本机还没有收藏，去首页挑一部吧",
            )
        } else {
            if (reset) {
                historyAdapter.clear()
                historyShown = minOf(PAGE_SIZE, history.size)
                historyAdapter.addAll(history.take(historyShown))
            } else {
                if (historyShown >= history.size) return
                val next = history.drop(historyShown).take(PAGE_SIZE)
                historyShown += next.size
                historyAdapter.addAll(next)
            }
            updateEmptyState(
                history.isEmpty(),
                if (app.account.isLoggedIn) "云端还没有观看记录" else "本机还没有观看记录",
            )
        }
    }

    private fun updateEmptyState(isEmpty: Boolean, message: String) {
        empty.text = message
        empty.visibility = if (isEmpty && !refreshing) View.VISIBLE else View.GONE
    }

    private fun loadMore() {
        if (tab == Tab.FAVORITES) {
            if (favoritesShown < favorites.size) render(reset = false)
        } else {
            if (historyShown < history.size) render(reset = false)
        }
    }

    private fun switchTab(target: Tab) {
        if (tab == target) return
        tab = target
        val on = getColor(R.color.text)
        val off = getColor(R.color.text_muted)
        tabFavorites.setTextColor(if (target == Tab.FAVORITES) on else off)
        tabFavorites.setTypeface(null, if (target == Tab.FAVORITES) Typeface.BOLD else Typeface.NORMAL)
        tabHistory.setTextColor(if (target == Tab.HISTORY) on else off)
        tabHistory.setTypeface(null, if (target == Tab.HISTORY) Typeface.BOLD else Typeface.NORMAL)

        listView.layoutManager =
            if (target == Tab.FAVORITES) GridLayoutManager(this, FAVORITE_COLUMNS)
            else LinearLayoutManager(this)
        listView.adapter = if (target == Tab.FAVORITES) seriesAdapter else historyAdapter
        listView.scrollToPosition(0)
        loading.visibility = if (refreshing && currentEmpty()) View.VISIBLE else View.GONE
        render(reset = true)
        ensureFresh(target, force = false)
    }

    // ---------------- 交互 ----------------

    private fun openDetail(id: String, title: String, cover: String, index: Int, position: Long) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_ID, id)
                .putExtra(DetailActivity.EXTRA_TITLE, title)
                .putExtra(DetailActivity.EXTRA_COVER, cover)
                .putExtra(DetailActivity.EXTRA_START_INDEX, index)
                .putExtra(DetailActivity.EXTRA_START_POSITION, position),
        )
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后本机不再保留账号信息，本地收藏和观看进度不受影响。")
            .setPositiveButton("退出") { _, _ ->
                app.account.logout()
                updateHeader()
                loadFromCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applySystemBarPadding(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private companion object {
        const val FAVORITE_COLUMNS = 3
        const val PAGE_SIZE = 30
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
