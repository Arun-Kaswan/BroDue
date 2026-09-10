package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

// Swipe-to-delete confirmation for a single transaction edit
// (same swipe control as leave/disable/logout).
class SwipeDeleteTxSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_swipe_delete_tx, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val txId = requireArguments().getString(ARG_TX_ID).orEmpty()
        val summary = requireArguments().getString(ARG_SUMMARY).orEmpty()
        if (summary.isNotBlank()) {
            view.findViewById<TextView>(R.id.deleteTxSummary).text = summary
        } else {
            view.findViewById<View>(R.id.deleteTxSummary).visibility = View.GONE
        }
        val swipeView = view.findViewById<SwipeLogoutView>(R.id.swipeDeleteTx)
        swipeView.label = getString(R.string.swipe_to_delete)
        swipeView.onSwipeComplete = {
            parentFragmentManager.setFragmentResult(
                REQ_DELETE_TX,
                Bundle().apply {
                    putString("personId", personId)
                    putString("txId", txId)
                }
            )
            dismiss()
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_TX_ID = "txId"
        private const val ARG_SUMMARY = "summary"
        const val TAG = "SwipeDeleteTxSheet"
        const val REQ_DELETE_TX = "delete_tx_confirmed"

        fun newInstance(personId: String, txId: String, summary: String): SwipeDeleteTxSheet =
            SwipeDeleteTxSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_TX_ID, txId)
                    putString(ARG_SUMMARY, summary)
                }
            }
    }
}
