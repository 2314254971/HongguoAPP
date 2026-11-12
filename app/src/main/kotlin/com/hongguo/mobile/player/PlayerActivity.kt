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
import android.os.SystemClock
import android.util.Log
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
import com.hongguotv.core.TimedComments
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
    private val playerPrefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    /** 已经是"第几集 → 已解开的流"；切集时直接复用，避免再等一次网络解析。 */
    private var prefetched: Pair<Int, OpenedStream>? = null
    private var prefetchingIndex = -1

    /** 用户已经切到某一集，但这一集正在后台预解析：等它回来直接接管，不要再发一次请求。 */
    private var pendingIndex = -1

    /** 预解析窗口：初始 3 秒，按实测解析耗时的 EMA 自动调整。 */
    private val prefetchPolicy = PrefetchPolicy()
    private val prefetchAttempts = mutableMapOf<Int, Int>()
    private var prefetchCooldownUntil = 0L

    private var playbackSpeed = 1f
    private var initialIndex = 0
    private var startPosition = 0L

    private val app get() = application as App
    private val timedComments by lazy { TimedComments(app.http) }
    private var danmakuEnabled = true
    private var danmakuEpisode = ""
    private var danmakuTone = 0
    private lateinit var videoBox: FrameLayout
    private lateinit var danmakuView: DanmakuView
    private lateinit var danmakuButton: TextView
    private lateinit var speedButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        episodes = intent.getStringArrayListExtra(EXTRA_EPISODES) ?: arrayListOf()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, episodes.size - 1))
        initialIndex = index
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L)

        if (episodes.isEmpty()) {
            finish()
            return
        }

        playbackSpeed = playerPrefs.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 2f)
        danmakuEnabled = playerPrefs.getBoolean(KEY_DANMAKU, true)
        buildViews()
        initPlayer()
        play(index, resume = true)
    }

    // ---------------- 视图 ----------------

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        videoBox = FrameLayout(this)
        root.addView(
            videoBox,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER),
        )
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        videoBox.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        danmakuView = DanmakuView(this).apply { danmakuOn = danmakuEnabled }
        videoBox.addView(danmakuView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

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
                    // 拖到片尾附近要立刻预解析，不能等下一轮 500ms 心跳。
                    handler.postDelayed({ maybePrefetch() }, 250)
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
        danmakuButton = TextView(this).apply {
            text = "弹"
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(if (danmakuEnabled) getColor(R.color.accent) else Color.WHITE)
            setOnClickListener { toggleDanmaku() }
        }
        controls.addView(danmakuButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        speedButton = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = formatSpeed(playbackSpeed)
            setOnClickListener { showSpeedPicker() }
        }
        controls.addView(speedButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        controls.addView(
            timeView,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) },
        )

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
        player.setPlaybackSpeed(playbackSpeed)
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
                            maybeLoadDanmaku()
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
        prefetchAttempts.clear()
        danmakuEpisode = ""
        danmakuView.clear()
        updateHeader()
        errorView.visibility = View.GONE

        pendingIndex = -1

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
            Log.d(TAG, "prefetch hit ep=${newIndex + 1}")
            attach(ready, newIndex, resume)
            // 不再立刻解析再下一集：交给时间窗口判定，避免"刚开播就多发一次请求"。
            return
        }

        // 这一集的预解析还在路上：只等它，不要重复发起。
        if (prefetchingIndex == newIndex) {
            pendingIndex = newIndex
            loadingBox.visibility = View.VISIBLE
            centerPlay.visibility = View.GONE
            Log.d(TAG, "wait prefetch ep=${newIndex + 1}")
            return
        }

        loadingBox.visibility = View.VISIBLE
        centerPlay.visibility = View.GONE
        val episodeId = episodes[newIndex]
        Log.d(TAG, "resolve blocking ep=${newIndex + 1} id=$episodeId")
        val startedAt = SystemClock.elapsedRealtime()
        io.execute {
            val result = runCatching {
                PlaybackResolver.open(application as App, episodeId)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                if (tone != requestId) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                result.onSuccess {
                    // 阻塞切换的耗时同样是有价值的样本，别只让预解析自己学习。
                    prefetchPolicy.recordResolve(elapsed)
                    attach(it, newIndex, resume)
                }
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
        player.setPlaybackSpeed(playbackSpeed)
        player.prepare()

        if (startPosition > 0 && newIndex == initialIndex) {
            // 从历史点进来：按云端记录的位置续播，优先于本机进度。
            player.seekTo(startPosition)
            startPosition = 0
        } else if (resume) {
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
        danmakuView.update(position, player.isPlaying)
        if (duration > 0 && position > 0 && player.isPlaying) saveProgress(false)
        // 暂停时也判定：拖到片尾后即使没继续播，下一集也应该先解析好。
        maybePrefetch()
    }

    /**
     * 是否该开始预解析下一集。剩余时长按倍速换算成媒体时间，
     * 所以 2 倍速时窗口会自动翻倍；拖动进度后由 onStopTrackingTouch 立即触发一次。
     */
    private fun maybePrefetch() {
        // 还在等当前集的预解析结果时，不要再去解析下一集，避免抢带宽和重复请求。
        if (pendingIndex != -1) return
        val next = index + 1
        if (next >= episodes.size) return
        if (prefetched?.first == next || prefetchingIndex == next) return
        if (!::player.isInitialized) return
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        val coolingDown = SystemClock.elapsedRealtime() < prefetchCooldownUntil
        val attempts = prefetchAttempts[next] ?: 0
        if (!prefetchPolicy.shouldPrefetch(remaining, playbackSpeed, coolingDown, attempts)) return
        Log.d(
            TAG,
            "prefetch trigger ep=${next + 1} remaining=$remaining " +
                "threshold=${prefetchPolicy.thresholdMs} speed=$playbackSpeed",
        )
        prefetchAttempts[next] = attempts + 1
        prefetchNext()
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
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "resolve prefetch ep=${next + 1}")
        io.execute {
            val result = runCatching {
                PlaybackResolver.open(application as App, episodes[next]).also { stream ->
                    // 预读首个 512 KiB，切过去时 Media3 解析容器不再等网络。
                    runCatching { stream.video.warm(512 * 1024) }
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.close()
                    return@runOnUiThread
                }
                if (prefetchingIndex == next) prefetchingIndex = -1
                result.onSuccess { stream ->
                    // 预热也算进耗时，窗口按"实际从发起到可用"来估。
                    prefetchPolicy.recordResolve(elapsed)
                    when {
                        // 用户已经切到这一集：直接接管，这次预解析就是它的解析。
                        next == index && pendingIndex == next -> {
                            pendingIndex = -1
                            loadingBox.visibility = View.GONE
                            errorView.visibility = View.GONE
                            Log.d(TAG, "prefetch handoff ep=${next + 1}")
                            attach(stream, next, resume = false)
                        }
                        // 还是下一集：存起来等切换。
                        next == index + 1 -> {
                            prefetched?.second?.close()
                            prefetched = next to stream
                        }
                        else -> {
                            Log.d(TAG, "discard stale prefetch ep=${next + 1}")
                            stream.close()
                        }
                    }
                }.onFailure {
                    // 失败不要每 500ms 撞一次接口，冷却 2.5 秒再试。
                    prefetchCooldownUntil = SystemClock.elapsedRealtime() + 2_500
                    if (next == index && pendingIndex == next) {
                        pendingIndex = -1
                        Log.d(TAG, "prefetch failed, fallback ep=${next + 1}")
                        play(next, resume = false)
                    }
                }
            }
        }
    }

    private fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        danmakuView.danmakuOn = danmakuEnabled
        danmakuButton.setTextColor(if (danmakuEnabled) getColor(R.color.accent) else Color.WHITE)
        playerPrefs.edit().putBoolean(KEY_DANMAKU, danmakuEnabled).apply()
        if (danmakuEnabled) {
            maybeLoadDanmaku()
        } else {
            danmakuEpisode = ""
            danmakuView.clear()
        }
    }

    private fun maybeLoadDanmaku() {
        if (!danmakuEnabled || !::player.isInitialized) return
        val episodeId = episodes.getOrNull(index) ?: return
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        if (danmakuEpisode == episodeId) return
        danmakuEpisode = episodeId
        danmakuView.clear()
        val tone = ++danmakuTone
        io.execute {
            val rows = runCatching { fetchDanmaku(episodeId, duration) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (tone == danmakuTone && episodes.getOrNull(index) == episodeId) {
                    danmakuView.setItems(rows)
                }
            }
        }
    }

    /** 时间轴弹幕按区间分页，最多取 3 页，够覆盖一集短剧。 */
    private fun fetchDanmaku(episodeId: String, duration: Long): List<DanmakuItem> {
        val output = mutableListOf<DanmakuItem>()
        val total = duration.coerceIn(30_000L, 86_400_000L)
        var start = 0L
        var cursor = ""
        repeat(3) {
            val page = timedComments.read(seriesId, episodeId, start, total, cursor)
            val rows = page.optJSONArray("items") ?: return output
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val text = row.optString("text").trim()
                if (text.isEmpty()) continue
                output += DanmakuItem(
                    id = row.optString("id"),
                    text = text,
                    timeMs = row.optLong("timeMS"),
                )
            }
            if (!page.optBoolean("hasMore")) return output
            val next = page.optLong("nextMS")
            if (next <= start || next >= total) return output
            start = next
            cursor = page.optString("nextCursor")
        }
        return output
    }

    private fun showSpeedPicker() {
        val options = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = options.map { formatSpeed(it) }.toTypedArray()
        val checked = options.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setSpeed(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setSpeed(value: Float) {
        playbackSpeed = value.coerceIn(0.5f, 2f)
        if (::player.isInitialized) player.setPlaybackSpeed(playbackSpeed)
        playerPrefs.edit().putFloat(KEY_SPEED, playbackSpeed).apply()
        if (::speedButton.isInitialized) speedButton.text = formatSpeed(playbackSpeed)
        // 倍速变了，媒体时间窗口也跟着变，立刻重新判定一次。
        maybePrefetch()
    }

    private fun formatSpeed(value: Float): String {
        val text =
            if (value == value.toInt().toFloat()) value.toInt().toString()
            else String.format("%.2f", value).trimEnd('0').trimEnd('.')
        return "${text}x"
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
        if (!::videoBox.isInitialized) return
        val metrics = resources.displayMetrics
        val params = videoBox.layoutParams as FrameLayout.LayoutParams
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
        videoBox.layoutParams = params
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
        const val EXTRA_START_POSITION = "start_position"
        private const val KEY_SPEED = "speed"
        private const val KEY_DANMAKU = "danmaku"
        private const val TAG = "HGPlayer"
    }
}
