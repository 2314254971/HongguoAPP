// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.data

import com.hongguotv.core.AppCommentPages

data class CommentItem(
    val id: String,
    val author: String,
    val content: String,
    val created: Long,
    val likes: Long,
)

data class CommentPage(
    val items: List<CommentItem>,
    val total: Long,
    val nextCursor: String,
    val hasMore: Boolean,
)

/** 评论游标分页：累计去重，服务端说没有下一页或游标为空就停。 */
class CommentPager(private val load: (cursor: String) -> CommentPage) {

    private var cursor = ""
    private val seen = mutableSetOf<String>()

    var total = 0L
        private set
    var hasMore = true
        private set

    fun reset() {
        cursor = ""
        seen.clear()
        total = 0
        hasMore = true
    }

    fun next(): List<CommentItem> {
        if (!hasMore) return emptyList()
        val page = load(cursor)
        total = maxOf(total, page.total)
        val fresh = page.items.filter { seen.add(it.id) }
        cursor = page.nextCursor
        hasMore = page.hasMore && cursor.isNotBlank()
        return fresh
    }
}

/** 把 core 的评论列表接口转成 [CommentPage]。读取是匿名的，不需要登录。 */
object CommentPages {

    private val pages by lazy { AppCommentPages() }

    fun load(seriesId: String, episodeId: String, cursor: String): CommentPage {
        val raw = pages.read(seriesId, episodeId, cursor)
        val rows = raw.optJSONArray("items")
        val output = mutableListOf<CommentItem>()
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val content = row.optString("content").trim()
                if (content.isEmpty()) continue
                output += CommentItem(
                    id = row.optString("id"),
                    author = row.optString("author"),
                    content = content,
                    created = row.optLong("created"),
                    likes = row.optLong("likes"),
                )
            }
        }
        return CommentPage(
            items = output,
            total = raw.optLong("total"),
            nextCursor = raw.optString("nextCursor"),
            hasMore = raw.optBoolean("hasMore"),
        )
    }
}
