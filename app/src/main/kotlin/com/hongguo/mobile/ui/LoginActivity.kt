// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
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
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.WebLogin
import java.util.concurrent.Executors

/** 官方网页扫码登录；成功后返回 RESULT_OK，由账号页刷新数据。 */
class LoginActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as App

    private lateinit var qrImage: ImageView
    private lateinit var status: TextView
    private lateinit var refresh: TextView
    private lateinit var loading: View

    private var login: WebLogin? = null
    private var pollTask: Runnable? = null
    private var generation = 0
    private var deadline = 0L
    private var pollInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_login)
        applySystemBarPadding(findViewById(R.id.root))

        qrImage = findViewById(R.id.qrImage)
        status = findViewById(R.id.status)
        refresh = findViewById(R.id.refresh)
        loading = findViewById(R.id.loading)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        refresh.setOnClickListener { startQr() }

        startQr()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        login?.cancel()
        login = null
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startQr() {
        generation++
        pollTask?.let { handler.removeCallbacks(it) }
        pollTask = null
        login?.cancel()
        qrImage.visibility = View.INVISIBLE
        refresh.visibility = View.GONE
        status.text = "正在生成二维码…"
        loading.visibility = View.VISIBLE

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
                    setResult(RESULT_OK, Intent())
                    finish()
                }.onFailure {
                    status.text = "登录失败：${it.message ?: "请重试"}"
                    refresh.visibility = View.VISIBLE
                }
            }
        }
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
