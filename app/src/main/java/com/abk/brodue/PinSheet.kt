package com.abk.brodue

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

// PIN drawer (bottom sheet) - Set a PIN -> Confirm PIN flow, or simple verify.
// Digits are revealed briefly then animatively masked to dots. The animation is
// applied only to the digit/dot character - the column boxes stay static.
object PinSheet {

    private const val MASK_DELAY_MS = 450L

    private class PinDigits(
        val context: Context,
        val containers: List<View>,
        val chars: List<TextView>,
        val input: EditText,
        private val onDigits: () -> Unit
    ) {
        fun refresh() {
            val digits = input.text.toString().trim()
            val shown = digits.length
            containers.forEachIndexed { i, box ->
                val char = chars[i]
                val filled = i < shown
                if (filled) {
                    if (box.tag == "revealed" || box.tag == "masked") {
                        // already displayed for this index - keep
                    } else {
                        box.tag = "revealed"
                        char.text = digits[i].toString()
                        // bounce-in reveal (blur-smudge: pop from small with overshoot)
                        char.alpha = 0f
                        char.scaleX = 0.5f
                        char.scaleY = 0.5f
                        char.animate()
                            .alpha(1f)
                            .scaleX(1.15f)
                            .scaleY(1.15f)
                            .setDuration(160)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
                            .withEndAction {
                                char.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            }
                            .start()
                        // typing the next letter masks the previous one immediately
                        containers.forEachIndexed { j, other ->
                            if (j != i && other.tag == "revealed") maskBox(other)
                        }
                        box.postDelayed({
                            if (box.tag == "revealed") maskBox(box)
                        }, MASK_DELAY_MS)
                    }
                } else {
                    box.tag = null
                    char.animate().cancel()
                    char.alpha = 1f
                    char.scaleX = 1f
                    char.scaleY = 1f
                    char.text = ""
                }
            }
            containers.forEachIndexed { i, box ->
                box.background = ContextCompat.getDrawable(
                    context,
                    if (i == shown) R.drawable.bg_field_focus else R.drawable.bg_field
                )
            }
            onDigits()
        }

        // Animate only the character (digit -> dot) with bounce + blur-smudge;
        // box stays static
        private fun maskBox(box: View) {
            if (box.tag != "revealed") return
            box.tag = "masked"
            val char = chars[containers.indexOf(box)]
            char.animate().cancel()
            // squeeze in (blur-smudge feel), then bounce out with overshoot
            char.animate()
                .alpha(0.2f)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .setDuration(90)
                .withEndAction {
                    char.text = "\u2022"
                    char.scaleX = 0.4f
                    char.scaleY = 0.4f
                    char.alpha = 1f
                    char.animate()
                        .scaleX(1.2f)
                        .scaleY(1.2f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                        .withEndAction {
                            char.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }
                        .start()
                }
                .start()
        }
    }

    private fun setupPinView(
        view: View,
        context: Context,
        input: EditText,
        containers: List<View>,
        chars: List<TextView>,
        onDigits: () -> Unit
    ): PinDigits {
        val engine = PinDigits(context, containers, chars, input, onDigits)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = engine.refresh()
        })
        containers.forEach { box ->
            box.setOnClickListener {
                input.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        input.requestFocus()
        engine.refresh()
        return engine
    }

    fun showSetup(context: Context, onSet: (String) -> Unit, onCancel: () -> Unit = {}) {
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_pin, null)
        sheet.setContentView(view)
        sheet.setCancelable(true)
        sheet.setOnCancelListener { onCancel() }

        val tvTitle = view.findViewById<TextView>(R.id.pinSheetTitle)
        val tvError = view.findViewById<TextView>(R.id.pinSheetError)
        val input = view.findViewById<EditText>(R.id.pinSheetInput)
        val btnAction = view.findViewById<View>(R.id.btnPinAction) as com.google.android.material.button.MaterialButton
        val containers = (0..5).map { view.findViewById<View>(R.id.pinBox0 + it) }
        val chars = (0..5).map { view.findViewById<TextView>(R.id.char0 + it) }
        var firstPin: String? = null

        setupPinView(view, context, input, containers, chars) {
            val digits = input.text.toString().trim()
            val confirming = firstPin != null
            // Finish stays greyed unless both PINs match (silently)
            val enabled = if (confirming) digits.length == 6 && digits == firstPin else digits.length == 6
            btnAction.isEnabled = enabled
            tvError.visibility = View.GONE
        }

        btnAction.setOnClickListener {
            val digits = input.text.toString().trim()
            if (digits.length != 6) return@setOnClickListener
            if (firstPin == null) {
                firstPin = digits
                tvTitle.text = "Confirm PIN"
                btnAction.text = "Finish"
                input.setText("")
                tvError.visibility = View.GONE
            } else {
                sheet.dismiss()
                onSet(firstPin!!)
            }
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        sheet.show()
        sheet.window?.let { it.decorView.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 150) }
    }

    // Single step - for restoring a PIN-protected backup
    fun showVerify(context: Context, title: String, hint: String, onConfirmed: (String) -> Unit, onCancel: () -> Unit = {}) {
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_pin, null)
        sheet.setContentView(view)
        sheet.setCancelable(true)
        sheet.setOnCancelListener { onCancel() }

        val tvTitle = view.findViewById<TextView>(R.id.pinSheetTitle)
        val tvError = view.findViewById<TextView>(R.id.pinSheetError)
        val input = view.findViewById<EditText>(R.id.pinSheetInput)
        val btnAction = view.findViewById<View>(R.id.btnPinAction) as com.google.android.material.button.MaterialButton
        val containers = (0..5).map { view.findViewById<View>(R.id.pinBox0 + it) }
        val chars = (0..5).map { view.findViewById<TextView>(R.id.char0 + it) }
        tvTitle.text = title
        btnAction.text = "Confirm"

        setupPinView(view, context, input, containers, chars) {
            val digits = input.text.toString().trim()
            btnAction.isEnabled = digits.length == 6
            tvError.visibility = View.GONE
        }

        btnAction.setOnClickListener {
            val digits = input.text.toString().trim()
            if (digits.length != 6) return@setOnClickListener
            sheet.dismiss()
            onConfirmed(digits)
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        sheet.show()
        sheet.window?.let { it.decorView.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 150) }
    }
}
