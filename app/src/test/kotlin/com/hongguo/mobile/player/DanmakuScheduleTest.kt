// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuScheduleTest {

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(DanmakuSchedule.assign(emptyList(), 4, 8_000).isEmpty())
        assertTrue(DanmakuSchedule.assign(listOf(DanmakuItem("1", "hi", 0)), 0, 8_000).isEmpty())
    }

    @Test
    fun singleCommentStartsOnTimeInFirstLane() {
        val rows = DanmakuSchedule.assign(listOf(DanmakuItem("1", "好看", 1_000)), 3, 8_000)
        assertEquals(1, rows.size)
        assertEquals(1_000L, rows[0].startMs)
        assertEquals(9_000L, rows[0].endMs)
        assertEquals(0, rows[0].lane)
    }

    @Test
    fun simultaneousCommentsUseDifferentLanes() {
        val items = (1..3).map { DanmakuItem("$it", "第 $it 条", 2_000) }
        val rows = DanmakuSchedule.assign(items, 3, 8_000)
        assertEquals(3, rows.map { it.lane }.toSet().size)
        assertTrue(rows.all { it.startMs == 2_000L })
    }

    @Test
    fun crowdingIsDelayedButNeverBeyondThreeSeconds() {
        val items = (1..12).map { DanmakuItem("$it", "弹幕 $it", 1_000) }
        val rows = DanmakuSchedule.assign(items, 2, 8_000)
        assertEquals(12, rows.size)
        assertTrue(rows.all { it.startMs <= 4_000L })
        assertTrue(rows.any { it.startMs > 1_000L })
    }

    @Test
    fun repeatedTextWithinFiveSecondsIsMerged() {
        val items = listOf(
            DanmakuItem("a", "哈哈哈哈", 1_000),
            DanmakuItem("b", "哈哈哈哈", 3_000),
            DanmakuItem("c", "哈哈哈哈", 9_000),
        )
        val rows = DanmakuSchedule.assign(items, 3, 8_000)
        assertEquals(2, rows.size)
        assertEquals("哈哈哈哈 ×2", rows[0].text)
        assertEquals("哈哈哈哈", rows[1].text)
    }

    @Test
    fun duplicateIdsAreDropped() {
        val items = listOf(
            DanmakuItem("same", "重复", 1_000),
            DanmakuItem("same", "重复", 1_100),
        )
        assertEquals(1, DanmakuSchedule.assign(items, 3, 8_000).size)
    }
}
