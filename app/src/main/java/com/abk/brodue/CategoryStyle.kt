package com.abk.brodue

import android.content.Context
import androidx.core.content.ContextCompat

object CategoryStyle {
    data class Style(val color: Int, val container: Int, val iconRes: Int)

    fun forCategory(context: Context, category: String): Style {
        val (colorRes, containerRes, iconRes) = when (category) {
            "Online" -> Triple(R.color.cat_blue, R.color.cat_blue_soft, R.drawable.ic_custom_01)
            "Recharge" -> Triple(R.color.cat_amber, R.color.cat_amber_soft, R.drawable.ic_custom_02)
            "Cash" -> Triple(R.color.cat_green, R.color.cat_green_soft, R.drawable.ic_custom_03)
            "Item" -> Triple(R.color.cat_teal, R.color.cat_teal_soft, R.drawable.ic_custom_04)
            "Roundoff" -> Triple(R.color.cat_purple, R.color.cat_purple_soft, R.drawable.ic_custom_27)
            else -> {
                val custom = CustomCategoryStore.getAll(context).find { it.name == category }
                if (custom != null) {
                    val icon = CustomCategoryStore.iconResId(context, custom.iconResName)
                    val opts = listOf(
                        R.color.cat_blue to R.color.cat_blue_soft,
                        R.color.cat_amber to R.color.cat_amber_soft,
                        R.color.cat_green to R.color.cat_green_soft,
                        R.color.cat_teal to R.color.cat_teal_soft,
                        R.color.cat_purple to R.color.cat_purple_soft
                    )
                    val idx = Math.abs(category.hashCode()) % opts.size
                    val (c, s) = opts[idx]
                    Triple(c, s, icon)
                } else {
                    // Check if it's a deleted custom that still exists in a transaction
                    // Fallback to generic
                    Triple(R.color.cat_purple, R.color.cat_purple_soft, R.drawable.ic_custom_01)
                }
            }
        }
        return Style(
            ContextCompat.getColor(context, colorRes),
            ContextCompat.getColor(context, containerRes),
            iconRes
        )
    }
}
