// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPagerTest {

    private fun comment(id: String) = CommentItem(id, "用户$id", "内容$id", 0, 0)

    @Test
    fun loadsPagesAndDropsDuplicates() {
        val pages = listOf(
            CommentPage(listOf(comment("1"), comment("2")), 4, "c2", true),
            CommentPage(listOf(comment("2"), comment("3")), 4, "c3", true),
        )
        var calls = 0
        val pager = CommentPager { pages[calls++] }

        assertEquals(listOf("1", "2"), pager.next().map { it.id })
        assertEquals(listOf("3"), pager.next().map { it.id })
        assertEquals(4L, pager.total)
        assertEquals(2, calls)
    }

    @Test
    fun stopsWhenServerReportsNoMore() {
        val pager = CommentPager {
            CommentPage(listOf(comment("1")), 1, "c1", false)
        }
        assertEquals(1, pager.next().size)
        assertFalse(pager.hasMore)
        assertTrue(pager.next().isEmpty())
    }

    @Test
    fun stopsWhenCursorIsBlankEvenIfHasMore() {
        val pager = CommentPager {
            CommentPage(listOf(comment("1")), 1, "", true)
        }
        pager.next()
        assertFalse(pager.hasMore)
        assertTrue(pager.next().isEmpty())
    }

    @Test
    fun resetRestartsFromFirstPage() {
        var cursorSeen = ""
        val pager = CommentPager { cursor ->
            cursorSeen = cursor
            CommentPage(listOf(comment(cursor.ifBlank { "first" })), 1, "next", true)
        }
        pager.next()
        pager.reset()
        pager.next()
        assertEquals("", cursorSeen)
        assertTrue(pager.hasMore)
    }
}
