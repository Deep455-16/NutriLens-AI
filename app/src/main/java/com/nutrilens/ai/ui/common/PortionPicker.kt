package com.nutrilens.ai.ui.common

import com.nutrilens.ai.util.Haptic
import com.nutrilens.ai.util.haptic
import android.content.Context
import android.text.InputType
import android.widget.Filter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nutrilens.ai.R
import com.nutrilens.ai.databinding.LayoutPortionPickerBinding
import com.nutrilens.ai.domain.PortionChoice
import com.nutrilens.ai.util.dp
import kotlin.math.roundToInt

/**
 * A dropdown adapter that always lists every item. AutoCompleteTextView normally filters its
 * list by the text already in the field, which would show only the current portion.
 */
private class AllItemsAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply { values = items; count = items.size }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
    }
}

/** Wires layout_portion_picker.xml to a [PortionChoice] held by a ViewModel. */
class PortionPicker(
    private val b: LayoutPortionPickerBinding,
    onPortion: (Int) -> Unit,
    onStep: (Boolean) -> Unit,
    private val onGrams: (Float) -> Unit
) {
    private var shown: List<String> = emptyList()

    init {
        b.portionMenu.setOnItemClickListener { _, _, position, _ -> onPortion(position) }
        b.btnLess.setOnClickListener { it.haptic(Haptic.TICK); onStep(false) }
        b.btnMore.setOnClickListener { it.haptic(Haptic.TICK); onStep(true) }
        b.btnGrams.setOnClickListener { askGrams() }
    }

    private var current: PortionChoice? = null

    fun bind(choice: PortionChoice) {
        current = choice
        val ctx = b.root.context
        val names = choice.portions.map { ctx.getString(R.string.portion_option_fmt, it.description, it.grams.roundToInt()) }
        if (names != shown) {
            shown = names
            b.portionMenu.setAdapter(AllItemsAdapter(b.root.context, names))
        }
        b.portionMenu.setText(names.getOrNull(choice.selected) ?: ctx.getString(R.string.one_serving), false)
        val custom = choice.customGrams != null
        b.tvQuantity.text = if (custom) "–" else ctx.getString(R.string.quantity_fmt,
            (if (choice.quantity % 1f == 0f) choice.quantity.toInt() else choice.quantity).toString())
        b.btnLess.isEnabled = custom || choice.quantity > PortionChoice.MIN_QTY
        b.btnMore.isEnabled = custom || choice.quantity < PortionChoice.MAX_QTY
        b.tvGrams.text = if (custom) ctx.getString(R.string.exact_amount_fmt, choice.grams.roundToInt())
            else ctx.getString(R.string.portion_equals_fmt, choice.text, choice.grams.roundToInt())
    }

    private fun askGrams() {
        val ctx = b.root.context
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = ctx.getString(R.string.grams)
            current?.let { setText(it.grams.roundToInt().toString()) }
            setSelection(text.length)
        }
        val box = FrameLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.how_many_grams)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                input.text.toString().toFloatOrNull()?.takeIf { it > 0f }?.let(onGrams)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
