package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

// Swipe-to-leave for joined members (same swipe control as logout).
class SwipeLeaveSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_swipe_leave, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val personName = requireArguments().getString(ARG_PERSON_NAME).orEmpty()
        if (personName.isNotBlank()) {
            view.findViewById<TextView>(R.id.leavePersonName).text = personName
        } else {
            view.findViewById<View>(R.id.leavePersonName).visibility = View.GONE
        }
        val swipeView = view.findViewById<SwipeLogoutView>(R.id.swipeLeave)
        swipeView.label = getString(R.string.swipe_to_leave)
        swipeView.onSwipeComplete = {
            ShareSync.leavePerson(requireContext(), personId) { ok, err ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.left_person, Toast.LENGTH_SHORT).show()
                        (activity as? MainActivity)?.onPersonLeft(personId)
                        parentFragmentManager.setFragmentResult(REQ_LEFT, Bundle())
                    } else {
                        // Surface the real reason (permission text tells us
                        // exactly which layer denied, for instant diagnosis)
                        val msg = if (err.isNullOrBlank()) getString(R.string.action_failed)
                        else "${getString(R.string.action_failed)}: $err"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        const val TAG = "SwipeLeaveSheet"
        const val REQ_LEFT = "left_person"

        fun newInstance(personId: String, personName: String): SwipeLeaveSheet = SwipeLeaveSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putString(ARG_PERSON_NAME, personName)
            }
        }
    }
}
