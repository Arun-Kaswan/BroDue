package com.abk.brodue

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

abstract class BaseSheet : BottomSheetDialogFragment() {

    protected open val fullScreen: Boolean = false
    // When true, swipe-to-dismiss is locked while the keyboard is open
    // (back/scrim-tap still dismiss) - prevents accidental closes mid-typing
    protected open val lockSwipeWhileKeyboardOpen: Boolean = false

    private var backgroundOverlay = false
    private var originalSoftInput = 0
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, true)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.statusBarColor = Color.TRANSPARENT
            it.navigationBarColor = Color.TRANSPARENT
            it.setWindowAnimations(R.style.SheetSlideAnimation)
            focusListener?.let { l ->
                it.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(l)
            }
            focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused) exitBackground() else enterBackground()
            }
            it.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            it.setBackgroundResource(R.drawable.bg_bottom_sheet)
            // Always open fully - avoids needing a second swipe when the sheet
            // starts partially expanded and the first small swipe gets consumed
            val behavior = BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        if (fullScreen) {
            val d = dialog as? BottomSheetDialog ?: return
            d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            sheet?.let {
                it.layoutParams = it.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        if (lockSwipeWhileKeyboardOpen) {
            val decor = dialog?.window?.decorView
            val sheetView = sheet
            if (decor != null && sheetView != null) {
                keyboardLayoutListener?.let { decor.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                    try {
                        val frame = android.graphics.Rect()
                        decor.getWindowVisibleDisplayFrame(frame)
                        val keyboardOpen = decor.height - frame.bottom > decor.height * 0.15
                        BottomSheetBehavior.from(sheetView).isDraggable = !keyboardOpen
                    } catch (_: Exception) {}
                }
                decor.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
            }
        }
    }

    override fun onStop() {
        keyboardLayoutListener?.let { l ->
            try {
                dialog?.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(l)
            } catch (_: Exception) {}
        }
        keyboardLayoutListener = null
        super.onStop()
    }

    private fun enterBackground() {
        if (backgroundOverlay) return
        backgroundOverlay = true
        val w = dialog?.window ?: return
        originalSoftInput = w.attributes.softInputMode
        w.attributes = w.attributes.apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING }
    }

    private fun exitBackground() {
        if (!backgroundOverlay) return
        backgroundOverlay = false
        val w = dialog?.window ?: return
        w.attributes = w.attributes.apply { softInputMode = originalSoftInput }
    }
}