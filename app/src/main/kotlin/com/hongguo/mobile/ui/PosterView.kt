// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

/** 圆角海报：高度为 wrap_content 时按 3:4 计算，避免不同海报比例把网格撑乱。 */
class PosterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(0xFF16181F.toInt())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (width > 0 && heightMode != MeasureSpec.EXACTLY) {
            val height = (width * 4f / 3f).toInt()
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
