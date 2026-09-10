package com.abk.brodue

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

object AvatarColors {

    private var letters: IntArray? = null
    private var bgs: IntArray? = null

    fun style(context: Context, avatar: TextView, name: String) {
        if (letters == null) {
            letters = context.resources.getIntArray(R.array.avatar_letter_colors)
            bgs = context.resources.getIntArray(R.array.avatar_bg_colors)
        }
        val index = (name.hashCode() and Int.MAX_VALUE) % letters!!.size
        avatar.setTextColor(letters!![index])
        (avatar.background as? GradientDrawable)?.setTint(bgs!![index])
    }
}