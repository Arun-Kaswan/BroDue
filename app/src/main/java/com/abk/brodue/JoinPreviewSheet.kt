package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

class JoinPreviewSheet : BasePopup() {

    private var skeletonPulse: android.animation.ObjectAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_join_preview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val code = requireArguments().getString(ARG_CODE).orEmpty()
        val ctx = requireContext()
        val loading = view.findViewById<View>(R.id.previewLoading)
        val content = view.findViewById<View>(R.id.previewContent)
        // Skeleton pulse (plain alpha loop - no canvas compositing, so it
        // runs in every window type including dialog popups)
        skeletonPulse?.cancel()
        loading.alpha = 1f
        skeletonPulse = android.animation.ObjectAnimator.ofFloat(loading, View.ALPHA, 1f, 0.35f).apply {
            duration = 700L
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            start()
        }

        ShareSync.previewInvite(ctx, code) { preview ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (preview == null || preview.personId.isBlank()) {
                    val detail = ShareSync.lastWorkerError
                    Toast.makeText(
                        ctx,
                        if (!detail.isNullOrBlank()) "Could not load: $detail" else getString(R.string.code_load_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                    return@runOnUiThread
                }
                // Owner block: big rounded picture + name + email
                val ownerName = preview.ownerName.ifBlank { "Owner" }
                view.findViewById<TextView>(R.id.previewOwnerName).text = ownerName
                val ownerEmail = view.findViewById<TextView>(R.id.previewOwnerEmail)
                if (preview.ownerEmail.isNotBlank()) ownerEmail.text = preview.ownerEmail
                else ownerEmail.visibility = View.GONE
                val ownerInitial = view.findViewById<TextView>(R.id.previewOwnerInitial)
                val ownerPhoto = view.findViewById<ShapeableImageView>(R.id.previewOwnerPhoto)
                if (preview.ownerPhoto.isNotBlank()) {
                    ownerPhoto.load(preview.ownerPhoto)
                    ownerPhoto.visibility = View.VISIBLE
                    ownerInitial.visibility = View.GONE
                } else {
                    ownerInitial.text = ownerName.trim().take(1).uppercase()
                    AvatarColors.style(ctx, ownerInitial, ownerName)
                }
                // Person column: avatar + name + net balance
                val personName = preview.personName.ifBlank { getString(R.string.shared_person) }
                view.findViewById<TextView>(R.id.previewPersonName).text = personName
                val personInitial = view.findViewById<TextView>(R.id.previewPersonInitial)
                personInitial.text = personName.trim().take(1).uppercase()
                AvatarColors.style(ctx, personInitial, personName)
                val netTv = view.findViewById<TextView>(R.id.previewNet)
                val net = preview.netAmount
                netTv.text = android.text.TextUtils.concat(
                    if (net < 0) "-" else "+",
                    Formatters.amountWithSymbol(
                        kotlin.math.abs(net),
                        preview.currency.ifBlank { CurrencyManager.getSymbol(ctx) }
                    )
                )
                netTv.setTextColor(
                    ContextCompat.getColor(
                        ctx, if (net < 0) R.color.save_red else R.color.save_green
                    )
                )
                skeletonPulse?.cancel()
                skeletonPulse = null
                loading.visibility = View.GONE
                content.visibility = View.VISIBLE

                // Already in this group (owner incl. cross-device, or any
                // joined/left-aside local copy): no Join button, show status.
                val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                val local = LocalStore.people(ctx).optJSONObject(preview.personId)
                val alreadyIn = (local != null && !local.optBoolean("leftGroup", false)) ||
                    (preview.ownerUid.isNotBlank() && preview.ownerUid == me)
                val btnJoin = view.findViewById<MaterialButton>(R.id.btnDoJoin)
                if (alreadyIn) {
                    btnJoin.visibility = View.GONE
                    view.findViewById<View>(R.id.tvAlreadyJoined).visibility = View.VISIBLE
                    return@runOnUiThread
                }
                btnJoin.setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    btnJoin.isEnabled = false
                    btnJoin.text = getString(R.string.joining)
                    ShareSync.redeemCode(ctx, code) { ok, pidOrErr ->
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            if (ok && pidOrErr != null) {
                                // Go live immediately (no restart needed for updates)
                                ShareSync.startRealtime(ctx, pidOrErr)
                                (requireActivity() as? MainActivity)?.refreshLocalData()
                                Toast.makeText(ctx, R.string.person_joined, Toast.LENGTH_SHORT).show()
                                parentFragmentManager.setFragmentResult(REQ_JOINED, Bundle())
                                dismiss()
                            } else {
                                Toast.makeText(
                                    ctx, pidOrErr ?: getString(R.string.code_load_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (!pidOrErr.isNullOrBlank() && pidOrErr.contains("Sync limit", true)) {
                                    (requireActivity() as? MainActivity)?.maybeShowSyncLock()
                                }
                                btnJoin.isEnabled = true
                                btnJoin.text = getString(R.string.join)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        skeletonPulse?.cancel()
        skeletonPulse = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CODE = "code"
        const val TAG = "JoinPreviewSheet"
        const val REQ_JOINED = "join_done"

        fun newInstance(code: String): JoinPreviewSheet = JoinPreviewSheet().apply {
            arguments = Bundle().apply { putString(ARG_CODE, code) }
        }
    }
}
