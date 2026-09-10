package com.abk.brodue

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

class SyncShareSheet : BaseSheet() {

    private var codeBoxes: List<TextView> = emptyList()
    private var sheetPersonId: String = ""
    private var sheetPersonName: String = ""
    private var sheetIsOwner: Boolean = false
    private var shownMembers: List<ShareSync.SharedMember>? = null
    private var shownCode: String = ""
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private var progShareMe = false
    private var disableOrigBg: ColorStateList? = null
    private var disableOrigText: Int = 0
    private var newCodeOrigBg: ColorStateList? = null
    private var newCodeOrigText: Int = 0
    private var leaveOrigBg: ColorStateList? = null
    private var leaveOrigText: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_sync_share, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val ctx = requireContext()
        codeBoxes = listOf(
            view.findViewById(R.id.codeChar0),
            view.findViewById(R.id.codeChar1),
            view.findViewById(R.id.codeChar2),
            view.findViewById(R.id.codeChar3),
            view.findViewById(R.id.codeChar4),
            view.findViewById(R.id.codeChar5)
        )

        // Viewers/editors see members only - code, buttons and
        // share-as-me are owner-only.
        val role = ShareSync.accessRole(ctx, personId)
        val isOwner = role == "owner" || !ShareSync.isCloudPerson(ctx, personId)
        sheetPersonId = personId
        sheetPersonName = LocalStore.people(ctx).optJSONObject(personId)?.optString("name", "").orEmpty()
        sheetIsOwner = isOwner
        parentFragmentManager.setFragmentResultListener(MemberDetailSheet.REQ_MEMBER_CHANGED, this) { _, _ ->
            reloadMembers()
        }
        parentFragmentManager.setFragmentResultListener(SwipeLeaveSheet.REQ_LEFT, this) { _, _ ->
            dismiss()
        }
        if (!isOwner) {
            // Joined members can leave the shared person from here
            val btnLeave = view.findViewById<View>(R.id.btnLeave)
            btnLeave.visibility = View.VISIBLE
            btnLeave.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (!NetworkUtils.isOnline(ctx)) {
                    Toast.makeText(ctx, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SwipeLeaveSheet.newInstance(sheetPersonId, sheetPersonName)
                    .show(parentFragmentManager, SwipeLeaveSheet.TAG)
            }
            applyLeaveOnlineState()
        }
        connectivityListener = { applyShareOnlineState(); applyLeaveOnlineState() }
        ConnectivityMonitor.addListener(connectivityListener!!)
        if (!isOwner) {
            view.findViewById<View>(R.id.shareHeaderRow).visibility = View.GONE
            view.findViewById<View>(R.id.codeRow).visibility = View.GONE
            view.findViewById<View>(R.id.codeButtonsRow).visibility = View.GONE
            view.findViewById<View>(R.id.btnShareCode).visibility = View.GONE
            view.findViewById<View>(R.id.shareAsMeCard).visibility = View.GONE
            // "Members" becomes the centered drawer heading
            val membersTitle = view.findViewById<TextView>(R.id.membersTitle)
            val lp = membersTitle.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.topMargin = (16 * ctx.resources.displayMetrics.density).toInt()
            membersTitle.layoutParams = lp
            membersTitle.gravity = android.view.Gravity.CENTER
            membersTitle.textSize = 17f
            membersTitle.setTextColor(ContextCompat.getColor(ctx, R.color.navy_text))
            membersTitle.setTypeface(membersTitle.typeface, android.graphics.Typeface.BOLD)
        }
        if (isOwner) {
            view.findViewById<View>(R.id.btnShareCode).setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                it.isEnabled = false
                shareActiveCode { ok ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        it.isEnabled = true
                        if (!ok) {
                            Toast.makeText(ctx, R.string.code_load_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            ShareSync.cleanupStaleQrCodes(ctx)
            val switchShareAsMe = view.findViewById<IosSwitch>(R.id.switchShareAsMe2)
            switchShareAsMe.isChecked = ShareSync.shareAsMe(ctx, personId)
            fun setSwitchChecked(v: Boolean) {
                progShareMe = true
                switchShareAsMe.isChecked = v
                progShareMe = false
            }
            switchShareAsMe.setOnCheckedChangeListener { _, checked ->
                if (progShareMe) return@setOnCheckedChangeListener
                if (!NetworkUtils.isOnline(ctx)) {
                    setSwitchChecked(!checked)
                    Toast.makeText(ctx, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                // Hold the previous state until the database confirms
                setSwitchChecked(!checked)
                switchShareAsMe.isEnabled = false
                ShareSync.setShareAsMe(personId, checked) { ok ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (ok) {
                            ShareSync.setLocalShareAsMe(ctx, personId, checked)
                            setSwitchChecked(checked)
                        } else {
                            Toast.makeText(
                                ctx,
                                if (!NetworkUtils.isOnline(ctx)) getString(R.string.no_internet_save)
                                else getString(R.string.action_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (isAdded) switchShareAsMe.isEnabled = NetworkUtils.isOnline(ctx)
                    }
                }
            }
            applyShareOnlineState()
            // One-time QR join code (separate from the main share code,
            // auto-deleted when the QR is closed)
            view.findViewById<View>(R.id.btnQr).setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                it.isEnabled = false
                ShareSync.generateQrCode(ctx, personId) { code ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        it.isEnabled = true
                        if (code == null) {
                            Toast.makeText(ctx, R.string.code_load_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            QrSheet.newInstance(code, System.currentTimeMillis())
                                .show(parentFragmentManager, QrSheet.TAG)
                        }
                    }
                }
            }
        }

        // Show the current code only - never auto-create one here.
        // A new code appears only after tapping "Create new code".
        // Instant from the local mirror, then silent refresh if it changed.
        if (isOwner) {
            shownCode = ShareSync.localShareCode(ctx, personId)
            fillCode(shownCode.ifBlank { null })
            ShareSync.getCode(ctx, personId) { code ->
                activity?.runOnUiThread {
                    if (!isAdded || code == null) return@runOnUiThread
                    if (code != shownCode) {
                        shownCode = code
                        fillCode(code.ifBlank { null })
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btnDisableCode).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            it.isEnabled = false
            ShareSync.disableCode(ctx, personId) { ok ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    applyShareOnlineState()
                    if (ok) {
                        shownCode = ""
                        fillCode(null)
                        Toast.makeText(ctx, R.string.code_disabled, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, R.string.code_load_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btnNewCode).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            it.isEnabled = false
            ShareSync.regenerateCode(ctx, personId) { code ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    applyShareOnlineState()
                    if (code == null) {
                        Toast.makeText(ctx, R.string.code_load_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        shownCode = code
                        fillCode(code)
                        Toast.makeText(ctx, R.string.new_code_created, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Members: owner always on top, then editors, then viewers
        reloadMembers()
    }

    override fun onDestroyView() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        super.onDestroyView()
    }

    // Leave needs internet too: grey + disable while offline
    private fun applyLeaveOnlineState() {
        val v = view ?: return
        if (!isAdded || sheetIsOwner) return
        val ctx = requireContext()
        val btnLeave =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLeave)
        if (btnLeave.visibility != View.VISIBLE) return
        if (leaveOrigBg == null) {
            leaveOrigBg = btnLeave.backgroundTintList
            leaveOrigText = btnLeave.currentTextColor
        }
        val online = NetworkUtils.isOnline(ctx)
        btnLeave.isEnabled = online
        if (online) {
            btnLeave.backgroundTintList = leaveOrigBg
            btnLeave.setTextColor(leaveOrigText)
        } else {
            btnLeave.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.outline_variant)
            )
            btnLeave.setTextColor(ContextCompat.getColor(ctx, R.color.grey_soft))
        }
    }

    // Code buttons + share-as-me need internet: grey + disable while offline.
    // Synced persons only - everything else works normally without internet.
    private fun applyShareOnlineState() {
        val v = view ?: return
        if (!isAdded || !sheetIsOwner) return
        val ctx = requireContext()
        if (!ShareSync.isSynced(ctx, sheetPersonId)) return
        val online = NetworkUtils.isOnline(ctx)
        val btnDisable =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDisableCode)
        val btnNew =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewCode)
        if (disableOrigBg == null) {
            disableOrigBg = btnDisable.backgroundTintList
            disableOrigText = btnDisable.currentTextColor
            newCodeOrigBg = btnNew.backgroundTintList
            newCodeOrigText = btnNew.currentTextColor
        }
        val greyBg = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.outline_variant))
        val greyText = ContextCompat.getColor(ctx, R.color.grey_soft)
        btnDisable.isEnabled = online
        btnDisable.backgroundTintList = if (online) disableOrigBg else greyBg
        btnDisable.setTextColor(if (online) disableOrigText else greyText)
        btnNew.isEnabled = online
        btnNew.backgroundTintList = if (online) newCodeOrigBg else greyBg
        btnNew.setTextColor(if (online) newCodeOrigText else greyText)
        val switchShareAsMe = v.findViewById<IosSwitch>(R.id.switchShareAsMe2)
        if (!progShareMe) {
            switchShareAsMe.isEnabled = online
            switchShareAsMe.alpha = if (online) 1f else 0.5f
        }
    }

    private fun reloadMembers() {
        val v = view ?: return
        val ctx = requireContext()
        val membersList = v.findViewById<LinearLayout>(R.id.membersList)
        // Instant render: full cached list if present, else the local owner
        // row - so the drawer never sits empty waiting on the network.
        val instant = ShareSync.cachedMembers(ctx, sheetPersonId)
            ?: ShareSync.localOwnerMember(ctx, sheetPersonId)?.let { listOf(it) }
        if (instant != null) {
            shownMembers = instant
            renderMemberRows(membersList, instant)
        } else {
            membersList.removeAllViews()
        }
        // Silent refresh: rebuild only when the data actually changed.
        ShareSync.listAccess(ctx, sheetPersonId) { users ->
            activity?.runOnUiThread {
                if (!isAdded || users.isEmpty() || users == shownMembers) return@runOnUiThread
                shownMembers = users
                renderMemberRows(membersList, users)
            }
        }
    }

    private fun renderMemberRows(membersList: LinearLayout, users: List<ShareSync.SharedMember>) {
        membersList.removeAllViews()
        val ordered = users.sortedWith(
            compareBy<ShareSync.SharedMember>(
                { if (it.role == "owner") 0 else if (it.role == "write") 1 else 2 },
                { it.name.ifBlank { it.uid } }
            )
        )
        ordered.forEach { addMemberRow(membersList, it) }
        // Settings-style grouped corners: high radius on the outer
        // corners (first row top, last row bottom), 8dp inside.
        val n = membersList.childCount
        for (i in 0 until n) {
            membersList.getChildAt(i).setBackgroundResource(
                when {
                    n == 1 -> R.drawable.bg_member_row_single
                    i == 0 -> R.drawable.bg_member_row_top
                    i == n - 1 -> R.drawable.bg_member_row_bottom
                    else -> R.drawable.bg_member_row
                }
            )
        }
    }

    private fun fillCode(code: String?) {
        codeBoxes.forEachIndexed { i, box ->
            box.text = code?.getOrNull(i)?.toString() ?: "–"
        }
    }

    // Share the ACTIVE joining code (+ its QR) as one image. Creates a code
    // first when none is active yet.
    private fun shareActiveCode(done: (Boolean) -> Unit) {
        val ctx = requireContext()
        val proceed: (String) -> Unit = { code ->
            val p = LocalStore.people(ctx).optJSONObject(sheetPersonId)
            val displayName = if (ShareSync.shareAsMe(ctx, sheetPersonId)) {
                p?.optString("ownerName", "").orEmpty().ifBlank {
                    com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName.orEmpty()
                }.ifBlank { p?.optString("name", "").orEmpty() }
            } else {
                p?.optString("name", "").orEmpty()
            }
            Thread {
                var ok = false
                try {
                    val bmp = ShareCodeImage.build(ctx, displayName, QrSheet.joinLink(code), code)
                    if (bmp != null) {
                        val act = activity
                        activity?.runOnUiThread {
                            ok = if (isAdded && act != null) ShareCodeImage.share(act, bmp) else false
                            done(ok)
                        }
                        return@Thread
                    }
                } catch (_: Exception) {}
                activity?.runOnUiThread { done(false) }
            }.start()
        }
        if (shownCode.isNotBlank()) {
            proceed(shownCode)
        } else {
            ShareSync.regenerateCode(ctx, sheetPersonId) { fresh ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (fresh == null) {
                        done(false)
                    } else {
                        shownCode = fresh
                        fillCode(fresh)
                        proceed(fresh)
                    }
                }
            }
        }
    }

    private fun addMemberRow(container: LinearLayout, m: ShareSync.SharedMember) {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_sync_member, container, false)
        val initial = row.findViewById<TextView>(R.id.memberInitial)
        val photo = row.findViewById<ShapeableImageView>(R.id.memberPhoto)
        val displayName = m.name.ifBlank { m.uid.take(8) }
        row.findViewById<TextView>(R.id.memberName).text = displayName
        val emailTv = row.findViewById<TextView>(R.id.memberEmail)
        if (m.email.isNotBlank()) emailTv.text = m.email else emailTv.visibility = View.GONE
        if (m.photo.isNotBlank()) {
            photo.load(m.photo)
            photo.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            initial.text = displayName.trim().take(1).uppercase()
            AvatarColors.style(ctx, initial, displayName)
        }
        val badge = row.findViewById<MaterialCardView>(R.id.memberBadge)
        val badgeIcon = row.findViewById<ImageView>(R.id.memberBadgeIcon)
        when (m.role) {
            "owner" -> {
                badge.visibility = View.VISIBLE
                badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.cat_amber_soft))
                badgeIcon.setImageResource(R.drawable.ic_owner_badge)
                badgeIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.cat_amber))
            }
            "write" -> {
                badge.visibility = View.VISIBLE
                badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.primary_container))
                badgeIcon.setImageResource(R.drawable.ic_editor_badge)
                badgeIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary))
            }
            else -> badge.visibility = View.GONE
        }
        // Settings-style 4dp gap between rows
        if (container.childCount > 0) {
            val lp = row.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = (4 * ctx.resources.displayMetrics.density).toInt()
            row.layoutParams = lp
        }
        // Owner taps any non-owner member to manage them
        if (sheetIsOwner && m.role != "owner") {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                MemberDetailSheet.newInstance(sheetPersonId, sheetPersonName, m)
                    .show(parentFragmentManager, MemberDetailSheet.TAG)
            }
        }
        container.addView(row)
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        const val TAG = "SyncShareSheet"

        fun newInstance(personId: String): SyncShareSheet = SyncShareSheet().apply {
            arguments = Bundle().apply { putString(ARG_PERSON_ID, personId) }
        }
    }
}
