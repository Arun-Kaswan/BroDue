package com.abk.brodue

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import coil.load

class NetBalanceSheet : BaseSheet() {

    private var onArchived: (() -> Unit)? = null
    private var archived = false
    private var transactions: List<Transaction> = emptyList()
    private var rootView: View? = null
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private var archiveOriginalBg: ColorStateList? = null
    private var archiveOriginalIcon: ColorStateList? = null
    private var archiveOriginalText: Int = 0
    private var editOriginalBg: ColorStateList? = null
    private var editOriginalIcon: ColorStateList? = null
    private var editOriginalText: Int = 0
    private var lastGreyed: Boolean? = null
    private var currencyOrigBg: ColorStateList? = null
    private var currencyOrigText: Int = 0
    private var currencyOrigIcon: ColorStateList? = null

    // Currency mirrors to the cloud for synced persons: grey + disable offline
    private fun applyCurrencyOnlineState(view: View, personId: String) {
        if (!isAdded) return
        val btn = try {
            view.findViewById<MaterialButton>(R.id.btnPersonCurrency)
        } catch (_: Exception) {
            return
        }
        if (btn.visibility != View.VISIBLE) return
        if (currencyOrigBg == null) {
            currencyOrigBg = btn.backgroundTintList
            currencyOrigText = btn.currentTextColor
            currencyOrigIcon = btn.iconTint
        }
        val grey = try {
            ShareSync.isSynced(requireContext(), personId) && !NetworkUtils.isOnline(requireContext())
        } catch (_: Exception) {
            false
        }
        btn.isEnabled = !grey
        if (grey) {
            btn.backgroundTintList =
                ColorStateList.valueOf(requireContext().getColor(R.color.outline_variant))
            btn.setTextColor(requireContext().getColor(R.color.grey_soft))
            btn.iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.grey_soft))
        } else {
            btn.backgroundTintList = currencyOrigBg
            btn.setTextColor(currencyOrigText)
            btn.iconTint = currencyOrigIcon
        }
    }
    private var lastNet: Long = 0L
    private var lastReceived: Long = 0L
    private var lastGave: Long = 0L
    private var currentPersonId: String = ""

    // Re-resolves the header (name/avatar/share-as-me lines) from fresh
    // local state. Called when the person is renamed while this drawer
    // sits open underneath the edit sheet.
    fun refreshPersonInfo() {
        val v = rootView ?: return
        if (!isAdded) return
        val ctx = requireContext()
        val pid = currentPersonId
        if (pid.isBlank()) return
        val personObj = LocalStore.people(ctx).optJSONObject(pid) ?: return
        val realName = personObj.optString("name", "")
        val role = ShareSync.accessRole(ctx, pid)
        val shareAsMeActive = ShareSync.isCloudPerson(ctx, pid) &&
            personObj.optBoolean("shareAsMe", false) && role != "owner"
        val displayName = if (shareAsMeActive) {
            personObj.optString("ownerName", "").ifBlank { realName }
        } else {
            realName
        }
        val displayPhoto = if (shareAsMeActive) personObj.optString("ownerPhotoUrl", "") else ""
        v.findViewById<TextView>(R.id.tvNetPersonName).text = displayName
        val ivAvatar = v.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivNetAvatar)
        val tvInitial = v.findViewById<TextView>(R.id.tvNetAvatarInitial)
        if (displayPhoto.isNotBlank()) {
            ivAvatar.visibility = View.VISIBLE
            ivAvatar.load(displayPhoto)
            tvInitial.visibility = View.GONE
        } else {
            ivAvatar.visibility = View.GONE
            tvInitial.visibility = View.VISIBLE
            tvInitial.text = displayName.trim().take(1).uppercase().ifEmpty { "?" }
            AvatarColors.style(ctx, tvInitial, displayName)
        }
        val ownerEmailLine = v.findViewById<TextView>(R.id.ownerEmailLine)
        val realPersonLine = v.findViewById<TextView>(R.id.realPersonLine)
        if (shareAsMeActive) {
            val ownerEmail = personObj.optString("ownerEmail", "")
            if (ownerEmail.isNotBlank()) {
                ownerEmailLine.text = ownerEmail
                ownerEmailLine.visibility = View.VISIBLE
            } else {
                ownerEmailLine.visibility = View.GONE
            }
            if (realName.isNotBlank() && realName != displayName) {
                realPersonLine.text = realName
                realPersonLine.visibility = View.VISIBLE
            } else {
                realPersonLine.visibility = View.GONE
            }
        } else {
            ownerEmailLine.visibility = View.GONE
            realPersonLine.visibility = View.GONE
        }
        val hideCall = role != "owner" && ShareSync.isCloudPerson(ctx, pid) && shareAsMeActive
        v.findViewById<View>(R.id.btnCall).visibility = if (hideCall) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.callSpace).visibility = if (hideCall) View.GONE else View.VISIBLE
    }

    // Re-resolve the per-person currency and repaint amounts (called when
    // the picker saves while this drawer is open).
    fun refreshCurrency() {
        val v = rootView ?: return
        if (!isAdded) return
        val symbol = CurrencyManager.symbolForPerson(requireContext(), currentPersonId)
        val tvNet = v.findViewById<TextView>(R.id.tvNetBalance)
        tvNet.text = if (lastNet > 0) {
            android.text.TextUtils.concat("+", Formatters.amountWithSymbol(lastNet, symbol))
        } else {
            Formatters.amountWithSymbol(lastNet, symbol)
        }
        v.findViewById<TextView>(R.id.tvReceivedAmount).text =
            Formatters.amountWithSymbol(lastReceived, symbol)
        v.findViewById<TextView>(R.id.tvSendAmount).text =
            Formatters.amountWithSymbol(lastGave, symbol)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_net_balance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view
        val args = requireArguments()
        val personId = args.getString(ARG_PERSON_ID).orEmpty()
        val name = args.getString(ARG_NAME).orEmpty()
        val mobile = args.getString(ARG_MOBILE).orEmpty()
        val net = args.getLong(ARG_NET)
        val received = args.getLong(ARG_RECEIVED)
        val gave = args.getLong(ARG_GAVE)
        val receivedCount = args.getInt(ARG_RECEIVED_COUNT)
        val gaveCount = args.getInt(ARG_GAVE_COUNT)
        currentPersonId = personId
        lastNet = net
        lastReceived = received
        lastGave = gave
        parentFragmentManager.setFragmentResultListener(
            PersonCurrencySheet.REQ_KEY, this
        ) { _, bundle ->
            if (bundle.getString("personId").orEmpty() == personId) refreshCurrency()
        }

        val tvNet = view.findViewById<TextView>(R.id.tvNetBalance)
        // Avatar + name header
        val ivAvatar = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivNetAvatar)
        val tvInitial = view.findViewById<TextView>(R.id.tvNetAvatarInitial)
        ivAvatar.visibility = View.GONE
        tvInitial.text = name.trim().take(1).uppercase().ifEmpty { "?" }
        AvatarColors.style(requireContext(), tvInitial, name)
        view.findViewById<TextView>(R.id.tvNetPersonName).text = name

        val symbol = CurrencyManager.symbolForPerson(requireContext(), personId)
        tvNet.text = if (net > 0) {
            android.text.TextUtils.concat("+", Formatters.amountWithSymbol(net, symbol))
        } else {
            Formatters.amountWithSymbol(net, symbol)
        }
        tvNet.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    net > 0 -> R.color.positive
                    net < 0 -> R.color.negative
                    else -> R.color.navy_text
                }
            )
        )

        view.findViewById<TextView>(R.id.tvReceivedLabel).text = getString(R.string.receive_count, receivedCount)
        view.findViewById<TextView>(R.id.tvReceivedAmount).text =
            Formatters.amountWithSymbol(received, symbol)
        view.findViewById<TextView>(R.id.tvSendLabel).text = getString(R.string.send_count, gaveCount)
        view.findViewById<TextView>(R.id.tvSendAmount).text =
            Formatters.amountWithSymbol(gave, symbol)

        view.findViewById<View>(R.id.btnCall).setOnClickListener {
            if (mobile.isNotBlank()) {
                val digits = mobile.replace(Regex("\\D"), "")
                val dial = when {
                    digits.length == 10 -> "+91$digits"
                    digits.length == 12 && digits.startsWith("91") -> "+91" + digits.takeLast(10)
                    else -> mobile
                }
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial")))
            } else {
                Toast.makeText(requireContext(), R.string.no_mobile, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btnDownload).setOnClickListener {
            StatementSheet.newInstance(
                getString(R.string.download_statement), name, mobile, net, transactions, saveOnly = true
            ).show(parentFragmentManager, StatementSheet.TAG)
        }
        view.findViewById<View>(R.id.btnShare).setOnClickListener {
            StatementSheet.newInstance(
                getString(R.string.share_statement), name, mobile, net, transactions
            ).show(parentFragmentManager, StatementSheet.TAG)
        }

        view.findViewById<View>(R.id.btnArchive).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // Synced archives need a connection first; local ones work offline
            if (ShareSync.isSynced(requireContext(), personId) &&
                !NetworkUtils.retryConnection(requireContext())
            ) return@setOnClickListener
            val roleHere = ShareSync.accessRole(requireContext(), personId)
            val isCloudHere = ShareSync.isCloudPerson(requireContext(), personId)
            val ownerHere = roleHere == "owner" || !isCloudHere
            // Owner of a synced person: full-disable flow instead of archive
            if (ownerHere && ShareSync.isSynced(requireContext(), personId)) {
                DisableSyncSheet.newInstance(personId, name)
                    .show(parentFragmentManager, DisableSyncSheet.TAG)
                return@setOnClickListener
            }
            // Joined reader/writer: explain the leave-first rule
            if (!ownerHere) {
                ConfirmSheet.newInstance(
                    REQ_LEAVE_TO_ARCHIVE,
                    getString(R.string.leave_to_archive_title),
                    getString(R.string.leave_to_archive_msg, name.ifBlank { "this person" }),
                    getString(R.string.leave_group)
                ).show(parentFragmentManager, ConfirmSheet.TAG)
                return@setOnClickListener
            }
            val act = requireActivity() as androidx.fragment.app.FragmentActivity
            TaskLockGuard.gate(act, "archive", "Archive") {
                try {
                    LocalStore.updatePersonFields(
                        requireContext(), personId, mapOf("archived" to true)
                    )
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.save_error, Toast.LENGTH_SHORT).show()
                    return@gate
                }
                Toast.makeText(requireContext(), R.string.archived_toast, Toast.LENGTH_SHORT).show()
                (requireActivity() as? MainActivity)?.refreshLocalData()
                dismiss()
                onArchived?.invoke()
            }
        }

        view.findViewById<View>(R.id.btnEdit).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (ShareSync.isSynced(requireContext(), personId) &&
                !NetworkUtils.retryConnection(requireContext())
            ) return@setOnClickListener
            val act = requireActivity() as androidx.fragment.app.FragmentActivity
            TaskLockGuard.gate(act, "edit_person", "Edit Person") {
                AddPersonBottomSheet.newInstance(personId, name, mobile)
                    .show(parentFragmentManager, AddPersonBottomSheet.TAG)
            }
        }

        val btnArchive = view.findViewById<MaterialButton>(R.id.btnArchive)
        val btnEdit = view.findViewById<MaterialButton>(R.id.btnEdit)
        archiveOriginalBg = btnArchive.backgroundTintList
        archiveOriginalIcon = btnArchive.iconTint
        archiveOriginalText = btnArchive.currentTextColor
        editOriginalBg = btnEdit.backgroundTintList
        editOriginalIcon = btnEdit.iconTint
        editOriginalText = btnEdit.currentTextColor
        lastGreyed = false
        // Offline grey applies to synced persons only; local ones work offline
        fun effectiveOnline(online: Boolean): Boolean =
            online || !ShareSync.isSynced(requireContext(), personId)
        if (!effectiveOnline(ConnectivityMonitor.online) || archived) applyActionState(view, false)
        connectivityListener = { online ->
            applyActionState(view, effectiveOnline(online))
            applyCurrencyOnlineState(view, personId)
        }
        ConnectivityMonitor.addListener(connectivityListener!!)

        // Show the OWNER info (name/email/photo) to joined users
        val ctx = requireContext()
        val ownerRow = view.findViewById<View>(R.id.ownerInfoRow)
        val ownerImg = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ownerInfoImg)
        val ownerTxt = view.findViewById<TextView>(R.id.ownerInfoTxt)
        val joinedSide = Shared_ownerLabel(ctx, personId)
        if (joinedSide != null) {
            val (ownerName, ownerEmail, ownerPhoto) = joinedSide
            ownerTxt.text = "Shared by $ownerName · $ownerEmail"
            if (ownerPhoto.isNotBlank()) {
                ownerImg.load(ownerPhoto)
            } else {
                ownerImg.setImageResource(0)
                ownerImg.background = ContextCompat.getDrawable(ctx, R.drawable.bg_circle)
                ownerImg.alpha = 0.4f
            }
            ownerRow.visibility = View.VISIBLE
        } else {
            ownerRow.visibility = View.GONE
        }

        // ---------- Per-person role gating ----------
        // (Sync/share/access/share-as-me now live in the separate sync drawer,
        // opened from the sync icon on the person page.)
        val role = ShareSync.accessRole(ctx, personId)
        val isCloud = ShareSync.isCloudPerson(ctx, personId)
        // Local persons (not synced yet) are owned by the current user
        val isOwner = role == "owner" || !isCloud
        val readOnlyShared = isCloud && ShareSync.isSynced(ctx, personId) && role == "read"
        // ---- per-role UI: joined users get only what their role permits ----
        // (full DB protection is enforced by Firestore rules; UI mirrors it)
        if (!isOwner) {
            // Joined readers/writers keep Archive: it explains the leave-first rule
            btnEdit.visibility = View.GONE
            btnArchive.visibility = View.VISIBLE
        }
        // Archived inspect: view-only, no Archive/Edit/Currency actions
        if (archived) {
            btnArchive.visibility = View.GONE
            btnEdit.visibility = View.GONE
            view.findViewById<View>(R.id.btnPersonCurrency).visibility = View.GONE
        }
        // Owner of a synced person: Archive becomes Disable sync
        val syncedHere = ShareSync.isSynced(ctx, personId)
        if (isOwner && syncedHere) {
            btnArchive.setText(R.string.disable_sync)
            btnArchive.setIconResource(R.drawable.ic_sync_off)
        }
        // This drawer goes stale after a leave: close it too
        parentFragmentManager.setFragmentResultListener(SwipeLeaveSheet.REQ_LEFT, this) { _, _ ->
            try {
                dismiss()
            } catch (_: Exception) {}
        }
        parentFragmentManager.setFragmentResultListener(REQ_LEAVE_TO_ARCHIVE, this) { _, _ ->
            SwipeLeaveSheet.newInstance(personId, name)
                .show(parentFragmentManager, SwipeLeaveSheet.TAG)
        }
        // Per-person currency (owner-only; joined users see the owner's pick)
        val btnCurrency = view.findViewById<MaterialButton>(R.id.btnPersonCurrency)
        if (!isOwner) {
            btnCurrency.visibility = View.GONE
        } else {
            btnCurrency.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                PersonCurrencySheet.newInstance(personId)
                    .show(parentFragmentManager, PersonCurrencySheet.TAG)
            }
        }
        // Synced currency mirrors to the cloud: grey + disable while offline
        applyCurrencyOnlineState(view, personId)
        // Left persons are edited via the Edit-copy pill, not here
        val leftGroup = try {
            LocalStore.people(ctx).optJSONObject(personId)?.optBoolean("leftGroup", false) == true
        } catch (_: Exception) { false }
        if (leftGroup) btnEdit.visibility = View.GONE
        // Joined viewers/writers never see Call while share-as-me is on
        // (the number shown would be the owner's private one)
        if (role != "owner" && ShareSync.isCloudPerson(ctx, personId) &&
            ShareSync.shareAsMe(ctx, personId)
        ) {
            view.findViewById<View>(R.id.btnCall).visibility = View.GONE
            view.findViewById<View>(R.id.callSpace).visibility = View.GONE
        }
        // "Share as me" display for joined accounts: person appears as the owner,
        // owner email under the name and the ACTUAL person name underneath
        if (ShareSync.isCloudPerson(ctx, personId) && role != "owner" && ShareSync.shareAsMe(ctx, personId)) {
            val personObj = LocalStore.people(ctx).optJSONObject(personId)
            val ownerPhoto = personObj?.optString("ownerPhotoUrl", "") ?: ""
            val ownerName = personObj?.optString("ownerName", "") ?: ""
            val ownerEmail = personObj?.optString("ownerEmail", "") ?: ""
            view.findViewById<TextView>(R.id.tvNetPersonName).text = ownerName.ifBlank { name }
            val ivAvatar = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivNetAvatar)
            val tvInitial = view.findViewById<TextView>(R.id.tvNetAvatarInitial)
            if (ownerPhoto.isNotBlank()) {
                ivAvatar.visibility = View.VISIBLE
                ivAvatar.load(ownerPhoto)
                tvInitial.visibility = View.GONE
            }
            // owner email + real person name under the display name
            // (the arg `name` is already the owner display name - take the real
            // name straight from the local person record)
            val realName = personObj?.optString("name", "") ?: ""
            val ownerEmailLine = view.findViewById<TextView>(R.id.ownerEmailLine)
            if (ownerEmail.isNotBlank()) {
                ownerEmailLine.text = ownerEmail
                ownerEmailLine.visibility = View.VISIBLE
            }
            val realPersonLine = view.findViewById<TextView>(R.id.realPersonLine)
            if (realName.isNotBlank() && realName != ownerName) {
                realPersonLine.text = realName
                realPersonLine.visibility = View.VISIBLE
            }
            ownerRow.visibility = View.GONE
        }
        if (readOnlyShared) {
            btnEdit.isEnabled = false
        }
    }

    // Returns owner (name, email, photoUrl) when current user is a joined user
    private fun Shared_ownerLabel(ctx: android.content.Context, personId: String): Triple<String, String, String>? {
        val role = ShareSync.accessRole(ctx, personId)
        val isCloud = ShareSync.isCloudPerson(ctx, personId)
        if (!isCloud || role == "owner") return null
        val o = LocalStore.people(ctx).optJSONObject(personId) ?: return null
        val name = o.optString("ownerName", "")
        val email = o.optString("ownerEmail", "")
        val photo = o.optString("ownerPhotoUrl", "")
        return Triple(name.ifBlank { "Owner" }, email, photo)
    }

    private fun showAccessManagement(ctx: android.content.Context, personId: String) {
        ShareSync.listAccess(ctx, personId) { users ->
            activity?.runOnUiThread {
                val sb = StringBuilder()
                users.forEach { m ->
                    val label = if (m.name.isNotBlank() && m.email.isNotBlank()) "${m.name} (${m.email})"
                        else m.name.ifBlank { m.uid }
                    sb.append("• ").append(label).append(": ").append(m.role).append("\n")
                }
                if (sb.isEmpty()) sb.append("No users have access yet.\nShare this person to add one.")
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Access management")
                    .setMessage(sb.toString())
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Refresh", null)
                    .show()
                // Action buttons (read/write/revoke) shown below via simple flow:
                choosePermissionAction(ctx, personId)
            }
        }
    }

    // Minimal UI to change/revoke: pick from current users
    private fun choosePermissionAction(ctx: android.content.Context, personId: String) {
        ShareSync.listAccess(ctx, personId) { users ->
            activity?.runOnUiThread {
                val others = users.filter { it.role != "owner" }
                if (others.isEmpty()) {
                    Toast.makeText(ctx, "Only you (owner) so far", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val items = others.map {
                    val label = if (it.name.isNotBlank()) it.name else it.uid
                    "$label (${it.role})"
                }.toTypedArray()
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Choose user to manage")
                    .setItems(items) { _, which ->
                        val target = others[which].uid
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Set permission for $target")
                            .setItems(arrayOf("Read Only", "Read + Write", "Revoke access")) { _, w ->
                                when (w) {
                                    0 -> ShareSync.setPermission(ctx, personId, target, "read") { ok ->
                                        if (ok) Toast.makeText(ctx, "Permission updated", Toast.LENGTH_SHORT).show()
                                    }
                                    1 -> ShareSync.setPermission(ctx, personId, target, "write") { ok ->
                                        if (ok) Toast.makeText(ctx, "Permission updated", Toast.LENGTH_SHORT).show()
                                    }
                                    else -> ShareSync.revokeAccess(ctx, personId, target) { ok ->
                                        if (ok) Toast.makeText(ctx, "Access revoked", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .show()
                    }
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        rootView = null
        super.onDestroyView()
    }

    private fun applyActionState(view: View, online: Boolean) {
        val btnArchive = view.findViewById<MaterialButton>(R.id.btnArchive)
        val btnEdit = view.findViewById<MaterialButton>(R.id.btnEdit)
        val grey = !online || archived
        if (lastGreyed == grey) return
        lastGreyed = grey

        if (online) {
            UiUtils.crossfade(btnArchive) {
                btnArchive.isEnabled = true
                btnArchive.backgroundTintList = archiveOriginalBg
                btnArchive.iconTint = archiveOriginalIcon
                btnArchive.setTextColor(archiveOriginalText)
            }
            UiUtils.crossfade(btnEdit) {
                btnEdit.isEnabled = true
                btnEdit.backgroundTintList = editOriginalBg
                btnEdit.iconTint = editOriginalIcon
                btnEdit.setTextColor(editOriginalText)
            }
            return
        }

        UiUtils.crossfade(btnArchive) {
            // Stay clickable while greyed so taps can trigger a connection retry
            btnArchive.isEnabled = !archived
            btnArchive.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.outline_variant))
            btnArchive.iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.grey_soft))
            btnArchive.setTextColor(requireContext().getColor(R.color.grey_soft))
        }
        UiUtils.crossfade(btnEdit) {
            btnEdit.isEnabled = !archived
            btnEdit.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.outline_variant))
            btnEdit.iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.grey_soft))
            btnEdit.setTextColor(requireContext().getColor(R.color.grey_soft))
        }
    }

    companion object {
        private const val REQ_LEAVE_TO_ARCHIVE = "leave_to_archive"
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_NAME = "name"
        private const val ARG_MOBILE = "mobile"
        private const val ARG_NET = "net"
        private const val ARG_RECEIVED = "received"
        private const val ARG_GAVE = "gave"
        private const val ARG_RECEIVED_COUNT = "receivedCount"
        private const val ARG_GAVE_COUNT = "gaveCount"
        const val TAG = "NetBalanceSheet"

        fun newInstance(
            personId: String,
            name: String,
            mobile: String,
            net: Long,
            received: Long,
            gave: Long,
            receivedCount: Int,
            gaveCount: Int,
            transactions: List<Transaction>,
            onArchived: () -> Unit,
            archived: Boolean = false
        ): NetBalanceSheet = NetBalanceSheet().apply {
            this.onArchived = onArchived
            this.archived = archived
            this.transactions = transactions
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putString(ARG_NAME, name)
                putString(ARG_MOBILE, mobile)
                putLong(ARG_NET, net)
                putLong(ARG_RECEIVED, received)
                putLong(ARG_GAVE, gave)
                putInt(ARG_RECEIVED_COUNT, receivedCount)
                putInt(ARG_GAVE_COUNT, gaveCount)
            }
        }
    }
}
