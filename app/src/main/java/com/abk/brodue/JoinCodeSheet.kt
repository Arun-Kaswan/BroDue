package com.abk.brodue

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

// Join-code popup - same column styling as the PIN sheet (hidden input +
// display boxes, focus highlight, bounce reveal), but the code stays visible.
class JoinCodeSheet : BasePopup() {

    private lateinit var input: EditText
    private lateinit var boxes: List<View>
    private lateinit var chars: List<TextView>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_join_code, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        input = view.findViewById(R.id.joinCodeInput)
        boxes = (0..5).map { view.findViewById<View>(R.id.joinBox0 + it) }
        chars = (0..5).map { view.findViewById<TextView>(R.id.joinChar0 + it) }
        // Paste sanitizer: letters + digits only, uppercased, first 6 kept
        val codeFilter = InputFilter { src, start, end, dest, dstart, dend ->
            val filtered = src.subSequence(start, end).filter { it.isLetterOrDigit() }
            val keep = 6 - (dest.length - (dend - dstart))
            filtered.take(keep.coerceAtLeast(0)).toString()
                .uppercase(java.util.Locale.ROOT)
        }
        input.filters = arrayOf(codeFilter, InputFilter.LengthFilter(6))

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable) = refresh()
        })
        boxes.forEach { box ->
            box.setOnClickListener {
                input.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        parentFragmentManager.setFragmentResultListener(JoinPreviewSheet.REQ_JOINED, this) { _, _ ->
            dismiss()
        }

        view.findViewById<View>(R.id.btnCodeNext).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            goNext()
        }

        // Deep-link / prefill: fill the code and go straight to preview
        requireArguments().getString(ARG_PREFILL).orEmpty().takeIf { it.length == 6 }?.let { prefill ->
            input.setText(prefill)
            view.post { goNext() }
        }

        input.requestFocus()
        refresh()
        dialog?.window?.decorView?.postDelayed({
            if (!isAdded) return@postDelayed
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun refresh() {
        val code = input.text.toString().trim()
        val shown = code.length
        boxes.forEachIndexed { i, box ->
            val char = chars[i]
            if (i < shown) {
                if (box.tag == null) {
                    // first display for this index - bounce-in reveal, stays visible
                    box.tag = "shown"
                    char.text = code[i].toString()
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
                } else if (char.text.toString() != code[i].toString()) {
                    char.text = code[i].toString()
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
        // focus highlight on the next empty box (PIN-sheet style)
        boxes.forEachIndexed { i, box ->
            box.background = ContextCompat.getDrawable(
                requireContext(),
                if (i == shown) R.drawable.bg_field_focus else R.drawable.bg_field
            )
        }
    }

    private fun goNext() {
        val code = input.text.toString().trim()
        if (code.length != 6) {
            Toast.makeText(requireContext(), R.string.enter_6_code, Toast.LENGTH_SHORT).show()
            return
        }
        JoinPreviewSheet.newInstance(code).show(parentFragmentManager, JoinPreviewSheet.TAG)
    }

    companion object {
        private const val ARG_PREFILL = "prefill"
        const val TAG = "JoinCodeSheet"

        fun newInstance(prefill: String = ""): JoinCodeSheet = JoinCodeSheet().apply {
            arguments = Bundle().apply { putString(ARG_PREFILL, prefill) }
        }
    }
}
