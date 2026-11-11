// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguotv.core.Series

class FavoritesActivity : Activity() {

    private lateinit var adapter: SeriesAdapter
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_favorites)

        applySystemBarPadding(findViewById(R.id.root))

        empty = findViewById(R.id.empty)
        adapter = SeriesAdapter { openDetail(it) }
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@FavoritesActivity, 3)
            adapter = this@FavoritesActivity.adapter
        }
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val favorites = (application as App).library.favorites()
        adapter.clear()
        adapter.addAll(favorites)
        empty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
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
