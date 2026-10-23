package com.nutrilens.ai.util

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.nutrilens.ai.R

/**
 * Adds a "label ..... value(s)" row (with a divider above it unless it is the first row) to a
 * vertical LinearLayout. Extra [values] become right-aligned columns; indexes in [highlight] are
 * drawn in [highlightColor] (green "better" by default).
 */
fun LinearLayout.addRow(
    label: String,
    vararg values: String,
    highlight: Set<Int> = emptySet(),
    header: Boolean = false,
    highlightColor: Int = R.color.brand_green
) {
    if (childCount > 0) {
        addView(View(context).apply {
            setBackgroundColor(MaterialColors.getColor(this@addRow, com.google.android.material.R.attr.colorOutline))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = context.dp(10)
        setPadding(0, pad, 0, pad)
    }
    row.addView(
        TextView(context, null, 0, if (header) R.style.NL_Caption else R.style.NL_Body).apply { text = label },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
    )
    values.forEachIndexed { i, value ->
        row.addView(
            TextView(context, null, 0, if (header) R.style.NL_Caption else R.style.NL_Value).apply {
                text = value
                gravity = Gravity.END
                if (i in highlight) setTextColor(ContextCompat.getColor(context, highlightColor))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
    }
    addView(row)
}
