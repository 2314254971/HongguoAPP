// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.R
import com.hongguo.mobile.data.HistoryEntry
import com.hongguo.mobile.data.ImageLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

    private val items = mutableListOf<HistoryEntry>()

    fun addAll(rows: List<HistoryEntry>) {
        if (rows.isEmpty()) return
        val start = items.size
        items.addAll(rows)
        notifyItemRangeInserted(start, rows.size)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun itemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        ImageLoader.load(row.series.cover, holder.cover, holder.itemView.context.dp(56))
        holder.title.text = row.series.title.ifBlank { "未命名" }
        holder.episode.text = if (row.episodeIndex >= 0) "第 ${row.episodeIndex + 1} 集" else "短剧"
        holder.progress.text = progressText(row)
        holder.time.text = relativeTime(row.updatedAt)
        holder.itemView.setOnClickListener { onClick(row) }
    }

    private fun progressText(row: HistoryEntry): String {
        if (row.duration <= 0) return ""
        val percent = (row.position * 100 / row.duration).coerceIn(0, 100)
        return "看到 ${formatTime(row.position)} / ${formatTime(row.duration)} · $percent%"
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun relativeTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000 -> "${diff / 86_400_000} 天前"
            else -> SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(timestamp))
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: PosterView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val episode: TextView = view.findViewById(R.id.episode)
        val progress: TextView = view.findViewById(R.id.progress)
        val time: TextView = view.findViewById(R.id.time)
    }
}
