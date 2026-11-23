// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import kotlin.math.ceil

/** 一条时间轴弹幕。 */
data class DanmakuItem(val id: String, val text: String, val timeMs: Long)

/** 排好轨道的弹幕：在 [startMs] 从右向左滚动到 [endMs]，文字宽度为 [widthPx]。 */
data class ScheduledDanmaku(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val lane: Int,
    val widthPx: Float,
)

/**
 * 弹幕轨道调度（纯逻辑，便于单测）：
 * - 同 id 去重，同文本 5 秒内合并成「文字 ×N」；
 * - 同轨道按**文字宽度**留安全间距，避免长短弹幕互相追尾；
 * - 拥挤时最多顺延 [MAX_DELAY_MS]，排不下就放弃，不叠字。
 */
object DanmakuSchedule {

    const val MIN_GAP_MS = 400L
    const val MAX_DELAY_MS = 3_000L
    const val MERGE_WINDOW_MS = 5_000L

    /**
     * @param viewportWidthPx 弹幕层宽度（横竖屏不同，旋转后重新调用）
     * @param gapPx 前后两条弹幕之间的最小像素间隔
     * @param measureText 与渲染一致的文字测量函数
     */
    fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Long,
        viewportWidthPx: Int,
        gapPx: Float,
        maxDelayMs: Long = MAX_DELAY_MS,
        measureText: (String) -> Float,
    ): List<ScheduledDanmaku> {
        if (items.isEmpty() || lanes <= 0 || travelMs <= 0 || viewportWidthPx <= 0) {
            return emptyList()
        }

        val unique = items
            .distinctBy { it.id.ifBlank { "${it.timeMs}:${it.text}" } }
            .sortedWith(compareBy<DanmakuItem> { it.timeMs }.thenBy { it.id })

        // 同文本短时间重复：合并计数，保留最早出现时间。
        val prepared = mutableListOf<Triple<String, Long, Int>>()
        val latestIndex = mutableMapOf<String, Int>()
        for (item in unique) {
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val index = latestIndex[text]
            val previous = index?.let { prepared[it] }
            if (previous != null && item.timeMs - previous.second <= MERGE_WINDOW_MS) {
                prepared[index!!] = previous.copy(third = previous.third + 1)
            } else {
                latestIndex[text] = prepared.size
                prepared += Triple(text, item.timeMs, 1)
            }
        }

        val lastStart = LongArray(lanes) { Long.MIN_VALUE }
        val laneClearance = LongArray(lanes)
        val output = ArrayList<ScheduledDanmaku>(prepared.size)

        for ((text, at, count) in prepared) {
            val display = if (count > 1) "$text ×$count" else text
            val widthPx = measureText(display)
            // 后一条要等前一条尾部离开入口、且永远追不上前一条，才能进同一轨。
            val clearance = ceil(
                travelMs.toDouble() * (widthPx + gapPx) / (viewportWidthPx + widthPx),
            ).toLong().coerceAtLeast(MIN_GAP_MS)

            var bestLane = 0
            var bestStart = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val previousStart = lastStart[lane]
                val earliest = if (previousStart == Long.MIN_VALUE) {
                    at
                } else {
                    maxOf(at, previousStart + maxOf(laneClearance[lane], clearance))
                }
                if (earliest < bestStart) {
                    bestStart = earliest
                    bestLane = lane
                }
            }

            if (bestStart > at + maxDelayMs) continue

            output += ScheduledDanmaku(
                text = display,
                startMs = bestStart,
                endMs = bestStart + travelMs,
                lane = bestLane,
                widthPx = widthPx,
            )
            lastStart[bestLane] = bestStart
            laneClearance[bestLane] = clearance
        }

        output.sortBy { it.startMs }
        return output
    }
}
