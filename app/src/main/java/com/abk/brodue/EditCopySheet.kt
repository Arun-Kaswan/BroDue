package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

// Explains the edit-a-copy feature for left persons and runs it.
class EditCopySheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_edit_copy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        view.findViewById<View>(R.id.btnCopyCancel).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
        view.findViewById<View>(R.id.btnMakeCopy).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
            val ok = (activity as? MainActivity)?.makeEditableCopy(personId) == true
            if (!ok) {
                Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        const val TAG = "EditCopySheet"

        fun newInstance(personId: String): EditCopySheet = EditCopySheet().apply {
            arguments = Bundle().apply { putString(ARG_PERSON_ID, personId) }
        }
    }
}
