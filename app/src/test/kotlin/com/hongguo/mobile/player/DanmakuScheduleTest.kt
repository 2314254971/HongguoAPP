// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuScheduleTest {

    private val measure: (String) -> Float = { 100f }

    private fun assign(
        items: List<DanmakuItem>,
        lanes: Int = 3,
        width: Int = 1_000,
        measureText: (String) -> Float = measure,
    ) = DanmakuSchedule.assign(
        items = items,
        lanes = lanes,
        travelMs = 7_000,
        viewportWidthPx = width,
        gapPx = 10f,
        measureText = measureText,
    )

    @Test
    fun emptyOrInvalidInputProducesNothing() {
        assertTrue(assign(emptyList()).isEmpty())
        assertTrue(assign(listOf(DanmakuItem("1", "hi", 0)), lanes = 0).isEmpty())
        assertTrue(assign(listOf(DanmakuItem("1", "hi", 0)), width = 0).isEmpty())
    }

    @Test
    fun singleCommentStartsOnTimeInFirstLane() {
        val rows = assign(listOf(DanmakuItem("1", "好看", 1_000)))
        assertEquals(1, rows.size)
        assertEquals(1_000L, rows[0].startMs)
        assertEquals(8_000L, rows[0].endMs)
        assertEquals(0, rows[0].lane)
        assertEquals(100f, rows[0].widthPx, 0.01f)
    }

    @Test
    fun simultaneousCommentsUseDifferentLanes() {
        val items = (1..3).map { DanmakuItem("$it", "第 $it 条", 2_000) }
        val rows = assign(items)
        assertEquals(3, rows.map { it.lane }.toSet().size)
        assertTrue(rows.all { it.startMs == 2_000L })
    }

    @Test
    fun crowdingDropsExtrasInsteadOfOverlapping() {
        val items = (1..12).map { DanmakuItem("$it", "弹幕 $it", 1_000) }
        val rows = assign(items, lanes = 2)
        assertTrue(rows.size in 1..11)
        assertTrue(rows.all { it.startMs <= 4_000L })
    }

    @Test
    fun longCommentDelaysTheNextShortOne() {
        val items = listOf(
            DanmakuItem("a", "长弹幕占满整屏", 0),
            DanmakuItem("b", "短", 0),
        )
        val rows = DanmakuSchedule.assign(
            items = items,
            lanes = 1,
            travelMs = 7_000,
            viewportWidthPx = 1_000,
            gapPx = 10f,
            measureText = { text -> if (text.startsWith("长")) 600f else 100f },
        )
        assertEquals(2, rows.size)
        // ceil(7000 * (600 + 10) / (1000 + 600)) = 2669ms
        assertTrue(rows[1].startMs - rows[0].startMs >= 2_600L)
    }

    @Test
    fun repeatedTextWithinFiveSecondsIsMerged() {
        val items = listOf(
            DanmakuItem("a", "哈哈哈哈", 1_000),
            DanmakuItem("b", "哈哈哈哈", 3_000),
            DanmakuItem("c", "哈哈哈哈", 9_000),
        )
        val rows = assign(items)
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
        assertEquals(1, assign(items).size)
    }
}
