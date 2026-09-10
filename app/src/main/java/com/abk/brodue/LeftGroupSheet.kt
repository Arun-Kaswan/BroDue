package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

// Details of a left shared person: when it happened, who owned it,
// and the original owner-saved person entry. All from local storage.
class LeftGroupSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_left_group, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val ctx = requireContext()
        val p = LocalStore.people(ctx).optJSONObject(personId)
        if (p == null) {
            dismiss()
            return
        }

        view.findViewById<TextView>(R.id.tvLeftGroupTitle).text = when (p.optString("leftReason", "left")) {
            "disabled" -> getString(R.string.left_group_disabled)
            "removed" -> getString(R.string.left_group_removed)
            "foreign" -> getString(R.string.left_group_foreign)
            else -> getString(R.string.left_group_title)
        }

        val leftAt = p.optLong("leftAt", 0L)
        view.findViewById<TextView>(R.id.leftDate).text =
            if (leftAt > 0) Formatters.date(leftAt) else "–"
        view.findViewById<TextView>(R.id.leftTime).text =
            if (leftAt > 0) Formatters.time(leftAt) else "–"

        // Owner row: square photo + name + email
        val ownerRow = view.findViewById<LinearLayout>(R.id.leftOwnerRow)
        val ownerName = p.optString("ownerName", "").ifBlank { "Owner" }
        addRow(
            ownerRow,
            ownerName,
            p.optString("ownerEmail", ""),
            p.optString("ownerPhotoUrl", "")
        )

        // Person row: original owner-saved name + avatar (no email)
        val personRow = view.findViewById<LinearLayout>(R.id.leftPersonRow)
        val originalName = p.optString("originalName", "").ifBlank { p.optString("name", "") }
        addRow(personRow, originalName.ifBlank { "–" }, "", "")
    }

    private fun addRow(container: LinearLayout, name: String, email: String, photo: String) {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_sync_member, container, false)
        val initial = row.findViewById<TextView>(R.id.memberInitial)
        val photoView = row.findViewById<ShapeableImageView>(R.id.memberPhoto)
        row.findViewById<TextView>(R.id.memberName).text = name
        val emailTv = row.findViewById<TextView>(R.id.memberEmail)
        if (email.isNotBlank()) emailTv.text = email else emailTv.visibility = View.GONE
        if (photo.isNotBlank()) {
            photoView.load(photo)
            photoView.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            initial.text = name.trim().take(1).uppercase()
            AvatarColors.style(ctx, initial, name)
        }
        // No role badge in this drawer
        row.findViewById<MaterialCardView>(R.id.memberBadge).visibility = View.GONE
        container.addView(row)
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        const val TAG = "LeftGroupSheet"

        fun newInstance(personId: String): LeftGroupSheet = LeftGroupSheet().apply {
            arguments = Bundle().apply { putString(ARG_PERSON_ID, personId) }
        }
    }
}
