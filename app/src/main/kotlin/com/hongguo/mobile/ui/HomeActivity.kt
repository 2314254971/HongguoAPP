// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguotv.core.Series
import java.util.concurrent.Executors

/** 竖屏首页：竖向滚动的剧集卡片网格，滚到底自动翻页。 */
class HomeActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adapter: SeriesAdapter
    private lateinit var grid: RecyclerView
    private lateinit var loading: View
    private lateinit var errorBox: View
    private lateinit var errorText: TextView

    private var page = 0
    private var hasMore = true
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)

        grid = findViewById(R.id.grid)
        loading = findViewById(R.id.loading)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)

        applySystemBarPadding(findViewById(R.id.root))

        adapter = SeriesAdapter { openDetail(it) }
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter
        grid.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 6) loadNextPage()
                }
            },
        )

        findViewById<View>(R.id.searchBtn).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.favBtn).setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }
        findViewById<View>(R.id.accountBtn).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        findViewById<View>(R.id.retryBtn).setOnClickListener {
            errorBox.hide()
            loadNextPage()
        }

        loadNextPage()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun loadNextPage() {
        if (busy || !hasMore) return
        busy = true
        if (page == 0) loading.show()
        io.execute {
            val result = runCatching { (application as App).repo.home(page + 1) }
            runOnUiThread {
                busy = false
                loading.hide()
                result.onSuccess { catalog ->
                    page += 1
                    hasMore = catalog.hasMore
                    adapter.addAll(catalog.items)
                    if (adapter.itemCount == 0) showError("这里暂时没有内容")
                }.onFailure { showError("加载失败，请检查网络") }
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorBox.show()
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

    private companion object {
        const val COLUMNS = 3
    }
}
