// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPolicyTest {

    @Test
    fun startsAtThreeSeconds() {
        assertEquals(3_000L, PrefetchPolicy().thresholdMs)
        assertTrue(PrefetchPolicy().shouldPrefetch(remainingMs = 2_999))
        assertFalse(PrefetchPolicy().shouldPrefetch(remainingMs = 3_001))
    }

    @Test
    fun fastResolveShrinksButDoesNotCollapseTheWindow() {
        val policy = PrefetchPolicy()
        repeat(5) { policy.recordResolve(200) }
        assertEquals(PrefetchPolicy.MIN_THRESHOLD_MS, policy.thresholdMs)
    }

    @Test
    fun slowResolveGrowsTheWindowWithinBounds() {
        val policy = PrefetchPolicy()
        repeat(5) { policy.recordResolve(9_000) }
        assertTrue(policy.thresholdMs in 1_500L..12_000L)
        assertTrue(policy.thresholdMs > 3_000L)
        assertEquals(PrefetchPolicy.MAX_THRESHOLD_MS, policy.thresholdMs)
    }

    @Test
    fun emaSmoothsASingleOutlier() {
        val policy = PrefetchPolicy()
        repeat(6) { policy.recordResolve(600) }
        val steady = policy.thresholdMs
        policy.recordResolve(8_000)
        assertTrue(policy.thresholdMs > steady)
        assertTrue(policy.thresholdMs < 8_000)
    }

    @Test
    fun playbackSpeedConvertsWallClockToMediaTime() {
        val policy = PrefetchPolicy()
        assertEquals(3_000L, policy.mediaWindowMs(1f))
        assertEquals(6_000L, policy.mediaWindowMs(2f))
        assertEquals(1_500L, policy.mediaWindowMs(0.5f))
        // 2 倍速下剩 5 秒片长只相当于 2.5 秒真实时间，应当已经进入窗口。
        assertTrue(policy.shouldPrefetch(remainingMs = 5_000, speed = 2f))
    }

    @Test
    fun cooldownAndAttemptsBlockRetries() {
        val policy = PrefetchPolicy()
        assertFalse(policy.shouldPrefetch(remainingMs = 2_000, coolingDown = true))
        assertFalse(policy.shouldPrefetch(remainingMs = 2_000, attempts = PrefetchPolicy.MAX_ATTEMPTS))
        assertTrue(policy.shouldPrefetch(remainingMs = 2_000, attempts = PrefetchPolicy.MAX_ATTEMPTS - 1))
    }

    @Test
    fun seekToTheTailOpensTheWindowImmediately() {
        val policy = PrefetchPolicy()
        // 拖到片尾只剩 800ms：远小于 3 秒阈值，应当立刻预解析。
        assertTrue(policy.shouldPrefetch(remainingMs = 800))
        // 往回拖到还剩 60 秒：不应触发。
        assertFalse(policy.shouldPrefetch(remainingMs = 60_000))
    }

    @Test
    fun endedEpisodeDoesNotTriggerPrefetch() {
        assertFalse(PrefetchPolicy().shouldPrefetch(remainingMs = 0))
        assertFalse(PrefetchPolicy().shouldPrefetch(remainingMs = -1_000))
    }
}
