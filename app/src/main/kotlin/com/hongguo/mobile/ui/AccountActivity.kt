// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.Series
import com.hongguotv.core.WebLogin
import java.util.concurrent.Executors

/**
 * 账号页：未登录时展示官方扫码登录，登录后展示云端收藏。
 * 二维码由红果官网登录流程生成，会话 Cookie 加密存本机。
 */
class AccountActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as App

    private lateinit var loginPanel: View
    private lateinit var accountPanel: View
    private lateinit var qrImage: ImageView
    private lateinit var status: TextView
    private lateinit var refresh: TextView
    private lateinit var nickname: TextView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var grid: RecyclerView
    private lateinit var loading: View
    private lateinit var adapter: SeriesAdapter

    private var login: WebLogin? = null
    private var pollTask: Runnable? = null
    private var generation = 0
    private var deadline = 0L
    private var pollInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_account)
        applySystemBarPadding(findViewById(R.id.root))

        loginPanel = findViewById(R.id.loginPanel)
        accountPanel = findViewById(R.id.accountPanel)
        qrImage = findViewById(R.id.qrImage)
        status = findViewById(R.id.status)
        refresh = findViewById(R.id.refresh)
        nickname = findViewById(R.id.nickname)
        count = findViewById(R.id.count)
        empty = findViewById(R.id.empty)
        grid = findViewById(R.id.grid)
        loading = findViewById(R.id.loading)

        adapter = SeriesAdapter { openDetail(it) }
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        refresh.setOnClickListener { startQr() }
        findViewById<View>(R.id.logout).setOnClickListener { confirmLogout() }

        if (app.account.isLoggedIn) {
            showAccount()
            loadFavorites(refresh = true)
        } else {
            showLogin()
            startQr()
        }
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        login?.cancel()
        login = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 登录 ----------------

    private fun startQr() {
        generation++
        pollTask?.let { handler.removeCallbacks(it) }
        pollTask = null
        login?.cancel()
        showLogin()
        loading.visibility = View.VISIBLE
        qrImage.visibility = View.INVISIBLE
        refresh.visibility = View.GONE

        val web = WebLogin(app.http)
        login = web
        val tone = generation
        io.execute {
            val result = runCatching { web.qr() }
            runOnUiThread {
                if (tone != generation || isFinishing || isDestroyed) return@runOnUiThread
                loading.visibility = View.GONE
                result.onSuccess { qr ->
                    val bitmap = BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                    if (bitmap == null) {
                        status.text = "二维码无法显示，请刷新"
                        refresh.visibility = View.VISIBLE
                        return@runOnUiThread
                    }
                    qrImage.setImageBitmap(bitmap)
                    qrImage.visibility = View.VISIBLE
                    status.text = "扫码后在手机上确认登录"
                    deadline = SystemClock.elapsedRealtime() + qr.expiresIn.coerceIn(1, 300) * 1000L
                    startPolling(web, tone)
                }.onFailure {
                    status.text = "二维码获取失败：${it.message ?: "请重试"}"
                    refresh.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startPolling(web: WebLogin, tone: Int) {
        val task = object : Runnable {
            override fun run() {
                val self = this
                if (tone != generation || isFinishing || isDestroyed) return
                if (SystemClock.elapsedRealtime() >= deadline) {
                    status.text = "二维码已过期，请刷新"
                    refresh.visibility = View.VISIBLE
                    return
                }
                if (pollInFlight) {
                    handler.postDelayed(self, 500)
                    return
                }
                pollInFlight = true
                io.execute {
                    val result = runCatching { if (web.poll()) web.credentials() else null }
                    runOnUiThread {
                        pollInFlight = false
                        if (tone != generation || isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { credentials ->
                            if (credentials != null) completeLogin(credentials)
                            else handler.postDelayed(self, 1500)
                        }.onFailure {
                            handler.postDelayed(self, 2000)
                        }
                    }
                }
            }
        }
        pollTask = task
        handler.postDelayed(task, 1200)
    }

    private fun completeLogin(web: AccountCredentials) {
        generation++
        pollTask?.let { handler.removeCallbacks(it) }
        pollTask = null
        login?.cancel()
        login = null
        loading.visibility = View.VISIBLE
        status.text = "已确认，正在初始化账号…"

        val tone = generation
        io.execute {
            val result = runCatching { app.account.completeLogin(web) }
            runOnUiThread {
                if (tone != generation || isFinishing || isDestroyed) return@runOnUiThread
                loading.visibility = View.GONE
                result.onSuccess { profile ->
                    toast("已登录 ${profile.nickname}")
                    showAccount()
                    loadFavorites(refresh = true)
                }.onFailure {
                    status.text = "登录失败：${it.message ?: "请重试"}"
                    refresh.visibility = View.VISIBLE
                }
            }
        }
    }

    // ---------------- 云端收藏 ----------------

    private fun loadFavorites(refresh: Boolean) {
        val cached = app.account.cachedFavorites()
        render(cached)
        if (!refresh) return
        io.execute {
            val result = runCatching { app.account.refreshFavorites() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { render(it) }.onFailure {
                    if (cached.isEmpty()) {
                        empty.text = "云端收藏读取失败：${it.message ?: "请稍后重试"}"
                        empty.visibility = View.VISIBLE
                        grid.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun render(series: List<Series>) {
        adapter.clear()
        adapter.addAll(series)
        count.text = "共 ${series.size} 部"
        empty.text = "云端还没有收藏"
        empty.visibility = if (series.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (series.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------- 状态切换 ----------------

    private fun showLogin() {
        loginPanel.visibility = View.VISIBLE
        accountPanel.visibility = View.GONE
        status.text = "正在生成二维码…"
        refresh.visibility = View.GONE
        loading.visibility = View.GONE
        adapter.clear()
    }

    private fun showAccount() {
        loginPanel.visibility = View.GONE
        accountPanel.visibility = View.VISIBLE
        nickname.text = app.account.nickname.ifBlank { "已登录" }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后本机不再保留账号信息，本地收藏和观看进度不受影响。")
            .setPositiveButton("退出") { _, _ ->
                app.account.logout()
                showLogin()
                startQr()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openDetail(series: Series) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_ID, series.id)
                .putExtra(DetailActivity.EXTRA_TITLE, series.title)
                .putExtra(DetailActivity.EXTRA_COVER, series.cover),
        )
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
}
