package com.abk.brodue

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat

// 6-digit PIN entry dialog (boxes per digit + hidden numeric input)
object PinEntryDialog {

    fun show(
        context: Context,
        title: String,
        hint: String,
        onConfirmed: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_pin_entry)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.setOnCancelListener { onCancel() }
        dialog.findViewById<TextView>(R.id.pinTitle).text = title
        dialog.findViewById<TextView>(R.id.pinHint).text = hint

        val boxes = (0..5).map { dialog.findViewById<TextView>(R.id.pinBox0 + it) }
        val input = dialog.findViewById<EditText>(R.id.pinInput)
        val tvError = dialog.findViewById<TextView>(R.id.pinError)
        val btnNext = dialog.findViewById<View>(R.id.btnPinNext)

        fun refresh(filled: String) {
            val shown = filled.length
            for (i in boxes.indices) {
                boxes[i].text = if (i < shown) filled[i].toString() else ""
                boxes[i].background = ContextCompat.getDrawable(
                    context,
                    if (i == shown) R.drawable.bg_field_focus else R.drawable.bg_field
                )
            }
            btnNext.isEnabled = shown == 6
        }
        refresh("")

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tvError.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable) {
                refresh(s.toString().trim())
            }
        })

        boxes.forEach { box ->
            box.setOnClickListener {
                input.requestFocus()
                input.showSoftInputOnFocus = true
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        input.requestFocus()

        btnNext.setOnClickListener {
            val pin = input.text.toString().trim()
            if (pin.length != 6) {
                tvError.text = "Please enter the 6-digit PIN"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirmed(pin)
        }
        dialog.show()
        dialog.window?.let {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            it.decorView.postDelayed({ imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 150)
        }
    }
}
