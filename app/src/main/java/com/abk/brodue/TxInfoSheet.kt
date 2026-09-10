package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.imageview.ShapeableImageView

class TxInfoSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_tx_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val personId = args.getString(ARG_PERSON_ID).orEmpty()
        val savedAt = args.getLong(ARG_SAVED_AT)
        val creatorUid = args.getString(ARG_CREATOR_UID, "")

        view.findViewById<TextView>(R.id.txInfoSavedDate).text = Formatters.date(savedAt)
        view.findViewById<TextView>(R.id.txInfoSavedTime).text = Formatters.time(savedAt)

        // Local transactions show Created-at only (no Created-by section)
        if (!ShareSync.isCloudPerson(requireContext(), personId)) {
            view.findViewById<View>(R.id.txInfoCreatedByLabel).visibility = View.GONE
            view.findViewById<View>(R.id.txInfoCreatorCard).visibility = View.GONE
            return
        }
        val creatorName = view.findViewById<TextView>(R.id.txInfoCreatorName)
        val creatorEmail = view.findViewById<TextView>(R.id.txInfoCreatorEmail)
        val creatorYou = view.findViewById<TextView>(R.id.txInfoCreatorYou)
        val creatorImg = view.findViewById<ShapeableImageView>(R.id.txInfoCreatorImg)
        val isSelf = creatorUid.isNotBlank() && creatorUid == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        fun showCreator(name: String, email: String, photo: String) {
            creatorName.text = name
            if (isSelf) creatorYou.visibility = View.VISIBLE
            creatorEmail.text = email
            if (photo.isNotBlank()) {
                creatorImg.load(photo)
            } else {
                creatorImg.setImageResource(0)
                creatorImg.background = android.graphics.drawable.ColorDrawable(
                    ContextCompat.getColor(requireContext(), R.color.primary_container)
                )
            }
        }
        // Instant from the local mirror, then silent DB refresh in
        // case the mirror is stale (same values = no visible change)
        ShareSync.creatorProfile(requireContext(), personId, creatorUid)?.let { (name, email, photo) ->
            if (name.isNotBlank()) activity?.runOnUiThread { showCreator(name, email, photo) }
        }
        ShareSync.resolveCreatorRaw(requireContext(), personId, creatorUid) { name, email, photo ->
            activity?.runOnUiThread { showCreator(name, email, photo) }
        }
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_SAVED_AT = "savedAt"
        private const val ARG_CREATOR_UID = "creatorUid"
        const val TAG = "TxInfoSheet"

        fun newInstance(personId: String, savedAt: Long, creatorUid: String): TxInfoSheet = TxInfoSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putLong(ARG_SAVED_AT, savedAt)
                putString(ARG_CREATOR_UID, creatorUid)
            }
        }
    }
}
