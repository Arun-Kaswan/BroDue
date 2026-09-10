package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

class SyncConfirmSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_sync_confirm, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val personName = requireArguments().getString(ARG_PERSON_NAME).orEmpty()
        view.findViewById<TextView>(R.id.syncConfirmMsg).text =
            getString(R.string.sync_to_cloud_msg, personName.ifBlank { "this person" })

        view.findViewById<View>(R.id.btnSyncCancel).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
        val btnSync = view.findViewById<MaterialButton>(R.id.btnSyncConfirm)
        btnSync.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            btnSync.isEnabled = false
            btnSync.text = getString(R.string.syncing)
            ShareSync.enableSync(requireContext(), personId) { ok, err ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.person_synced, Toast.LENGTH_SHORT).show()
                        (activity as? MainActivity)?.onPersonSyncChanged(personId)
                        dismiss()
                        // Open the share drawer automatically, once
                        (activity as? MainActivity)?.openSyncShare(personId)
                    } else {
                        val msg = if (err.isNullOrBlank()) "Sync failed. Check connection." else "Sync failed: $err"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        if (!err.isNullOrBlank() && err.contains("Sync limit", true)) {
                            (activity as? MainActivity)?.maybeShowSyncLock()
                        }
                        btnSync.isEnabled = true
                        btnSync.text = getString(R.string.sync)
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        const val TAG = "SyncConfirmSheet"

        fun newInstance(personId: String, personName: String): SyncConfirmSheet = SyncConfirmSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putString(ARG_PERSON_NAME, personName)
            }
        }
    }
}
