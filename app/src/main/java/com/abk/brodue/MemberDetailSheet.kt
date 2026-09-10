package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

class MemberDetailSheet : BaseSheet() {

    private var pendingUid: String = ""
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private var removeOrigBg: android.content.res.ColorStateList? = null
    private var removeOrigText: Int = 0
    private var roleOrigBg: android.content.res.ColorStateList? = null
    private var roleOrigText: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_member_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val personId = args.getString(ARG_PERSON_ID).orEmpty()
        val personName = args.getString(ARG_PERSON_NAME).orEmpty()
        val uid = args.getString(ARG_UID).orEmpty()
        val role = args.getString(ARG_ROLE).orEmpty()
        val name = args.getString(ARG_NAME).orEmpty()
        val email = args.getString(ARG_EMAIL).orEmpty()
        val photo = args.getString(ARG_PHOTO).orEmpty()
        val ctx = requireContext()
        val displayName = name.ifBlank { uid.take(8) }

        view.findViewById<TextView>(R.id.memberDetailName).text = displayName
        val emailTv = view.findViewById<TextView>(R.id.memberDetailEmail)
        if (email.isNotBlank()) emailTv.text = email else emailTv.visibility = View.GONE
        val initial = view.findViewById<TextView>(R.id.memberDetailInitial)
        val img = view.findViewById<ShapeableImageView>(R.id.memberDetailPhoto)
        if (photo.isNotBlank()) {
            img.load(photo)
            img.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            initial.text = displayName.trim().take(1).uppercase()
            AvatarColors.style(ctx, initial, displayName)
        }

        parentFragmentManager.setFragmentResultListener(REQ_CONFIRM_WRITER, this) { _, _ ->
            applyRole(pendingUid, "write")
        }
        parentFragmentManager.setFragmentResultListener(REQ_CONFIRM_REMOVE, this) { _, _ ->
            removeMember(pendingUid)
        }

        val isReader = role != "write"
        val btnRole = view.findViewById<MaterialButton>(R.id.btnMemberRole)
        btnRole.text = getString(if (isReader) R.string.make_writer else R.string.make_reader)
        connectivityListener = { applyMemberOnlineState() }
        ConnectivityMonitor.addListener(connectivityListener!!)
        applyMemberOnlineState()
        btnRole.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (isReader) {
                pendingUid = uid
                ConfirmSheet.newInstance(
                    REQ_CONFIRM_WRITER,
                    getString(R.string.make_writer_title),
                    getString(R.string.make_writer_msg, displayName, personName.ifBlank { "this person" }),
                    getString(R.string.make_writer)
                ).show(parentFragmentManager, ConfirmSheet.TAG)
            } else {
                applyRole(uid, "read")
            }
        }
        view.findViewById<View>(R.id.btnMemberRemove).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            pendingUid = uid
            ConfirmSheet.newInstance(
                REQ_CONFIRM_REMOVE,
                getString(R.string.remove_member_title),
                getString(R.string.remove_member_msg, displayName, personName.ifBlank { "this person" }),
                getString(R.string.remove),
                destructive = true
            ).show(parentFragmentManager, ConfirmSheet.TAG)
        }
    }

    override fun onDestroyView() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        super.onDestroyView()
    }

    // Remove / role change need internet: grey + disable while offline
    private fun applyMemberOnlineState() {
        val v = view ?: return
        if (!isAdded) return
        val ctx = requireContext()
        val online = NetworkUtils.isOnline(ctx)
        val btnRemove =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMemberRemove)
        val btnRole =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMemberRole)
        if (removeOrigBg == null) {
            removeOrigBg = btnRemove.backgroundTintList
            removeOrigText = btnRemove.currentTextColor
            roleOrigBg = btnRole.backgroundTintList
            roleOrigText = btnRole.currentTextColor
        }
        val greyBg = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(ctx, R.color.outline_variant)
        )
        val greyText = androidx.core.content.ContextCompat.getColor(ctx, R.color.grey_soft)
        btnRemove.isEnabled = online
        btnRemove.backgroundTintList = if (online) removeOrigBg else greyBg
        btnRemove.setTextColor(if (online) removeOrigText else greyText)
        btnRole.isEnabled = online
        btnRole.backgroundTintList = if (online) roleOrigBg else greyBg
        btnRole.setTextColor(if (online) roleOrigText else greyText)
    }

    private fun applyRole(uid: String, newRole: String) {
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        ShareSync.setPermission(requireContext(), personId, uid, newRole) { ok ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    Toast.makeText(requireContext(), R.string.permission_updated, Toast.LENGTH_SHORT).show()
                    notifyChanged()
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun removeMember(uid: String) {
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        ShareSync.revokeAccess(requireContext(), personId, uid) { ok ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    Toast.makeText(requireContext(), R.string.access_revoked, Toast.LENGTH_SHORT).show()
                    notifyChanged()
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun notifyChanged() {
        parentFragmentManager.setFragmentResult(REQ_MEMBER_CHANGED, Bundle())
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        private const val ARG_UID = "uid"
        private const val ARG_ROLE = "role"
        private const val ARG_NAME = "name"
        private const val ARG_EMAIL = "email"
        private const val ARG_PHOTO = "photo"
        const val TAG = "MemberDetailSheet"
        const val REQ_MEMBER_CHANGED = "member_changed"
        private const val REQ_CONFIRM_WRITER = "confirm_make_writer"
        private const val REQ_CONFIRM_REMOVE = "confirm_remove_member"

        fun newInstance(
            personId: String,
            personName: String,
            member: ShareSync.SharedMember
        ): MemberDetailSheet = MemberDetailSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putString(ARG_PERSON_NAME, personName)
                putString(ARG_UID, member.uid)
                putString(ARG_ROLE, member.role)
                putString(ARG_NAME, member.name)
                putString(ARG_EMAIL, member.email)
                putString(ARG_PHOTO, member.photo)
            }
        }
    }
}
