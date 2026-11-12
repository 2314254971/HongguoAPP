// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.R
import com.hongguotv.core.AppCommentPages
import java.util.concurrent.Executors

/** 只读评论区：游标分页，滚到底继续加载。 */
class CommentsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val pages = AppCommentPages()
    private val adapter = CommentAdapter()

    private lateinit var listView: RecyclerView
    private lateinit var loading: View
    private lateinit var empty: TextView
    private lateinit var countView: TextView

    private var seriesId = ""
    private var episodeId = ""
    private var cursor = ""
    private var hasMore = true
    private var loadingPage = false
    private val seen = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_comments)
        applySystemBarPadding(findViewById(R.id.root))

        seriesId = intent.getStringExtra(EXTRA_SERIES).orEmpty()
        episodeId = intent.getStringExtra(EXTRA_EPISODE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        listView = findViewById(R.id.list)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        countView = findViewById(R.id.count)

        val title = findViewById<TextView>(R.id.title)
        title.text = if (label.isBlank()) "评论" else "评论 · $label"

        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        listView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val last = (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.findLastVisibleItemPosition() ?: return
                    if (last >= adapter.itemCount - 4) loadPage()
                }
            },
        )

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }

        if (seriesId.isBlank() || episodeId.isBlank()) {
            finish()
            return
        }
        loadPage()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun loadPage() {
        if (loadingPage || !hasMore) return
        loadingPage = true
        if (adapter.itemCount == 0) loading.visibility = View.VISIBLE
        val requestCursor = cursor
        io.execute {
            val result = runCatching { pages.read(seriesId, episodeId, requestCursor) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingPage = false
                loading.visibility = View.GONE
                result.onSuccess { page ->
                    val rows = page.optJSONArray("items")
                    val parsed = mutableListOf<CommentItem>()
                    if (rows != null) {
                        for (i in 0 until rows.length()) {
                            val row = rows.optJSONObject(i) ?: continue
                            val content = row.optString("content").trim()
                            if (content.isEmpty()) continue
                            if (!seen.add(row.optString("id"))) continue
                            parsed += CommentItem(
                                id = row.optString("id"),
                                author = row.optString("author"),
                                content = content,
                                created = row.optLong("created"),
                                likes = row.optLong("likes"),
                            )
                        }
                    }
                    adapter.addAll(parsed)
                    cursor = page.optString("nextCursor")
                    hasMore = page.optBoolean("hasMore") && cursor.isNotBlank()
                    val total = page.optLong("total")
                    countView.text = if (total > 0) "$total 条" else ""
                    if (adapter.itemCount == 0) {
                        empty.text = "这一集还没有评论"
                        empty.visibility = View.VISIBLE
                    } else {
                        empty.visibility = View.GONE
                    }
                }.onFailure {
                    if (adapter.itemCount == 0) {
                        empty.text = "评论加载失败：${it.message ?: "请稍后重试"}"
                        empty.visibility = View.VISIBLE
                    }
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

    companion object {
        const val EXTRA_SERIES = "series_id"
        const val EXTRA_EPISODE = "episode_id"
        const val EXTRA_LABEL = "episode_label"
    }
}
