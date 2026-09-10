package com.abk.brodue

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

// App-themed confirm popup (bottom drawer). Reports back via FragmentResult
// on `requestKey` with `confirmed=true` - no native AlertDialog.
class ConfirmSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_confirm, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY).orEmpty()
        view.findViewById<TextView>(R.id.confirmTitle).text = args.getString(ARG_TITLE).orEmpty()
        view.findViewById<TextView>(R.id.confirmMsg).text = args.getString(ARG_MESSAGE).orEmpty()
        val btnOk = view.findViewById<MaterialButton>(R.id.btnConfirmOk)
        btnOk.text = args.getString(ARG_CONFIRM).orEmpty()
        if (args.getBoolean(ARG_DESTRUCTIVE)) {
            btnOk.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.negative)
            )
        }
        view.findViewById<View>(R.id.btnConfirmCancel).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
        btnOk.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            parentFragmentManager.setFragmentResult(
                requestKey, Bundle().apply { putBoolean(EXTRA_CONFIRMED, true) }
            )
            dismiss()
        }
    }

    companion object {
        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_CONFIRM = "confirm"
        private const val ARG_DESTRUCTIVE = "destructive"
        const val EXTRA_CONFIRMED = "confirmed"
        const val TAG = "ConfirmSheet"

        fun newInstance(
            requestKey: String,
            title: String,
            message: String,
            confirmText: String,
            destructive: Boolean = false
        ): ConfirmSheet = ConfirmSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putString(ARG_MESSAGE, message)
                putString(ARG_CONFIRM, confirmText)
                putBoolean(ARG_DESTRUCTIVE, destructive)
            }
        }
    }
}
