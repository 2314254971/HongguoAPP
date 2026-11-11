// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

class SearchActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adapter: SeriesAdapter
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var loading: View
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_search)

        applySystemBarPadding(findViewById(R.id.root))

        input = findViewById(R.id.input)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)

        adapter = SeriesAdapter { openDetail(it) }
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, 3)
            adapter = this@SearchActivity.adapter
        }

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.submit).setOnClickListener { search() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }
        input.requestFocus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun search() {
        val keyword = input.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty() || searching) return
        hideKeyboard()
        searching = true
        loading.show()
        empty.hide()
        io.execute {
            val app = application as App
            // searchAll 是官网 JSON 接口，电视版也用它；search() 会按 genre 过滤掉官网结果。
            val result = runCatching { app.repo.searchAll(keyword, 1) }
                .recoverCatching { app.repo.search(keyword, 1) }
            runOnUiThread {
                searching = false
                loading.hide()
                adapter.clear()
                result.onSuccess { page ->
                    adapter.addAll(page.items)
                    empty.text = getString(R.string.no_result)
                    empty.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure {
                    empty.text = "搜索失败：${it.message ?: "请稍后重试"}"
                    empty.show()
                }
            }
        }
    }

    private fun openDetail(series: Series) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_ID, series.id)
                .putExtra(DetailActivity.EXTRA_TITLE, series.title)
                .putExtra(DetailActivity.EXTRA_COVER, series.cover),
        )
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
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
