package com.abk.brodue

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.fragment.app.DialogFragment

// Centered theme-style popup (vs BaseSheet's bottom drawer).
// Layout root should use @drawable/bg_popup with side margins handled here.
abstract class BasePopup : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.setWindowAnimations(R.style.PopupAnimation)
            it.setGravity(Gravity.CENTER)
            it.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Side margins so the popup floats centered with breathing room
            val d = (20 * resources.displayMetrics.density).toInt()
            it.decorView.setPadding(d, 0, d, 0)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
}
