// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguo.mobile.data.ImageLoader
import com.hongguo.mobile.player.PlayerActivity
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import java.util.concurrent.Executors

/** 竖屏详情页：海报 + 简介 + 剧集网格，底部固定收藏/播放。 */
class DetailActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var seriesId: String
    private var seriesTitle = ""
    private var coverUrl = ""
    private var detail: Detail? = null
    private var expanded = false

    private lateinit var cover: PosterView
    private lateinit var titleView: TextView
    private lateinit var tagsView: TextView
    private lateinit var countView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var episodeGrid: LinearLayout
    private lateinit var toggleAll: TextView
    private lateinit var favBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var loading: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_detail)

        seriesId = intent.getStringExtra(EXTRA_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        coverUrl = intent.getStringExtra(EXTRA_COVER).orEmpty()

        cover = findViewById(R.id.cover)
        titleView = findViewById(R.id.title)
        tagsView = findViewById(R.id.tags)
        countView = findViewById(R.id.episodeCount)
        descriptionView = findViewById(R.id.description)
        episodeGrid = findViewById(R.id.episodeGrid)
        toggleAll = findViewById(R.id.toggleAll)
        favBtn = findViewById(R.id.favBtn)
        playBtn = findViewById(R.id.playBtn)
        loading = findViewById(R.id.loading)

        applySystemBarPadding(findViewById(R.id.root))

        titleView.text = seriesTitle
        ImageLoader.load(coverUrl, cover, dp(112))
        countView.text = "–"
        refreshFavorite()

        toggleAll.setOnClickListener {
            expanded = !expanded
            renderEpisodes()
        }
        favBtn.setOnClickListener { toggleFavorite() }
        playBtn.setOnClickListener { playAt(startIndex()) }

        if (seriesId.isBlank()) {
            finish()
            return
        }
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        loading.show()
        io.execute {
            val result = runCatching { (application as App).repo.detail(seriesId) }
            runOnUiThread {
                loading.hide()
                result.onSuccess { bind(it) }.onFailure {
                    descriptionView.text = "详情加载失败，请返回重试"
                    countView.text = "–"
                }
            }
        }
    }

    private fun bind(detail: Detail) {
        this.detail = detail
        val series = detail.series
        if (series.title.isNotBlank()) titleView.text = series.title
        tagsView.text = series.tags.ifBlank { series.badge }
        tagsView.visibility = if (tagsView.text.isBlank()) View.GONE else View.VISIBLE
        descriptionView.text = series.description.ifBlank { "暂无简介" }
        countView.text = "共 ${detail.episodes.size} 集"
        ImageLoader.load(series.cover.ifBlank { coverUrl }, cover, dp(112))
        refreshFavorite()
        renderEpisodes()
    }

    private fun renderEpisodes() {
        val detail = detail ?: return
        episodeGrid.removeAllViews()
        val episodes = detail.episodes
        val limit = if (expanded) episodes.size else minOf(PREVIEW_COUNT, episodes.size)
        val progress = (application as App).library.progressFor(seriesId)

        var index = 0
        while (index < limit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (column in 0 until COLUMNS) {
                val position = index + column
                if (position >= limit) {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    })
                    continue
                }
                row.addView(episodeButton(position + 1, position, progress?.index == position))
            }
            episodeGrid.addView(row)
            index += COLUMNS
        }

        toggleAll.visibility = if (episodes.size > PREVIEW_COUNT) View.VISIBLE else View.GONE
        toggleAll.setText(if (expanded) R.string.collapse else R.string.expand_all)
        refreshPlayButton()
    }

    private fun episodeButton(label: Int, position: Int, active: Boolean): TextView =
        TextView(this).apply {
            text = label.toString()
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else Color.parseColor("#F5F6F8"))
            textSize = 14f
            setBackgroundResource(if (active) R.drawable.bg_episode_active else R.drawable.bg_episode)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
                topMargin = dp(8)
            }
            setOnClickListener { playAt(position) }
        }

    private fun startIndex(): Int {
        val progress = (application as App).library.progressFor(seriesId)
        val size = detail?.episodes?.size ?: 0
        val index = progress?.index ?: 0
        return if (size > 0) index.coerceIn(0, size - 1) else 0
    }

    private fun playAt(index: Int) {
        val detail = detail ?: return
        if (detail.episodes.isEmpty()) {
            toast("这一部还没有可播放的剧集")
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
                .putExtra(PlayerActivity.EXTRA_TITLE, detail.series.title)
                .putExtra(PlayerActivity.EXTRA_COVER, detail.series.cover)
                .putStringArrayListExtra(
                    PlayerActivity.EXTRA_EPISODES,
                    ArrayList(detail.episodes),
                )
                .putExtra(PlayerActivity.EXTRA_INDEX, index.coerceIn(0, detail.episodes.size - 1)),
        )
    }

    private fun toggleFavorite() {
        val series = detail?.series ?: seriesPlaceholder()
        val app = application as App
        val added = app.library.toggleFavorite(series)
        // 云端收藏会在刷新时回来，本机取消要记一笔，避免又冒出来。
        if (added) app.account.unhideCloudFavorite(seriesId) else app.account.hideCloudFavorite(seriesId)
        toast(if (added) "已加入收藏" else "已取消收藏")
        refreshFavorite()
    }

    private fun seriesPlaceholder(): Series =
        Series(id = seriesId, title = seriesTitle, cover = coverUrl)

    private fun refreshFavorite() {
        val favorite = seriesId.isNotBlank() && (application as App).library.isFavorite(seriesId)
        favBtn.setText(if (favorite) R.string.favorited else R.string.favorite)
        favBtn.setTextColor(getColor(if (favorite) R.color.accent else R.color.text))
    }

    private fun refreshPlayButton() {
        val current = detail
        val progress = (application as App).library.progressFor(seriesId)
        playBtn.text = if (current != null && progress != null) {
            "继续观看 第 ${(progress.index + 1).coerceAtMost(current.episodes.size)} 集"
        } else {
            getString(R.string.play_now)
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
        const val EXTRA_ID = "series_id"
        const val EXTRA_TITLE = "series_title"
        const val EXTRA_COVER = "series_cover"
        private const val COLUMNS = 5
        private const val PREVIEW_COUNT = 30
    }
}
