package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

// Owner-only: explains full cloud removal, confirms with the same swipe
// control as logout, then disables sync (local data is kept).
class DisableSyncSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_disable_sync, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val personName = requireArguments().getString(ARG_PERSON_NAME).orEmpty()
        if (personName.isNotBlank()) {
            view.findViewById<TextView>(R.id.disableSyncPerson).text = personName
        } else {
            view.findViewById<View>(R.id.disableSyncPerson).visibility = View.GONE
        }
        val swipeView = view.findViewById<SwipeLogoutView>(R.id.swipeDisable)
        swipeView.label = getString(R.string.swipe_to_disable)
        var busy = false
        swipeView.onSwipeComplete = swipe@{
            if (busy) return@swipe
            busy = true
            ShareSync.disableSyncFull(requireContext(), personId) { ok, err ->
                val act = activity
                act?.runOnUiThread {
                    if (!isAdded || act.isFinishing || act.isDestroyed) {
                        busy = false
                    } else if (ok) {
                        Toast.makeText(requireContext(), R.string.sync_disabled, Toast.LENGTH_SHORT).show()
                        parentFragmentManager.setFragmentResult(REQ_DISABLED, Bundle())
                        (act as? MainActivity)?.onPersonSyncChanged(personId)
                        // The i-drawer underneath is stale now: close it too
                        try {
                            (parentFragmentManager.findFragmentByTag(NetBalanceSheet.TAG)
                                as? androidx.fragment.app.DialogFragment)?.dismiss()
                        } catch (_: Exception) {}
                        dismiss()
                    } else {
                        busy = false
                        Toast.makeText(
                            requireContext(),
                            err ?: getString(R.string.action_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        const val TAG = "DisableSyncSheet"
        const val REQ_DISABLED = "sync_disabled_done"

        fun newInstance(personId: String, personName: String): DisableSyncSheet =
            DisableSyncSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_PERSON_NAME, personName)
                }
            }
    }
}
