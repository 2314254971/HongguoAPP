// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.ui

import android.content.Context
import android.view.View
import android.widget.Toast

fun Context.dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density).toInt()

fun View.show() {
    visibility = View.VISIBLE
}

fun View.hide() {
    visibility = View.GONE
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
