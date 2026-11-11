// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

/**
 * 下一集预解析的时机策略。
 *
 * - 初始阈值 3 秒（按真实播放时间算）；
 * - 每次成功解析（预解析或阻塞解析）把耗时喂进来，EMA 平滑后按 1.5 倍 + 0.5 秒留余量；
 * - 阈值限制在 1.5–12 秒：网络再好也不早于 1.5 秒，抖动再大也不超过 12 秒；
 * - 倍速播放时把真实时间换算成媒体时间，2 倍速下 3 秒窗口等于 6 秒片长；
 * - 冷却与重试次数由调用方持有，策略只做判定。
 */
class PrefetchPolicy(initialThresholdMs: Long = 3_000L) {

    var thresholdMs: Long = initialThresholdMs
        private set

    /** 平滑后的解析耗时；0 表示还没有样本。 */
    var estimateMs: Double = 0.0
        private set

    fun recordResolve(elapsedMs: Long) {
        if (elapsedMs <= 0) return
        estimateMs =
            if (estimateMs <= 0.0) elapsedMs.toDouble()
            else estimateMs * EMA_KEEP + elapsedMs * (1 - EMA_KEEP)
        thresholdMs = (estimateMs * SAFETY_FACTOR + MARGIN_MS).toLong()
            .coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
    }

    /** 真实时间窗口换算成媒体时间。 */
    fun mediaWindowMs(speed: Float): Long =
        (thresholdMs * speed.coerceIn(MIN_SPEED, MAX_SPEED)).toLong().coerceAtLeast(1L)

    fun shouldPrefetch(
        remainingMs: Long,
        speed: Float = 1f,
        coolingDown: Boolean = false,
        attempts: Int = 0,
    ): Boolean =
        remainingMs > 0 &&
            !coolingDown &&
            attempts < MAX_ATTEMPTS &&
            remainingMs <= mediaWindowMs(speed)

    companion object {
        const val MIN_THRESHOLD_MS = 1_500L
        const val MAX_THRESHOLD_MS = 12_000L
        const val MAX_ATTEMPTS = 3
        private const val SAFETY_FACTOR = 1.5
        private const val MARGIN_MS = 500
        private const val EMA_KEEP = 0.6
        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 4f
    }
}
