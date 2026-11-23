// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs

/**
 * 时间轴弹幕层：位置由播放进度驱动，暂停即静止，拖动后重新对齐。
 * 字号可调，横竖屏各自按新的画布尺寸重算轨道与显示区域。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var items: List<DanmakuItem> = emptyList()
    private var scheduled: List<ScheduledDanmaku> = emptyList()
    private var cursor = 0
    private val active = mutableListOf<ScheduledDanmaku>()

    private var positionMs = 0L
    private var anchorRealtime = 0L
    private var playing = false
    private var lastPosition = 0L

    private var textSizeSp = DEFAULT_SIZE_SP
    private var lanes = 0
    private var lineHeight = 0f
    private var firstBaseline = 0f
    private var regionTop = 0
    private var regionHeight = 0

    var danmakuOn: Boolean = true
        set(value) {
            field = value
            if (!value) active.clear()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val strokeWidth = 2f * density

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = OPACITY
        typeface = Typeface.DEFAULT_BOLD
        textSize = spToPx(DEFAULT_SIZE_SP)
    }
    private val outline = TextPaint(fill).apply {
        color = Color.BLACK
        alpha = OPACITY
        style = Paint.Style.STROKE
        strokeWidth = this@DanmakuView.strokeWidth
    }

    fun setTextSizeSp(sizeSp: Int) {
        if (textSizeSp == sizeSp) return
        textSizeSp = sizeSp
        rebuild()
    }

    fun setItems(list: List<DanmakuItem>) {
        items = list
        rebuild()
    }

    fun clear() {
        items = emptyList()
        scheduled = emptyList()
        cursor = 0
        active.clear()
        invalidate()
    }

    fun update(ms: Long, isPlaying: Boolean) {
        positionMs = ms
        playing = isPlaying
        anchorRealtime = SystemClock.elapsedRealtime()
        if (!danmakuOn) return

        if (abs(ms - lastPosition) > 2_500) {
            // 拖动过进度：清空当前屏，按新位置重新对齐。
            active.clear()
            cursor = scheduled.indexOfFirst { it.endMs > ms }.let {
                if (it < 0) scheduled.size else it
            }
        }
        lastPosition = ms

        while (cursor < scheduled.size && scheduled[cursor].startMs <= ms) {
            val row = scheduled[cursor]
            if (row.endMs > ms) active += row
            cursor++
        }
        active.removeAll { it.endMs <= ms }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) {
            scheduled = emptyList()
            return
        }
        fill.textSize = spToPx(textSizeSp)
        outline.textSize = fill.textSize
        outline.strokeWidth = strokeWidth

        val metrics = fill.fontMetricsInt
        val textHeight = (metrics.bottom - metrics.top + strokeWidth).coerceAtLeast(1f)
        lineHeight = maxOf(textHeight, fill.textSize * LINE_HEIGHT_RATIO)

        // 顶部留给标题栏，底部留给控制条；竖屏视频区更矮，横屏更宽，都按当前画布算。
        val topInset = minOf(56f * density, height / 8f)
        val bottomInset = minOf(44f * density, height / 8f)
        val available = (height - topInset - bottomInset).coerceAtLeast(1f)
        regionTop = topInset.toInt()
        regionHeight = (available * AREA_FRACTION).toInt().coerceAtLeast(0)
        firstBaseline = regionTop - metrics.top + strokeWidth / 2f

        lanes = if (regionHeight < textHeight) {
            0
        } else {
            minOf(MAX_LANES, 1 + ((regionHeight - textHeight) / lineHeight).toInt())
        }

        scheduled = DanmakuSchedule.assign(
            items = items,
            lanes = lanes,
            travelMs = TRAVEL_MS,
            viewportWidthPx = width,
            gapPx = 8f * density + strokeWidth,
            measureText = fill::measureText,
        )
        cursor = 0
        active.clear()
        invalidate()
    }

    private fun spToPx(sp: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        resources.displayMetrics,
    )

    private fun currentMs(): Long =
        positionMs + if (playing) SystemClock.elapsedRealtime() - anchorRealtime else 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuOn || active.isEmpty() || lanes <= 0) return
        val now = currentMs()
        val save = canvas.save()
        canvas.clipRect(0f, regionTop.toFloat(), width.toFloat(), (regionTop + regionHeight).toFloat())
        for (row in active) {
            val progress = ((now - row.startMs).toFloat() / TRAVEL_MS).coerceIn(0f, 1f)
            val x = width - progress * (width + row.widthPx)
            val y = firstBaseline + row.lane * lineHeight
            canvas.drawText(row.text, x, y, outline)
            canvas.drawText(row.text, x, y, fill)
        }
        canvas.restoreToCount(save)
        if (playing) postInvalidateOnAnimation()
    }

    private companion object {
        const val DEFAULT_SIZE_SP = 18
        const val TRAVEL_MS = 7_000L
        const val LINE_HEIGHT_RATIO = 1.6f
        const val AREA_FRACTION = 0.5f
        const val MAX_LANES = 12
        const val OPACITY = 217 // 85%
    }
}
