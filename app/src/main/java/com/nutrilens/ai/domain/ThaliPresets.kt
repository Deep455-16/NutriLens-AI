package com.nutrilens.ai.domain

import androidx.annotation.StringRes
import com.nutrilens.ai.R

/** One dish in a thali: a label from the nutrition dataset and how many of its standard servings. */
data class ThaliDish(val label: String, val servings: Float = 1f)

data class ThaliPreset(@StringRes val name: Int, val dishes: List<ThaliDish>)

/** Common thali combinations, logged together as one meal. Amounts are the dataset's own servings. */
object ThaliPresets {
    val ALL = listOf(
        ThaliPreset(R.string.thali_north, listOf(
            ThaliDish("chapati", 2f), ThaliDish("dal_makhani"), ThaliDish("shahi_paneer"), ThaliDish("jeera_rice")
        )),
        ThaliPreset(R.string.thali_protein, listOf(
            ThaliDish("chapati", 2f), ThaliDish("palak_paneer"), ThaliDish("chana_masala")
        )),
        ThaliPreset(R.string.thali_south, listOf(
            ThaliDish("jeera_rice", 1.5f), ThaliDish("sambar"), ThaliDish("rasam")
        )),
        ThaliPreset(R.string.thali_home, listOf(
            ThaliDish("chapati", 2f), ThaliDish("dal_tadka"), ThaliDish("bhindi_masala")
        ))
    )
}
