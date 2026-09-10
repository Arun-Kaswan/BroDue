package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

// Permanent person delete with slide confirmation. Removes the person and
// all their transactions locally (cloud mirrors follow for synced owners).
// Nothing about a delete is restorable.
class DeletePersonSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_delete_person, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val personName = requireArguments().getString(ARG_PERSON_NAME).orEmpty()
        view.findViewById<TextView>(R.id.deletePersonTitle).text =
            getString(R.string.delete_person_title, personName.ifBlank { "this person" })
        val swipeView = view.findViewById<SwipeLogoutView>(R.id.swipeDelete)
        swipeView.label = getString(R.string.swipe_to_delete)
        var busy = false
        swipeView.onSwipeComplete = swipe@{
            if (busy) return@swipe
            busy = true
            val ctx = requireContext()
            try {
                val txs = LocalStore.transactions(ctx)
                val keys = txs.keys()
                val doomed = mutableListOf<String>()
                while (keys.hasNext()) {
                    val tid = keys.next()
                    if (txs.optJSONObject(tid)?.optString("personId", "") == personId) {
                        doomed.add(tid)
                    }
                }
                doomed.forEach { LocalStore.removeTransaction(ctx, it) }
                LocalStore.removePerson(ctx, personId)
            } catch (_: Exception) {}
            (activity as? MainActivity)?.refreshLocalData()
            parentFragmentManager.setFragmentResult(REQ_DELETED, Bundle())
            Toast.makeText(ctx, R.string.person_deleted, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        const val TAG = "DeletePersonSheet"
        const val REQ_DELETED = "person_deleted"

        fun newInstance(personId: String, personName: String): DeletePersonSheet =
            DeletePersonSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_PERSON_NAME, personName)
                }
            }
    }
}
