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
import android.view.View
import kotlin.math.abs

/**
 * 时间轴弹幕层：位置完全由播放进度驱动，暂停即静止，拖动后自动重新对齐。
 * 只负责绘制，排轨交给 [DanmakuSchedule]。
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

    var danmakuOn: Boolean = true
        set(value) {
            field = value
            if (!value) active.clear()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val laneHeight = 26f * density

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val outline = TextPaint(fill).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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
            // 用户拖动过进度：清空当前屏，按新位置重新对齐。
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
        val lanes = if (height > 0) (height / laneHeight).toInt().coerceAtLeast(1) else 3
        scheduled = DanmakuSchedule.assign(items, lanes, TRAVEL_MS)
        cursor = 0
        active.clear()
        invalidate()
    }

    private fun currentMs(): Long =
        positionMs + if (playing) SystemClock.elapsedRealtime() - anchorRealtime else 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuOn || active.isEmpty()) return
        val now = currentMs()
        for (row in active) {
            val progress = ((now - row.startMs).toFloat() / TRAVEL_MS).coerceIn(0f, 1f)
            val textWidth = fill.measureText(row.text)
            val x = width - progress * (width + textWidth)
            val y = (row.lane + 1) * laneHeight
            canvas.drawText(row.text, x, y, outline)
            canvas.drawText(row.text, x, y, fill)
        }
        if (playing) postInvalidateOnAnimation()
    }

    private companion object {
        const val TRAVEL_MS = 8_000L
    }
}
