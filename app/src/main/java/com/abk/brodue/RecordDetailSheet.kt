package com.abk.brodue

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import coil.load

class RecordDetailSheet : BaseSheet() {

    private var rootView: View? = null
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private var readOnly = false
    private var editOriginalBg: ColorStateList? = null
    private var editOriginalIcon: ColorStateList? = null
    private var lastGreyed: Boolean? = null
    private var chipViews: Map<String, android.view.View> = emptyMap()
    private var selectedCategory: String = ""

    fun refreshForAppearanceChange() {
        if (!isAdded || rootView == null) return
        applyViewCardMode()
    }

    private fun viewCardOnlySelected(): Boolean =
        requireContext().getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("view_card_only_selected", false)

    private fun applyViewCardMode() {
        val onlySelected = viewCardOnlySelected()
        chipViews.forEach { (name, btn) ->
            btn.visibility = if (!onlySelected || name == selectedCategory) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_record_detail, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view
        val args = requireArguments()
        val personId = args.getString(ARG_PERSON_ID).orEmpty()
        val tx = Transaction(
            id = args.getString(ARG_TX_ID).orEmpty(),
            category = args.getString(ARG_CATEGORY).orEmpty(),
            amount = args.getLong(ARG_AMOUNT),
            type = args.getString(ARG_TYPE) ?: "gave",
            note = args.getString(ARG_NOTE).orEmpty(),
            createdAt = args.getLong(ARG_DATE),
            savedAt = args.getLong(ARG_SAVED_AT)
        )
        val received = tx.type == "received"
        val color = ContextCompat.getColor(requireContext(), if (received) R.color.save_green else R.color.save_red)

        val creatorUid = try {
            LocalStore.transactions(requireContext()).optJSONObject(tx.id)?.optString("createdByUid", "").orEmpty()
        } catch (_: Exception) { "" }
        val isCloudTx = ShareSync.isCloudPerson(requireContext(), personId)
        fun showTxInfo() {
            TxInfoSheet.newInstance(personId, tx.savedAt, creatorUid)
                .show(parentFragmentManager, TxInfoSheet.TAG)
        }

        val personName = args.getString(ARG_PERSON_NAME)
        if (personName != null) {
            view.findViewById<View>(R.id.rdTitle).visibility = View.GONE
            view.findViewById<View>(R.id.rdPersonRow).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.rdPersonName).text = personName
            val avatar = view.findViewById<TextView>(R.id.rdPersonAvatar)
            avatar.text = personName.trim().take(1).uppercase()
            AvatarColors.style(requireContext(), avatar, personName)
            // "i" info button (Logs path; local shows Created-at only)
            val btnRdInfo = view.findViewById<View>(R.id.btnRdInfo)
            btnRdInfo.visibility = View.VISIBLE
            btnRdInfo.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                showTxInfo()
            }
        } else {
            view.findViewById<TextView>(R.id.rdTitle).setText(if (received) R.string.receive else R.string.send)
            // "i" info button (People page path; local shows Created-at only)
            val btnRdInfoTitle = view.findViewById<View>(R.id.btnRdInfoTitle)
            btnRdInfoTitle.visibility = View.VISIBLE
            btnRdInfoTitle.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                showTxInfo()
            }
        }
        val tvAmount = view.findViewById<TextView>(R.id.rdAmount)
        tvAmount.text = android.text.TextUtils.concat(
            if (received) "+" else "-",
            Formatters.amountWithSymbol(
                tx.amount, CurrencyManager.symbolForPerson(requireContext(), personId)
            )
        )
        tvAmount.setTextColor(color)

        val chips = mutableMapOf(
            "Online" to view.findViewById<MaterialButton>(R.id.chipRdOnline),
            "Recharge" to view.findViewById<MaterialButton>(R.id.chipRdRecharge),
            "Cash" to view.findViewById<MaterialButton>(R.id.chipRdCash),
            "Item" to view.findViewById<MaterialButton>(R.id.chipRdItem),
            "Roundoff" to view.findViewById<MaterialButton>(R.id.chipRdRoundoff)
        )
        // Show all custom categories (like MoneySheet) with correct colors and border
        val customAll = CustomCategoryStore.getAll(requireContext())
        customAll.forEach { custom ->
            if (custom.name !in chips) {
                val flow = view.findViewById<FlowLayout>(R.id.rdCategoryFlow)
                val ctx = requireContext()
                val iconRes = CustomCategoryStore.iconResId(ctx, custom.iconResName)
                val btn = MaterialButton(ctx).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (44 * ctx.resources.displayMetrics.density).toInt()
                    ).apply {
                        val m = (4 * ctx.resources.displayMetrics.density).toInt()
                        setMargins(m, m, m, m)
                    }
                    text = custom.name
                    isClickable = false
                    isFocusable = false
                    insetTop = 0
                    insetBottom = 0
                    minimumWidth = 0
                    setPadding(
                        (15 * ctx.resources.displayMetrics.density).toInt(), 0,
                        (15 * ctx.resources.displayMetrics.density).toInt(), 0
                    )
                    cornerRadius = (50 * ctx.resources.displayMetrics.density).toInt()
                    elevation = 0f
                    icon = ContextCompat.getDrawable(ctx, iconRes)
                    iconSize = (15 * ctx.resources.displayMetrics.density).toInt()
                    iconPadding = (6 * ctx.resources.displayMetrics.density).toInt()
                    stateListAnimator = null
                    strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                }
                // Assign distinct color via hash
                val colorOptions = listOf(
                    R.color.cat_blue to R.color.cat_blue_soft,
                    R.color.cat_amber to R.color.cat_amber_soft,
                    R.color.cat_green to R.color.cat_green_soft,
                    R.color.cat_teal to R.color.cat_teal_soft,
                    R.color.cat_purple to R.color.cat_purple_soft
                )
                val idx = Math.abs(custom.name.hashCode()) % colorOptions.size
                // Store for later use in forEach via chipColors-like logic, but we will handle in forEach below
                flow.addView(btn)
                chips[custom.name] = btn
            }
        }
        // Handle current tx's category if it's custom but not in store (e.g., legacy or deleted)
        // For deleted custom, use ic_custom_28 and red as requested
        if (tx.category.isNotBlank() && tx.category !in chips) {
            val flow = view.findViewById<FlowLayout>(R.id.rdCategoryFlow)
            val ctx = requireContext()
            val isDeletedCustom = tx.category !in listOf("Online", "Recharge", "Cash", "Item", "Roundoff") &&
                CustomCategoryStore.getAll(ctx).none { it.name == tx.category }
            val iconRes = if (isDeletedCustom) R.drawable.ic_custom_28 else
                CustomCategoryStore.getAll(ctx).find { it.name == tx.category }?.let {
                    CustomCategoryStore.iconResId(ctx, it.iconResName)
                } ?: R.drawable.ic_custom_01
            val btn = MaterialButton(ctx).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (44 * ctx.resources.displayMetrics.density).toInt()
                ).apply {
                    val m = (4 * ctx.resources.displayMetrics.density).toInt()
                    setMargins(m, m, m, m)
                }
                text = tx.category
                isClickable = false
                isFocusable = false
                insetTop = 0
                insetBottom = 0
                minimumWidth = 0
                setPadding(
                    (15 * ctx.resources.displayMetrics.density).toInt(), 0,
                    (15 * ctx.resources.displayMetrics.density).toInt(), 0
                )
                cornerRadius = (50 * ctx.resources.displayMetrics.density).toInt()
                elevation = 0f
                icon = ContextCompat.getDrawable(ctx, iconRes)
                iconSize = (15 * ctx.resources.displayMetrics.density).toInt()
                iconPadding = (6 * ctx.resources.displayMetrics.density).toInt()
                stateListAnimator = null
                strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                // Tag to identify deleted for later styling
                tag = if (isDeletedCustom) "deleted" else null
            }
            flow.addView(btn)
            chips[tx.category] = btn
        }
        // Hide hidden defaults (deleted via manage)
        val hidden = CustomCategoryStore.getHidden(requireContext())
        hidden.forEach { h ->
            chips[h]?.let { b ->
                b.visibility = View.GONE
            }
            chips.remove(h)
        }
        // Apply saved order from MoneySheet for consistency
        run {
            val flow = view.findViewById<FlowLayout>(R.id.rdCategoryFlow)
            val ordered = CategoryOrderStore.applyOrder(requireContext(), chips.keys.toList())
            ordered.forEach { name ->
                chips[name]?.let { btn ->
                    flow.removeView(btn)
                    flow.addView(btn)
                }
            }
        }
        chips.forEach { (name, btn) ->
            val isDeletedForTx = name == tx.category && name !in listOf("Online","Recharge","Cash","Item","Roundoff") &&
                customAll.none { it.name == name } && CustomCategoryStore.getAll(requireContext()).none { it.name == name }
            val (cRes, sRes, iRes) = when {
                isDeletedForTx -> Triple(R.color.negative, R.color.negative_container, R.drawable.ic_custom_28)
                else -> {
                    val isCustom = customAll.any { it.name == name } || (tx.category == name && name !in listOf("Online","Recharge","Cash","Item","Roundoff"))
                    if (isCustom) {
                        val custom = customAll.find { it.name == name } ?: CustomCategoryStore.getAll(requireContext()).find { it.name == name }
                        val icon = if (custom != null) CustomCategoryStore.iconResId(requireContext(), custom.iconResName) else R.drawable.ic_custom_01
                        val colorOptions = listOf(
                            R.color.cat_blue to R.color.cat_blue_soft,
                            R.color.cat_amber to R.color.cat_amber_soft,
                            R.color.cat_green to R.color.cat_green_soft,
                            R.color.cat_teal to R.color.cat_teal_soft,
                            R.color.cat_purple to R.color.cat_purple_soft
                        )
                        val idx = Math.abs(name.hashCode()) % colorOptions.size
                        val (cr, sr) = colorOptions[idx]
                        Triple(cr, sr, icon)
                    } else categoryStyle(name)
                }
            }
            btn.setIconResource(iRes)
            // Ensure border
            btn.strokeWidth = (1 * requireContext().resources.displayMetrics.density).toInt()
            if (name == tx.category) {
                val c = ContextCompat.getColor(requireContext(), cRes)
                btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), sRes)))
                btn.setTextColor(c)
                btn.setIconTint(ColorStateList.valueOf(c))
                btn.setStrokeColor(ColorStateList.valueOf(c))
            } else {
                btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.field_bg)))
                btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey_soft))
                btn.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.grey_soft)))
                btn.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.border_light)))
            }
        }
        chipViews = chips
        selectedCategory = tx.category
        applyViewCardMode()

        val noteGroup = view.findViewById<View>(R.id.rdNoteGroup)
        if (tx.note.isNotBlank()) {
            noteGroup.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.rdNoteBox).text = tx.note
        }

        view.findViewById<TextView>(R.id.tvRdDate).text = Formatters.date(tx.createdAt)
        view.findViewById<TextView>(R.id.tvRdTime).text = Formatters.time(tx.createdAt)

        // Show who created this entry (photo + name) - mandatory for shared persons.
        // Resolved straight from the database person doc so it works for every
        // viewer (owner/member) and survives restarts/installs.
        val rdCreatorRow = view.findViewById<View>(R.id.rdCreatorRow)
        val rdCreatorImg = view.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.rdCreatorImg)
        val rdCreatedBy = view.findViewById<TextView>(R.id.rdCreatedBy)
        try {
            if (isCloudTx) {
                val selfUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                val isSelf = creatorUid.isNotBlank() && creatorUid == selfUid
                fun showCreator(name: String, photo: String) {
                    rdCreatedBy.text = if (isSelf) "You" else name
                    if (photo.isNotBlank()) {
                        rdCreatorImg.load(photo)
                    } else {
                        rdCreatorImg.setImageResource(0)
                        rdCreatorImg.background = ContextCompat.getDrawable(
                            requireContext(), R.drawable.bg_circle
                        )
                        rdCreatorImg.alpha = 0.4f
                    }
                    if (rdCreatorRow.visibility != View.VISIBLE) {
                        rdCreatorRow.visibility = View.VISIBLE
                    }
                }
                // Instant from the local mirror, then silent DB refresh in
                // case the mirror is stale (same values = no visible change)
                ShareSync.creatorProfile(requireContext(), personId, creatorUid)?.let { (name, _, photo) ->
                    if (name.isNotBlank()) activity?.runOnUiThread { showCreator(name, photo) }
                }
                ShareSync.resolveCreatorRaw(requireContext(), personId, creatorUid) { name, _, photo ->
                    activity?.runOnUiThread { showCreator(name, photo) }
                }
            }
        } catch (_: Exception) {}

        view.findViewById<View>(R.id.btnEditRecord).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // Locked explains itself instead of opening a doomed editor
            if (ShareSync.isSynced(requireContext(), personId) &&
                ShareSync.syncedCount(requireContext()) > ShareSync.cachedSyncCap(requireContext())
            ) {
                SyncPausedSheet.newInstance().show(parentFragmentManager, SyncPausedSheet.TAG)
                return@setOnClickListener
            }
            // Synced entries need a connection first; local ones edit offline
            if (ShareSync.isSynced(requireContext(), personId) &&
                !NetworkUtils.retryConnection(requireContext())
            ) return@setOnClickListener
            val act = requireActivity() as androidx.fragment.app.FragmentActivity
            TaskLockGuard.gate(act, "edit_entry", "Edit Entry") {
                dismiss()
                MoneySheet.newInstanceForEdit(personId, tx).show(parentFragmentManager, MoneySheet.TAG)
            }
        }

        val btnEdit = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditRecord)
        // Joined (shared) users cannot edit entries - owner-only.
        // Archived inspect has no edit button at all (not even greyed).
        // Over-limit lockdown greys it dead for everyone (server denies anyway).
        val role = ShareSync.accessRole(requireContext(), personId)
        val isJoined = ShareSync.isSynced(requireContext(), personId) && role != "owner"
        val locked = ShareSync.isSynced(requireContext(), personId) &&
            ShareSync.syncedCount(requireContext()) > ShareSync.cachedSyncCap(requireContext())
        if (isJoined || readOnly) btnEdit.visibility = View.GONE
        editOriginalBg = btnEdit.backgroundTintList
        editOriginalIcon = btnEdit.iconTint
        lastGreyed = false
        // Offline grey applies to synced persons only; local edits work offline
        fun effectiveOnline(online: Boolean): Boolean =
            (online || !ShareSync.isSynced(requireContext(), personId)) && !locked
        if (!effectiveOnline(ConnectivityMonitor.online) || readOnly) applyEditState(view, false)
        if (locked) btnEdit.isEnabled = false
        connectivityListener = { online -> applyEditState(view, effectiveOnline(online)) }
        ConnectivityMonitor.addListener(connectivityListener!!)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        try {
            // Viewed: clear the entry dot, maybe the person dot, repaint rows
            val txId = requireArguments().getString(ARG_TX_ID).orEmpty()
            val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
            if (txId.isNotBlank()) {
                LocalStore.transactions(requireContext()).optJSONObject(txId)?.put("unseen", false)
                LocalStore.persist(requireContext())
                (activity as? MainActivity)?.let {
                    if (personId.isNotBlank()) it.refreshPersonDot(personId)
                    it.repaintTxDots()
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        rootView = null
        super.onDestroyView()
    }

    private fun applyEditState(view: View, online: Boolean) {
        val btnEdit = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditRecord)
        val grey = !online || readOnly
        if (lastGreyed == grey) return
        lastGreyed = grey

        if (online) {
            UiUtils.crossfade(btnEdit) {
                btnEdit.isEnabled = true
                btnEdit.backgroundTintList = editOriginalBg
                btnEdit.iconTint = editOriginalIcon
            }
            return
        }
        UiUtils.crossfade(btnEdit) {
            // Stay clickable while greyed so taps can trigger a connection retry
            btnEdit.isEnabled = !readOnly
            btnEdit.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.outline_variant))
            btnEdit.iconTint = ColorStateList.valueOf(requireContext().getColor(R.color.grey_soft))
        }
    }

    private fun categoryStyle(category: String): Triple<Int, Int, Int> = when (category) {
        "Online" -> Triple(R.color.cat_blue, R.color.cat_blue_soft, R.drawable.ic_custom_01)
        "Recharge" -> Triple(R.color.cat_amber, R.color.cat_amber_soft, R.drawable.ic_custom_02)
        "Cash" -> Triple(R.color.cat_green, R.color.cat_green_soft, R.drawable.ic_custom_03)
        "Item" -> Triple(R.color.cat_teal, R.color.cat_teal_soft, R.drawable.ic_custom_04)
        "Roundoff" -> Triple(R.color.cat_purple, R.color.cat_purple_soft, R.drawable.ic_custom_27)
        else -> Triple(R.color.cat_purple, R.color.cat_purple_soft, R.drawable.ic_custom_01)
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_TX_ID = "txId"
        private const val ARG_CATEGORY = "category"
        private const val ARG_AMOUNT = "amount"
        private const val ARG_TYPE = "type"
        private const val ARG_NOTE = "note"
        private const val ARG_DATE = "date"
        private const val ARG_SAVED_AT = "savedAt"
        private const val ARG_PERSON_NAME = "personName"
        const val TAG = "RecordDetailSheet"

        fun newInstance(tx: Transaction, personId: String, personName: String? = null, readOnly: Boolean = false): RecordDetailSheet = RecordDetailSheet().apply {
            this.readOnly = readOnly
            arguments = Bundle().apply {
                putString(ARG_PERSON_ID, personId)
                putString(ARG_TX_ID, tx.id)
                putString(ARG_CATEGORY, tx.category)
                putLong(ARG_AMOUNT, tx.amount)
                putString(ARG_TYPE, tx.type)
                putString(ARG_NOTE, tx.note)
                putLong(ARG_DATE, tx.createdAt)
                putLong(ARG_SAVED_AT, tx.savedAt)
                putString(ARG_PERSON_NAME, personName)
            }
        }
    }
}