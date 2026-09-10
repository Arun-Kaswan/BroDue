package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth

class ProfileSheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        val img = view.findViewById<ShapeableImageView>(R.id.imgProfile)
        val tvInitial = view.findViewById<TextView>(R.id.tvProfileInitial)
        val tvName = view.findViewById<TextView>(R.id.tvProfileName)
        val tvEmail = view.findViewById<TextView>(R.id.tvProfileEmail)

        val name = user?.displayName ?: "User"
        val email = user?.email ?: ""
        val photoUrl = user?.photoUrl

        tvName.text = name
        tvEmail.text = email

        // Offline builds have no account: no logout, generic identity
        if (BuildConfig.OFFLINE_MODE) {
            tvName.text = getString(R.string.app_name)
            tvEmail.visibility = View.GONE
            view.findViewById<View>(R.id.btnLogout).visibility = View.GONE
        }

        // Sync-limit usage pill (fresh every open, live cap). The pill
        // background itself is the meter - no ripple, no separate bar.
        // Tapping it while over the limit reopens the recovery sheet.
        try {
            val pill = view.findViewById<View>(R.id.syncUsagePill)
            pill.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                (activity as? MainActivity)?.maybeShowSyncLock(force = true)
            }
            val used = ShareSync.syncedCount(requireContext())
            val max = ShareSync.cachedSyncCap(requireContext())
            view.findViewById<TextView>(R.id.tvSyncUsage).text =
                getString(R.string.sync_usage, used, max)
            val level = if (max > 0) (used * 10000 / max).coerceIn(0, 10000) else 0
            (pill.background as? android.graphics.drawable.LayerDrawable)
                ?.findDrawableByLayerId(android.R.id.progress)
                ?.let { (it as? android.graphics.drawable.ClipDrawable)?.level = level }
        } catch (_: Exception) {}

        if (photoUrl != null) {
            tvInitial.visibility = View.GONE
            img.visibility = View.VISIBLE
            img.load(photoUrl)
        } else {
            img.visibility = View.INVISIBLE
            tvInitial.visibility = View.VISIBLE
            tvInitial.text = name.trim().take(1).uppercase().ifEmpty { "U" }
            AvatarColors.style(requireContext(), tvInitial, name)
            // Make initial background circular already via bg_circle
        }

        view.findViewById<View>(R.id.btnProfileArchive).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val act = activity as? MainActivity ?: return@setOnClickListener
            dismiss()
            act.runWhenSheetsClosed { act.openArchiveSheet() }
        }

        // Red dot on Setting while an update is pending (fresh every open)
        try {
            view.findViewById<View>(R.id.profileSettingDot).visibility =
                if (UpdateManager.getPendingUpdate(requireContext()) != null) View.VISIBLE else View.GONE
        } catch (_: Exception) {}

        view.findViewById<View>(R.id.btnProfileSetting).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val act = activity as? MainActivity
            act?.openSettings()
            dismiss()
        }

        view.findViewById<View>(R.id.btnProfileInfo).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val act = activity as? MainActivity ?: return@setOnClickListener
            dismiss()
            act.runWhenSheetsClosed { act.openAbout() }
        }

        view.findViewById<View>(R.id.btnLogout).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // Show swipe to logout sheet instead of instant logout
            SwipeLogoutSheet.newInstance().show(parentFragmentManager, SwipeLogoutSheet.TAG)
        }

        // Grouped corners like the Settings page: 18dp outer top/bottom, 8dp inner
        val rowCards = listOf(R.id.btnProfileArchive, R.id.btnProfileSetting, R.id.btnProfileInfo)
        val d = resources.displayMetrics.density
        val outerRadius = 18 * d
        val innerRadius = 8 * d
        rowCards.forEachIndexed { i, id ->
            val card = view.findViewById<com.google.android.material.card.MaterialCardView>(id)
            card.shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outerRadius else innerRadius)
                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == 0) outerRadius else innerRadius)
                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == rowCards.lastIndex) outerRadius else innerRadius)
                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (i == rowCards.lastIndex) outerRadius else innerRadius)
                .build()
        }
    }

    companion object {
        const val TAG = "ProfileSheet"
        fun newInstance(): ProfileSheet = ProfileSheet()
    }
}
