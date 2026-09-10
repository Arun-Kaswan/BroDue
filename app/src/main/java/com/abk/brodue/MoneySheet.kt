package com.abk.brodue

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MoneySheet : BaseSheet() {

    override val fullScreen: Boolean = true
    override val lockSwipeWhileKeyboardOpen: Boolean = true

    private lateinit var etAmount: EditText
    private lateinit var etNote: EditText
    private lateinit var tvTitle: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView
    private lateinit var amountBox: View
    private lateinit var btnSave: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var imgSavePlus: ImageView
    private lateinit var chips: MutableMap<String, MaterialButton>
    private lateinit var chipColors: MutableMap<String, Pair<Int, Int>>

    private var direction = "received"
    private var selectedCategory = "Online"
    private var isSaving = false
    private var editTxId: String? = null
    private var savedAt = 0L
    private var timePicked = false
    private var dateMs = 0L
    private var timeMs = 0L
    private var ignoreText = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_money, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val personId = args.getString(ARG_PERSON_ID).orEmpty()
        direction = args.getString(ARG_DIRECTION) ?: "received"
        editTxId = args.getString(ARG_TX_ID)

        etAmount = view.findViewById(R.id.etAmount)
        etNote = view.findViewById(R.id.etNote)
        tvTitle = view.findViewById(R.id.tvMoneyTitle)
        tvDate = view.findViewById(R.id.tvDate)
        tvTime = view.findViewById(R.id.tvTime)
        amountBox = view.findViewById(R.id.amountBox)
        view.findViewById<TextView>(R.id.tvCurrencySymbol)?.let {
            it.text = CurrencyManager.symbolForPerson(requireContext(), personId)
        }
        btnSave = view.findViewById(R.id.btnSaveMoney)
        btnDelete = view.findViewById(R.id.btnDeleteMoney)
        imgSavePlus = view.findViewById(R.id.imgSavePlus)
        val defaults = listOf(
            Triple("Online", R.id.chipOnline, R.color.cat_blue to R.color.cat_blue_soft),
            Triple("Recharge", R.id.chipRecharge, R.color.cat_amber to R.color.cat_amber_soft),
            Triple("Cash", R.id.chipCash, R.color.cat_green to R.color.cat_green_soft),
            Triple("Item", R.id.chipItem, R.color.cat_teal to R.color.cat_teal_soft),
            Triple("Roundoff", R.id.chipRoundoff, R.color.cat_purple to R.color.cat_purple_soft)
        )
        chips = mutableMapOf()
        chipColors = mutableMapOf()
        defaults.forEach { (name, id, colors) ->
            val btn = view.findViewById<MaterialButton>(id)
            if (CustomCategoryStore.isHidden(requireContext(), name)) {
                btn.visibility = View.GONE
            } else {
                chips[name] = btn
                chipColors[name] = colors
            }
        }
        // If default selected (Online) was deleted, pick first visible
        if (selectedCategory !in chips) {
            selectedCategory = chips.keys.firstOrNull() ?: CustomCategoryStore.getAll(requireContext()).firstOrNull()?.name ?: "Online"
        }

        val now = Calendar.getInstance()
        dateMs = startOfDay(now).timeInMillis
        timeMs = now.get(Calendar.HOUR_OF_DAY) * 3600000L + now.get(Calendar.MINUTE) * 60000L

        if (editTxId != null) {
            savedAt = args.getLong(ARG_SAVED_AT)
            val cat = args.getString(ARG_CATEGORY).orEmpty()
            if (cat.isNotBlank()) selectedCategory = cat
            val amount = args.getLong(ARG_AMOUNT)
            if (amount > 0) etAmount.setText(Money.formatPaise(amount))
            etNote.setText(args.getString(ARG_NOTE).orEmpty())
            val createdAt = args.getLong(ARG_CREATED_AT)
            if (createdAt > 0) {
                val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
                dateMs = startOfDay(cal).timeInMillis
                val tod = cal.get(Calendar.HOUR_OF_DAY) * 3600000L + cal.get(Calendar.MINUTE) * 60000L
                timeMs = tod
                timePicked = Math.abs(tod - timeMsOf(now)) > 60000L
            }
            val tvSavedAt = view.findViewById<TextView>(R.id.tvSavedAt)
            if (savedAt > 0) {
                tvSavedAt.visibility = View.VISIBLE
                tvSavedAt.text = "${Formatters.date(savedAt)} · ${Formatters.time(savedAt)}"
            }
            btnDelete.visibility = View.VISIBLE
        }

        etAmount.addTextChangedListener(amountWatcher)
        etAmount.setOnFocusChangeListener { _, focused ->
            amountBox.background = ContextCompat.getDrawable(
                requireContext(),
                if (focused) R.drawable.bg_field_focus else R.drawable.bg_field
            )
        }

        view.findViewById<View>(R.id.dateBox).setOnClickListener { openDatePicker() }
        view.findViewById<View>(R.id.timeBox).setOnClickListener { openTimePicker() }

        chips.forEach { (name, btn) ->
            btn.setOnClickListener {
                selectedCategory = name
                updateChips()
            }
            enableChipDrag(btn, name, view)
        }

        // Load existing custom categories synchronously from local cache first (no pop-in)
        CustomCategoryStore.getAll(requireContext()).forEach { c ->
            if (!chips.containsKey(c.name)) {
                addCustomChip(view, c.name, c.iconResName, select = c.name == selectedCategory)
            }
        }
        if (editTxId != null && selectedCategory !in chips) {
            val isDeleted = selectedCategory !in listOf("Online","Recharge","Cash","Item","Roundoff") &&
                CustomCategoryStore.getAll(requireContext()).none { it.name == selectedCategory }
            if (!isDeleted) {
                addCustomChip(view, selectedCategory, "ic_custom_01", select = true)
            }
        }
        if (selectedCategory !in chips) {
            selectedCategory = chips.keys.firstOrNull() ?: "Online"
        }

        // Apply saved order
        applyCategoryOrder(view)

        // Custom category + button
        view.findViewById<View>(R.id.chipAdd).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            CustomCategorySheet.newInstance().show(parentFragmentManager, CustomCategorySheet.TAG)
        }
        // Make FlowLayout a drop target
        setupFlowDrag(view)
        // Listen for new custom category
        parentFragmentManager.setFragmentResultListener(CustomCategorySheet.REQ_KEY, this) { _, bundle ->
            val name = bundle.getString("name") ?: return@setFragmentResultListener
            val icon = bundle.getString("icon") ?: "ic_custom_01"
            addCustomChip(view, name, icon, select = true)
            updateChips()
        }
        parentFragmentManager.setFragmentResultListener(ManageCategoriesSheet.REQ_DELETED, this) { _, bundle ->
            val name = bundle.getString("name") ?: return@setFragmentResultListener
            chips[name]?.let { btn ->
                // For custom, remove view; for default, hide
                val isDefault = name in listOf("Online", "Recharge", "Cash", "Item", "Roundoff")
                if (isDefault) {
                    btn.visibility = View.GONE
                } else {
                    (btn.parent as? ViewGroup)?.removeView(btn)
                }
                chips.remove(name)
                chipColors.remove(name)
                if (selectedCategory == name) {
                    selectedCategory = chips.keys.firstOrNull() ?: "Online"
                    updateChips()
                }
            } ?: run {
                if (selectedCategory == name) {
                    selectedCategory = chips.keys.firstOrNull() ?: "Online"
                    updateChips()
                }
            }
        }
        parentFragmentManager.setFragmentResultListener(ManageCategoriesSheet.REQ_RESTORED, this) { _, bundle ->
            val name = bundle.getString("name") ?: return@setFragmentResultListener
            val icon = bundle.getString("icon") ?: "ic_custom_01"
            restoreCategoryChip(name, icon)
        }
        // Sync from Firebase in background — only update if something actually changed
        CustomCategoryStore.syncFromFirebase(requireContext()) {
            var changed = false
            CustomCategoryStore.getAll(requireContext()).forEach { c ->
                if (!chips.containsKey(c.name)) {
                    addCustomChip(view, c.name, c.iconResName, select = c.name == selectedCategory)
                    changed = true
                }
            }
            // If editing with custom category not yet loaded (offline) or deleted, ensure it exists
            if (editTxId != null && selectedCategory !in chips) {
                val isDeleted = selectedCategory !in listOf("Online","Recharge","Cash","Item","Roundoff") &&
                    CustomCategoryStore.getAll(requireContext()).none { it.name == selectedCategory }
                if (isDeleted) {
                    // Deleted custom: show with ic_custom_28 and red
                    val flow = view.findViewById<FlowLayout>(R.id.categoryFlow)
                    val addBtn = view.findViewById<View>(R.id.chipAdd)
                    val ctx = requireContext()
                    val btn = com.google.android.material.button.MaterialButton(ctx).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, (44 * ctx.resources.displayMetrics.density).toInt()
                        ).apply {
                            val m = (4 * ctx.resources.displayMetrics.density).toInt()
                            setMargins(m, m, m, m)
                        }
                        text = selectedCategory
                        textSize = 14f
                        isAllCaps = false
                        insetTop = 0
                        insetBottom = 0
                        minimumWidth = 0
                        setPadding((15 * ctx.resources.displayMetrics.density).toInt(), 0, (15 * ctx.resources.displayMetrics.density).toInt(), 0)
                        cornerRadius = (50 * ctx.resources.displayMetrics.density).toInt()
                        elevation = 0f
                        icon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_custom_28)
                        iconSize = (15 * ctx.resources.displayMetrics.density).toInt()
                        iconPadding = (6 * ctx.resources.displayMetrics.density).toInt()
                        strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, com.abk.brodue.R.animator.btn_press_scale)
                    }
                    chips[selectedCategory] = btn
                    chipColors[selectedCategory] = R.color.negative to R.color.negative_container
                    val idx = flow.indexOfChild(addBtn).takeIf { it >= 0 } ?: flow.childCount
                    flow.addView(btn, idx)
                    btn.setOnClickListener { selectedCategory = btn.text.toString(); updateChips() }
                } else {
                    addCustomChip(view, selectedCategory, "ic_custom_01", select = true)
                }
            }
            if (selectedCategory !in chips) {
                selectedCategory = chips.keys.firstOrNull() ?: "Online"
            }
            if (changed) applyCategoryOrder(view)
            updateChips()
        }

        btnSave.setOnClickListener { save(personId) }
        // Delete asks for a swipe confirmation first
        parentFragmentManager.setFragmentResultListener(
            SwipeDeleteTxSheet.REQ_DELETE_TX, this
        ) { _, bundle ->
            if (!isAdded) return@setFragmentResultListener
            if (bundle.getString("txId") != editTxId) return@setFragmentResultListener
            delete(bundle.getString("personId") ?: personId)
        }
        btnDelete.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val txId = editTxId ?: return@setOnClickListener
            val amount = requireArguments().getLong(ARG_AMOUNT)
            val type = requireArguments().getString(ARG_DIRECTION) ?: "gave"
            val sym = CurrencyManager.symbolForPerson(requireContext(), personId)
            val sign = if (type == "received") "+" else "-"
            val summary = "$sign${Formatters.amountWithSymbol(amount, sym)} · " +
                "${requireArguments().getString(ARG_CATEGORY).orEmpty()}"
            SwipeDeleteTxSheet.newInstance(personId, txId, summary)
                .show(parentFragmentManager, SwipeDeleteTxSheet.TAG)
        }

        updateDirectionUi()
        updateChips()
        updateDateLabel()
        updateTimeLabel()

        etAmount.requestFocus()
        etAmount.post {
            requireDialog().window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }

    // Decimal-aware watcher: digits + one dot, max 2 fraction digits,
    // max 9 integer digits. (No live thousand-grouping - it fights the dot.)
    private val amountWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            if (ignoreText) return
            ignoreText = true
            var t = s.toString().filter { it.isDigit() || it == '.' }
            val firstDot = t.indexOf('.')
            if (firstDot >= 0) {
                t = t.substring(0, firstDot + 1) + t.substring(firstDot + 1).replace(".", "")
            }
            val dot = t.indexOf('.')
            if (dot >= 0 && t.length - dot - 1 > 2) t = t.substring(0, dot + 3)
            val intPart = t.substringBefore('.')
            if (intPart.length > 9) {
                t = intPart.take(9) + if (dot >= 0) t.substring(dot) else ""
            }
            if (t.length > 1 && t.startsWith("0") && t[1] != '.') {
                t = t.trimStart('0').ifEmpty { "0" }
            }
            if (t != s.toString()) {
                s.replace(0, s.length, t)
                try {
                    etAmount.setSelection(s.length.coerceAtMost(t.length))
                } catch (_: Exception) {}
            }
            ignoreText = false
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }

    private fun updateDirectionUi() {
        val isReceived = direction == "received"
        tvTitle.setText(if (isReceived) R.string.receive else R.string.send)
        btnSave.setText(if (editTxId != null) R.string.save else if (isReceived) R.string.receive else R.string.send)
        val color = ContextCompat.getColor(requireContext(), if (isReceived) R.color.save_green else R.color.save_red)
        val soft = ContextCompat.getColor(requireContext(), if (isReceived) R.color.save_green_soft else R.color.save_red_soft)
        btnSave.setBackgroundTintList(ColorStateList.valueOf(soft))
        btnSave.setTextColor(color)
        imgSavePlus.setColorFilter(color)
    }

    private fun updateChips() {
        chips.forEach { (name, btn) ->
            val selected = name == selectedCategory
            val pair = chipColors[name] ?: (R.color.cat_purple to R.color.cat_purple_soft)
            val (colorRes, softRes) = pair
            if (selected) {
                val color = ContextCompat.getColor(requireContext(), colorRes)
                btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), softRes)))
                btn.setTextColor(color)
                btn.setIconTint(ColorStateList.valueOf(color))
                btn.setStrokeColor(ColorStateList.valueOf(color))
            } else {
                btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.field_bg)))
                btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey_soft))
                btn.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.grey_soft)))
                btn.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.border_light)))
            }
        }
    }

    private fun addCustomChip(root: View, name: String, iconResName: String, select: Boolean = false) {
        if (chips.containsKey(name)) {
            if (select) { selectedCategory = name; updateChips() }
            return
        }
        val ctx = requireContext()
        val flow = root.findViewById<FlowLayout>(R.id.categoryFlow)
        val addBtn = root.findViewById<View>(R.id.chipAdd)
        val btn = MaterialButton(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (44 * resources.displayMetrics.density).toInt()
            ).apply {
                val m = (4 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
            text = name
            textSize = 14f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minimumWidth = 0
            setPadding(
                (15 * resources.displayMetrics.density).toInt(),
                0,
                (15 * resources.displayMetrics.density).toInt(),
                0
            )
            cornerRadius = (50 * resources.displayMetrics.density).toInt()
            elevation = 0f
            icon = ContextCompat.getDrawable(ctx, CustomCategoryStore.iconResId(ctx, iconResName))
            iconSize = (15 * resources.displayMetrics.density).toInt()
            iconPadding = (6 * resources.displayMetrics.density).toInt()
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(ctx, com.abk.brodue.R.animator.btn_press_scale)
        }
        val colorOptions = listOf(
            R.color.cat_blue to R.color.cat_blue_soft,
            R.color.cat_amber to R.color.cat_amber_soft,
            R.color.cat_green to R.color.cat_green_soft,
            R.color.cat_teal to R.color.cat_teal_soft,
            R.color.cat_purple to R.color.cat_purple_soft
        )
        val idxColor = Math.abs(name.hashCode()) % colorOptions.size
        chipColors[name] = colorOptions[idxColor]
        chips[name] = btn
        // Ensure border appears
        btn.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
        val idx = flow.indexOfChild(addBtn).takeIf { it >= 0 } ?: flow.childCount
        flow.addView(btn, idx)
        btn.setOnClickListener {
            selectedCategory = name
            updateChips()
        }
        if (select) {
            selectedCategory = name
        }
    }

    fun removeCategoryChip(name: String) {
        // Instant removal for delete from manage sheet
        chips[name]?.let { btn ->
            val isDefault = name in listOf("Online", "Recharge", "Cash", "Item", "Roundoff")
            if (isDefault) {
                btn.visibility = View.GONE
            } else {
                (btn.parent as? ViewGroup)?.removeView(btn)
            }
            chips.remove(name)
            chipColors.remove(name)
            if (selectedCategory == name) {
                selectedCategory = chips.keys.firstOrNull() ?: "Online"
                try { updateChips() } catch (_: Exception) {}
            }
        }
        // Also handle hidden default case where button was GONE but not in chips
        try {
            val idMap = mapOf(
                "Online" to R.id.chipOnline,
                "Recharge" to R.id.chipRecharge,
                "Cash" to R.id.chipCash,
                "Item" to R.id.chipItem,
                "Roundoff" to R.id.chipRoundoff
            )
            idMap[name]?.let { id ->
                view?.findViewById<View>(id)?.visibility = View.GONE
            }
            if (selectedCategory == name && !chips.containsKey(name)) {
                selectedCategory = chips.keys.firstOrNull() ?: "Online"
                try { updateChips() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun restoreCategoryChip(name: String, iconResName: String) {
        val idMap = mapOf(
            "Online" to (R.id.chipOnline to (R.color.cat_blue to R.color.cat_blue_soft)),
            "Recharge" to (R.id.chipRecharge to (R.color.cat_amber to R.color.cat_amber_soft)),
            "Cash" to (R.id.chipCash to (R.color.cat_green to R.color.cat_green_soft)),
            "Item" to (R.id.chipItem to (R.color.cat_teal to R.color.cat_teal_soft)),
            "Roundoff" to (R.id.chipRoundoff to (R.color.cat_purple to R.color.cat_purple_soft))
        )
        idMap[name]?.let { (id, colors) ->
            val btn = view?.findViewById<MaterialButton>(id) ?: return
            btn.visibility = View.VISIBLE
            chips[name] = btn
            chipColors[name] = colors
            try { updateChips() } catch (_: Exception) {}
            return
        }
        // For custom restored (if ever), re-add as custom chip
        try {
            val flow = view?.findViewById<FlowLayout>(R.id.categoryFlow) ?: return
            if (chips.containsKey(name)) return
            addCustomChip(requireView(), name, iconResName, select = false)
            updateChips()
        } catch (_: Exception) {}
    }

    private fun applyCategoryOrder(root: View) {
        val flow = root.findViewById<FlowLayout>(R.id.categoryFlow) ?: return
        val addBtn = root.findViewById<View>(R.id.chipAdd) ?: return
        val allNames = chips.keys.toList()
        val ordered = CategoryOrderStore.applyOrder(requireContext(), allNames)
        ordered.forEach { name ->
            chips[name]?.let { btn ->
                flow.removeView(btn)
                val idx = flow.indexOfChild(addBtn).takeIf { it >= 0 } ?: flow.childCount
                flow.addView(btn, idx)
            }
        }
    }

    private fun saveCurrentOrder(root: View) {
        val flow = root.findViewById<FlowLayout>(R.id.categoryFlow) ?: return
        val order = mutableListOf<String>()
        for (i in 0 until flow.childCount) {
            val child = flow.getChildAt(i)
            if (child.id == R.id.chipAdd) continue
            val entry = chips.entries.find { it.value === child }
            if (entry != null) order.add(entry.key)
        }
        if (order.isNotEmpty()) CategoryOrderStore.saveOrder(requireContext(), order)
    }

    private fun enableChipDrag(btn: View, name: String, root: View) {
        btn.setOnLongClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // Lift animation: scale up slightly + elevate
            v.animate()
                .scaleX(1.12f).scaleY(1.12f)
                .alpha(0.9f)
                .setDuration(140)
                .setInterpolator(UiUtils.EASE_OUT)
                .start()
            val data = android.content.ClipData.newPlainText("category", name)
            val shadow = View.DragShadowBuilder(v)
            v.startDragAndDrop(data, shadow, v, 0)
            true
        }
    }

    private fun setupFlowDrag(root: View) {
        val flow = root.findViewById<FlowLayout>(R.id.categoryFlow) ?: return
        val addBtn = root.findViewById<View>(R.id.chipAdd) ?: return
        var lastSwapTime = 0L
        flow.setOnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                    // Dim other chips while dragging
                    for (i in 0 until flow.childCount) {
                        val child = flow.getChildAt(i)
                        if (child !== event.localState) {
                            child.animate().alpha(0.55f).setDuration(150).setInterpolator(UiUtils.EASE_OUT).start()
                        }
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val dragged = event.localState as? View ?: return@setOnDragListener true
                    // Keep dragged view visually lifted
                    if (dragged.scaleX < 1.1f) {
                        dragged.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).start()
                    }
                    var target: View? = null
                    for (i in 0 until flow.childCount) {
                        val child = flow.getChildAt(i)
                        if (child === dragged || child === addBtn) continue
                        val rect = android.graphics.Rect()
                        child.getHitRect(rect)
                        if (rect.contains(event.x.toInt(), event.y.toInt())) {
                            target = child
                            break
                        }
                    }
                    if (target != null && target !== dragged) {
                        // Throttle swaps so animation can play
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastSwapTime < 160) return@setOnDragListener true
                        lastSwapTime = now
                        val fromIdx = flow.indexOfChild(dragged)
                        val toIdx = flow.indexOfChild(target)
                        if (fromIdx >= 0 && toIdx >= 0) {
                            // Animate target sliding into dragged's old slot
                            animateShift(target, fromIdx, toIdx)
                            flow.removeView(dragged)
                            val addIdx = flow.indexOfChild(addBtn)
                            val insertIdx = toIdx
                            val clampedIdx = if (addIdx != -1 && insertIdx >= addIdx) addIdx else insertIdx
                            flow.addView(dragged, clampedIdx)
                        }
                    }
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    saveCurrentOrder(root); true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    val dragged = event.localState as? View
                    // Restore all alphas/scales with a small settle animation
                    for (i in 0 until flow.childCount) {
                        val child = flow.getChildAt(i)
                        child.animate()
                            .alpha(1f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(180)
                            .setInterpolator(UiUtils.EASE_OUT)
                            .start()
                    }
                    dragged?.let { d ->
                        d.postDelayed({
                            d.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(120).setInterpolator(UiUtils.EASE_OUT).start()
                        }, 20)
                    }
                    saveCurrentOrder(root)
                    true
                }
                else -> true
            }
        }
    }

    private fun animateShift(view: View, fromIdx: Int, toIdx: Int) {
        val dx = if (toIdx > fromIdx) -40f else 40f
        view.animate()
            .translationX(dx)
            .setDuration(120)
            .setInterpolator(UiUtils.EASE_OUT)
            .withEndAction {
                view.animate()
                    .translationX(0f)
                    .setDuration(140)
                    .setInterpolator(UiUtils.EASE_OUT)
                    .start()
            }
            .start()
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMs }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                dateMs = startOfDay(Calendar.getInstance().apply { set(y, m, d) }).timeInMillis
                updateDateLabel()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openTimePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMs + timeMs }
        TimePickerDialog(
            requireContext(),
            { _, h, min ->
                timeMs = h * 3600000L + min * 60000L
                timePicked = true
                updateTimeLabel()
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateDateLabel() {
        val today = startOfDay(Calendar.getInstance()).timeInMillis
        tvDate.text = if (dateMs == today) getString(R.string.today)
        else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dateMs))
    }

    private fun updateTimeLabel() {
        tvTime.text = if (!timePicked) getString(R.string.now)
        else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timeMs))
    }

    private fun save(personId: String) {
        if (isSaving) return
        // Fix #1: if selected category was deleted (e.g., Online), pick first visible
        if (selectedCategory !in chips) {
            selectedCategory = chips.keys.firstOrNull() ?: run {
                Toast.makeText(requireContext(), "No category available", Toast.LENGTH_SHORT).show()
                return
            }
            try { updateChips() } catch (_: Exception) {}
        }
        val amount = Money.parseToPaise(etAmount.text?.toString().orEmpty())
        if (amount <= 0) {
            Toast.makeText(requireContext(), R.string.amount_required, Toast.LENGTH_SHORT).show()
            return
        }
        val note = etNote.text?.toString()?.trim().orEmpty()
        val createdAt = dateMs + timeMs
        isSaving = true
        val ctx = requireContext()
        // Local-first storage
        try {
            // Track the acting account as a single user-id entry
            val fu = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val actorUid = fu?.uid ?: ""
            val actorField = if (editTxId != null) {
                mapOf("editedByUid" to actorUid)
            } else {
                mapOf("createdByUid" to actorUid)
            }
            val data = mapOf<String, Any>(
                "personId" to personId,
                "category" to selectedCategory,
                "amount" to amount,
                "type" to direction,
                "note" to note,
                "createdAt" to createdAt
            ) + actorField + mapOf(
                // savedAt = last touch: set on create, bumped on every edit
                "savedAt" to System.currentTimeMillis()
            )
            val txId = editTxId ?: java.util.UUID.randomUUID().toString()
            // Synced persons: database first, app storage only after the
            // cloud write succeeds. Offline -> No internet alert, no save.
            if (ShareSync.isSynced(ctx, personId)) {
                if (!NetworkUtils.isOnline(ctx)) {
                    Toast.makeText(ctx, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
                    return
                }
                btnSave.isEnabled = false
                // Stay open until the database confirms (slow networks included)
                isCancelable = false
                dialog?.setCanceledOnTouchOutside(false)
                // Worker owns the math: raw fields go up, server aggregates come back
                ShareSync.saveTxRemote(ctx, personId, txId, data) { ok, agg ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        isCancelable = true
                        dialog?.setCanceledOnTouchOutside(true)
                        btnSave.isEnabled = true
                        if (ok) {
                            LocalStore.putTransaction(ctx, txId, data, mirror = false)
                            try {
                                LocalStore.transactions(ctx).optJSONObject(txId)?.put("unseen", true)
                                LocalStore.persist(ctx)
                            } catch (_: Exception) {}
                            Toast.makeText(ctx, "${Formatters.amount(amount)} ${getString(R.string.saved)}", Toast.LENGTH_SHORT).show()
                            ShareSync.applyServerAggregates(ctx, personId, agg)
                            (requireActivity() as? MainActivity)?.let {
                                it.animateNextRecordsChange()
                                it.refreshLocalData()
                            }
                            dismiss()
                        } else {
                            isSaving = false
                            val errMsg = agg?.optString("error")
                            if (!NetworkUtils.isOnline(ctx)) {
                                Toast.makeText(ctx, getString(R.string.no_internet_save), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    ctx, errMsg ?: getString(R.string.save_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                                // Worker lockdown (412): surface the recovery popup
                                if (!errMsg.isNullOrBlank() && errMsg.contains("Sync limit", true)) {
                                    (requireActivity() as? MainActivity)?.maybeShowSyncLock()
                                }
                            }
                        }
                    }
                }
                return
            }
            LocalStore.putTransaction(ctx, txId, data)
            // Local path only (synced returns earlier): no entry dots here.
            // Dots belong to synced persons only.
            Toast.makeText(ctx, "${Formatters.amount(amount)} ${getString(R.string.saved)}", Toast.LENGTH_SHORT).show()
            recomputeNet(personId)
            (requireActivity() as? MainActivity)?.let {
                it.animateNextRecordsChange()
                it.refreshLocalData()
            }
            dismiss()
        } catch (_: Exception) {
            isSaving = false
            Toast.makeText(ctx, R.string.save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun recomputeNet(personId: String, mirror: Boolean = true) {
        // Local-first: net + receive/send totals recomputed from this
        // person's local transactions (one pass, this person only) and
        // stored on the person record so totals UIs never rescan.
        val totals = PersonTotals.accumulate(LocalStore.transactions(requireContext()), personId)
        val now = System.currentTimeMillis()
        LocalStore.updatePersonFields(
            requireContext(), personId,
            // txUpdatedAt tracks transaction changes only (updatedAt = any change).
            // Own edits are seen by definition: loaded marker moves with it.
            PersonTotals.fieldMap(totals) + mapOf(
                "updatedAt" to now,
                "txUpdatedAt" to now, "txUpdatedAtLoaded" to now
            ),
            mirror = mirror
        )
    }

    private fun delete(personId: String) {
        val txId = editTxId ?: return
        val ctx = requireContext()
        try {
            // Synced persons: database first, same rule as save
            if (ShareSync.isSynced(ctx, personId)) {
                if (!NetworkUtils.isOnline(ctx)) {
                    Toast.makeText(ctx, R.string.no_internet_save, Toast.LENGTH_SHORT).show()
                    return
                }
                btnDelete.isEnabled = false
                // Stay open until the database confirms (slow networks included)
                isCancelable = false
                dialog?.setCanceledOnTouchOutside(false)
                ShareSync.deleteTxRemote(ctx, personId, txId) { ok, agg ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        isCancelable = true
                        dialog?.setCanceledOnTouchOutside(true)
                        btnDelete.isEnabled = true
                        if (ok) {
                            LocalStore.removeTransaction(ctx, txId, mirror = false)
                            Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
                            ShareSync.applyServerAggregates(ctx, personId, agg)
                            (requireActivity() as? MainActivity)?.let {
                                it.animateNextRecordsChange()
                                it.refreshLocalData()
                            }
                            dismiss()
                        } else {
                            val errMsg = agg?.optString("error")
                            if (!NetworkUtils.isOnline(ctx)) {
                                Toast.makeText(ctx, getString(R.string.no_internet_save), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    ctx, errMsg ?: getString(R.string.save_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (!errMsg.isNullOrBlank() && errMsg.contains("Sync limit", true)) {
                                    (requireActivity() as? MainActivity)?.maybeShowSyncLock()
                                }
                            }
                        }
                    }
                }
                return
            }
            LocalStore.removeTransaction(ctx, txId)
            Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
            recomputeNet(personId)
            (requireActivity() as? MainActivity)?.let {
                it.animateNextRecordsChange()
                it.refreshLocalData()
            }
            dismiss()
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOfDay(cal: Calendar): Calendar {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    private fun timeMsOf(cal: Calendar): Long =
        cal.get(Calendar.HOUR_OF_DAY) * 3600000L + cal.get(Calendar.MINUTE) * 60000L

    companion object {
        private const val ARG_PERSON_ID = "personId"
        private const val ARG_PERSON_NAME = "personName"
        private const val ARG_DIRECTION = "direction"
        private const val ARG_TX_ID = "txId"
        private const val ARG_CATEGORY = "category"
        private const val ARG_AMOUNT = "amount"
        private const val ARG_NOTE = "note"
        private const val ARG_CREATED_AT = "createdAt"
        private const val ARG_SAVED_AT = "savedAt"
        const val TAG = "MoneySheet"

        fun newInstance(personId: String, personName: String, direction: String): MoneySheet =
            MoneySheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_PERSON_NAME, personName)
                    putString(ARG_DIRECTION, direction)
                }
            }

        fun newInstanceForEdit(personId: String, tx: Transaction): MoneySheet =
            MoneySheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERSON_ID, personId)
                    putString(ARG_PERSON_NAME, "")
                    putString(ARG_DIRECTION, tx.type)
                    putString(ARG_TX_ID, tx.id)
                    putString(ARG_CATEGORY, tx.category)
                    putLong(ARG_AMOUNT, tx.amount)
                    putString(ARG_NOTE, tx.note)
                    putLong(ARG_CREATED_AT, tx.createdAt)
                    putLong(ARG_SAVED_AT, tx.savedAt)
                }
            }
    }
}