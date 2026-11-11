// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguo.mobile.App
import com.hongguo.mobile.R
import com.hongguo.mobile.data.LocalLibrary
import com.hongguo.mobile.ui.dp
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 双视角播放器：
 * - 竖屏（默认）：视频宽度占满屏幕，高度按真实画幅计算；上下滑动切集。
 * - 横屏：全屏铺满，控件浮层，可随时切回竖屏。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var seriesId: String
    private lateinit var seriesTitle: String
    private var cover: String = ""
    private lateinit var episodes: ArrayList<String>
    private var index = 0
    private var landscape = false
    private var aspect = 16f / 9f
    private var requestId = 0
    private var opened: OpenedStream? = null
    private var dragging = false
    private var controlsVisible = true

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var episodeView: TextView
    private lateinit var centerPlay: ImageView
    private lateinit var playButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeView: TextView
    private lateinit var loadingBox: LinearLayout
    private lateinit var errorView: TextView

    private lateinit var player: ExoPlayer
    private var lastSavedAt = 0L

    /** 已经是"第几集 → 已解开的流"；切集时直接复用，避免再等一次网络解析。 */
    private var prefetched: Pair<Int, OpenedStream>? = null
    private var prefetchingIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        episodes = intent.getStringArrayListExtra(EXTRA_EPISODES) ?: arrayListOf()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, episodes.size - 1))

        if (episodes.isEmpty()) {
            finish()
            return
        }

        buildViews()
        initPlayer()
        play(index, resume = true)
    }

    // ---------------- 视图 ----------------

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER),
        )

        val overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        val back = iconButton(R.drawable.ic_back) { handleBack() }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = seriesTitle
        }
        episodeView = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
        }
        val rotate = iconButton(R.drawable.ic_rotate) { toggleOrientation() }
        val list = iconButton(R.drawable.ic_list) { showEpisodePicker() }
        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(
            titleView,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) },
        )
        topBar.addView(episodeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        topBar.addView(rotate, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(list, LinearLayout.LayoutParams(dp(44), dp(44)))

        overlay.addView(
            topBar,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP),
        )

        centerPlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66000000"))
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnClickListener { togglePlay() }
        }
        overlay.addView(centerPlay, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        seekBar = SeekBar(this).apply { max = 1000 }
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = if (::player.isInitialized) player.duration else 0
                    if (duration > 0) {
                        timeView.text = "${formatTime(duration * progress / 1000)} / ${formatTime(duration)}"
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    val duration = if (::player.isInitialized) player.duration else 0
                    if (duration > 0) player.seekTo(duration * (sb?.progress ?: 0) / 1000)
                    hideControlsLater()
                }
            },
        )
        timeView = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            text = "00:00 / 00:00"
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = iconButton(R.drawable.ic_prev) { previousEpisode() }
        playButton = iconButton(R.drawable.ic_pause) { togglePlay() }
        val next = iconButton(R.drawable.ic_next) { nextEpisode() }
        val spacer = View(this)
        controls.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        controls.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        controls.addView(spacer, LinearLayout.LayoutParams(0, dp(1), 1f))
        controls.addView(timeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        bottomBar.addView(seekBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bottomBar.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        overlay.addView(
            bottomBar,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM),
        )

        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        loadingBox.addView(ProgressBar(this))
        loadingBox.addView(
            TextView(this).apply {
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 13f
                text = "正在解析播放地址…"
                setPadding(0, dp(10), 0, 0)
            },
        )
        overlay.addView(loadingBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#CC1F222B"))
            }
            visibility = View.GONE
            setOnClickListener { play(index, resume = true) }
        }
        overlay.addView(errorView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        val hint = TextView(this).apply {
            text = "上下滑动切换集数 · 右上角可切横屏"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
        }
        overlay.addView(
            hint,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(96) },
        )
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(600)
            .withEndAction { hint.visibility = View.GONE }
            .start()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            topBar.setPadding(dp(4), bars.top + dp(6), dp(4), dp(6))
            bottomBar.setPadding(dp(12), dp(8), dp(12), bars.bottom + dp(14))
            insets
        }

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    val dy = e2.y - e1.y
                    if (abs(dy) > dp(70) && abs(velocityY) > abs(velocityX)) {
                        if (dy < 0) nextEpisode() else previousEpisode()
                        return true
                    }
                    return false
                }
            },
        )
        // 视频区与空白区都接受手势；控件是子 View，会先拿到自己的点击事件。
        val gesture = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
        playerView.setOnTouchListener(gesture)
        overlay.setOnTouchListener(gesture)

        updateHeader()
        updatePlayIcon()
        hideControlsLater()
    }

    private fun iconButton(icon: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    // ---------------- 播放器 ----------------

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        layoutVideo()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_ENDED -> nextEpisode()
                        Player.STATE_BUFFERING -> loadingBox.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            loadingBox.visibility = View.GONE
                            errorView.visibility = View.GONE
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayIcon()
                    if (isPlaying) hideControlsLater() else showControls()
                }
            },
        )
        handler.post(
            object : Runnable {
                override fun run() {
                    updateProgress()
                    handler.postDelayed(this, 500)
                }
            },
        )
    }

    private fun play(newIndex: Int, resume: Boolean) {
        if (newIndex !in episodes.indices) return
        index = newIndex
        val tone = ++requestId
        updateHeader()
        errorView.visibility = View.GONE

        // 已经预解析好这一集：直接接管，不再显示"正在解析"。
        prefetched?.let { cached ->
            if (cached.first != newIndex) {
                cached.second.close()
                prefetched = null
            }
        }
        val ready = prefetched?.second
        if (ready != null) {
            prefetched = null
            loadingBox.visibility = View.GONE
            attach(ready, newIndex, resume)
            prefetchNext()
            return
        }

        loadingBox.visibility = View.VISIBLE
        centerPlay.visibility = View.GONE
        val episodeId = episodes[newIndex]
        io.execute {
            val result = runCatching {
                PlaybackResolver.open(application as App, episodeId)
            }
            runOnUiThread {
                if (tone != requestId) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                result.onSuccess { attach(it, newIndex, resume) }
                    .onFailure {
                        loadingBox.visibility = View.GONE
                        showError(it.message ?: "播放失败，点此重试")
                    }
            }
        }
    }

    private fun attach(stream: OpenedStream, newIndex: Int, resume: Boolean) {
        val previous = opened
        opened = stream
        if (stream.width > 0 && stream.height > 0) {
            aspect = stream.width.toFloat() / stream.height
        }
        layoutVideo()

        val source = ProgressiveMediaSource.Factory(VideoDataSourceFactory(stream.video))
            .createMediaSource(MediaItem.fromUri(Uri.parse("hongguo://episode/$newIndex")))
        player.setMediaSource(source)
        player.prepare()

        if (resume) {
            val saved = (application as App).library.progressFor(seriesId)
            if (saved != null && saved.index == newIndex && saved.position in 1..(saved.duration - 5000)) {
                player.seekTo(saved.position)
            }
        }
        player.playWhenReady = true
        updatePlayIcon()

        if (previous != null) {
            handler.postDelayed({ previous.close() }, 1500)
        }
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        updatePlayIcon()
        showControls()
    }

    private fun updatePlayIcon() {
        val playing = ::player.isInitialized && player.isPlaying
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        centerPlay.visibility =
            if (playing || !controlsVisible) View.GONE else View.VISIBLE
        centerPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateHeader() {
        titleView.text = seriesTitle.ifBlank { "红果短剧" }
        episodeView.text = "第 ${index + 1} / ${episodes.size} 集"
    }

    private fun updateProgress() {
        if (!::player.isInitialized) return
        if (player.playbackState == Player.STATE_IDLE) return
        val duration = player.duration
        val position = player.currentPosition.coerceAtLeast(0)
        if (!dragging && duration > 0 && duration != C.TIME_UNSET) {
            seekBar.progress = ((position * 1000) / duration).toInt().coerceIn(0, 1000)
        }
        timeView.text = "${formatTime(position)} / ${formatTime(duration)}"
        if (duration > 0 && position > 0 && player.isPlaying) {
            saveProgress(false)
            // 播过 5 秒就把下一集解析好；临近片尾仍未解析则持续重试到成功。
            val remaining = duration - position
            if (position > 5000 || remaining in 1..8000) prefetchNext()
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0 || ms == C.TIME_UNSET) return "00:00"
        val total = ms / 1000
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun saveProgress(force: Boolean) {
        if (!::player.isInitialized) return
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 4000) return
        lastSavedAt = now
        (application as App).library.saveProgress(
            LocalLibrary.Progress(
                seriesId = seriesId,
                title = seriesTitle,
                cover = cover,
                index = index,
                position = player.currentPosition,
                duration = duration,
                updatedAt = now,
            ),
        )
    }

    // ---------------- 交互 ----------------

    private fun showError(message: String) {
        errorView.text = "$message\n（点击重试）"
        errorView.visibility = View.VISIBLE
        loadingBox.visibility = View.GONE
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        if (controlsVisible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            centerPlay.visibility = if (player.isPlaying) View.GONE else View.VISIBLE
            hideControlsLater()
        } else {
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            centerPlay.visibility = View.GONE
        }
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        updatePlayIcon()
        hideControlsLater()
    }

    private fun hideControlsLater() {
        handler.removeCallbacks(hideControlsTask)
        handler.postDelayed(hideControlsTask, 4000)
    }

    private val hideControlsTask = Runnable {
        if (dragging || !::player.isInitialized || !player.isPlaying) return@Runnable
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        centerPlay.visibility = View.GONE
    }

    private fun nextEpisode() {
        if (index + 1 < episodes.size) play(index + 1, resume = false)
        else toast("已经是最后一集")
    }

    private fun previousEpisode() {
        if (index > 0) play(index - 1, resume = false)
        else toast("已经是第一集")
    }

    private fun showEpisodePicker() {
        prefetchNext()
        lateinit var dialog: AlertDialog
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var cursor = 0
        while (cursor < episodes.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0 until 5) {
                val position = cursor + column
                if (position >= episodes.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
                    continue
                }
                val active = position == index
                row.addView(
                    TextView(this).apply {
                        text = (position + 1).toString()
                        gravity = Gravity.CENTER
                        setTextColor(if (active) Color.WHITE else Color.WHITE)
                        textSize = 14f
                        setBackgroundResource(
                            if (active) R.drawable.bg_episode_active else R.drawable.bg_episode,
                        )
                        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                            topMargin = dp(6)
                        }
                        setOnClickListener {
                            dialog.dismiss()
                            if (position != index) play(position, resume = false)
                        }
                    },
                )
            }
            rows.addView(row)
            cursor += 5
        }
        val scroll = ScrollView(this).apply { addView(rows) }
        dialog = AlertDialog.Builder(this)
            .setTitle("选集（共 ${episodes.size} 集）")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
    }

    /** 后台预解析下一集；失败不提示，真正切过去时还会再试一次。 */
    private fun prefetchNext() {
        val next = index + 1
        if (next >= episodes.size) return
        if (prefetched?.first == next || prefetchingIndex == next) return
        prefetchingIndex = next
        val tone = requestId
        io.execute {
            val result = runCatching {
                PlaybackResolver.open(application as App, episodes[next])
            }
            runOnUiThread {
                if (prefetchingIndex == next) prefetchingIndex = -1
                result.onSuccess { stream ->
                    if (tone == requestId && index + 1 == next) {
                        prefetched?.second?.close()
                        prefetched = next to stream
                    } else {
                        stream.close()
                    }
                }
            }
        }
    }

    private fun toggleOrientation() {
        landscape = !landscape
        requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun applySystemUi() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun layoutVideo() {
        if (!::playerView.isInitialized) return
        val metrics = resources.displayMetrics
        val params = playerView.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            params.width = MATCH_PARENT
            params.height = MATCH_PARENT
        } else {
            var width = metrics.widthPixels.toFloat()
            var height = width / if (aspect > 0.01f) aspect else 16f / 9f
            val maxHeight = metrics.heightPixels * 0.80f
            if (height > maxHeight) {
                height = maxHeight
                width = height * aspect
            }
            params.width = width.toInt()
            params.height = height.toInt()
        }
        params.gravity = Gravity.CENTER
        playerView.layoutParams = params
    }

    // ---------------- 生命周期 ----------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applySystemUi()
        layoutVideo()
    }

    override fun onStart() {
        super.onStart()
        applySystemUi()
        layoutVideo()
    }

    override fun onStop() {
        saveProgress(true)
        if (::player.isInitialized) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        prefetched?.second?.close()
        prefetched = null
        opened?.close()
        opened = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (landscape) {
            toggleOrientation()
        } else {
            finish()
        }
    }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "series_title"
        const val EXTRA_COVER = "series_cover"
        const val EXTRA_EPISODES = "episode_ids"
        const val EXTRA_INDEX = "episode_index"
    }
}
