// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

/** 一条时间轴弹幕。 */
data class DanmakuItem(val id: String, val text: String, val timeMs: Long)

/** 排好轨道的弹幕：在 [startMs] 从右向左滚动，直到 [endMs]。 */
data class ScheduledDanmaku(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val lane: Int,
)

/**
 * 弹幕轨道调度（纯逻辑，便于单测）：
 * - 相同 id 去重，相同文本 5 秒内合并成「文字 ×N」；
 * - 同轨道两条之间至少留 400ms；
 * - 拥挤时最多顺延 3 秒，超过就允许重叠，不能把弹幕排到片尾。
 */
object DanmakuSchedule {

    const val MIN_GAP_MS = 400L
    const val MAX_DELAY_MS = 3_000L
    const val MERGE_WINDOW_MS = 5_000L

    fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Long,
    ): List<ScheduledDanmaku> {
        if (items.isEmpty() || lanes <= 0 || travelMs <= 0) return emptyList()

        val unique = items
            .distinctBy { it.id.ifBlank { "${it.timeMs}:${it.text}" } }
            .sortedBy { it.timeMs }

        // 同文本短时间重复：合并计数，保留最早出现时间。
        val prepared = mutableListOf<Triple<String, Long, Int>>()
        val lastIndex = mutableMapOf<String, Int>()
        for (item in unique) {
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val index = lastIndex[text]
            val previous = index?.let { prepared[it] }
            if (previous != null && item.timeMs - previous.second <= MERGE_WINDOW_MS) {
                prepared[index] = previous.copy(third = previous.third + 1)
            } else {
                lastIndex[text] = prepared.size
                prepared += Triple(text, item.timeMs, 1)
            }
        }

        val laneFreeAt = LongArray(lanes) { Long.MIN_VALUE }
        val output = ArrayList<ScheduledDanmaku>(prepared.size)
        for ((text, at, count) in prepared) {
            var bestLane = 0
            var earliest = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val candidate = maxOf(at, laneFreeAt[lane] + MIN_GAP_MS)
                if (candidate < earliest) {
                    earliest = candidate
                    bestLane = lane
                }
            }
            val start = minOf(earliest, at + MAX_DELAY_MS).coerceAtLeast(at)
            laneFreeAt[bestLane] = start + travelMs / 3
            output += ScheduledDanmaku(
                text = if (count > 1) "$text ×$count" else text,
                startMs = start,
                endMs = start + travelMs,
                lane = bestLane,
            )
        }
        return output
    }
}
