// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.mobile.R
import com.hongguo.mobile.data.ImageLoader
import com.hongguotv.core.Series

class SeriesAdapter(
    private val onClick: (Series) -> Unit,
) : RecyclerView.Adapter<SeriesAdapter.Holder>() {

    private val items = mutableListOf<Series>()

    fun addAll(newItems: List<Series>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Series? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_series, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val series = items[position]
        val target = holder.itemView.resources.displayMetrics.widthPixels / 3
        ImageLoader.load(series.cover, holder.poster, target)
        holder.title.text = series.title.ifBlank { "未命名" }
        val meta = series.badge.ifBlank { series.tags }
        holder.meta.text = meta
        holder.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        holder.badge.visibility = if (series.badge.isBlank()) View.GONE else View.VISIBLE
        holder.badge.text = series.badge
        holder.itemView.setOnClickListener { onClick(series) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: PosterView = view.findViewById(R.id.poster)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val badge: TextView = view.findViewById(R.id.badge)
    }
}
