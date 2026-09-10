package com.abk.brodue

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class CustomCategorySheet : BaseSheet() {

    private var selectedIcon = "ic_custom_01"
    private var iconAdapter: IconPickerAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_custom_category, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etName = view.findViewById<android.widget.EditText>(R.id.etCustomName)
        val tilName = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCustomName)
        val rv = view.findViewById<RecyclerView>(R.id.rvIcons)
        rv.layoutManager = GridLayoutManager(requireContext(), 4)
        iconAdapter = IconPickerAdapter(CustomCategoryStore.availableIcons, selectedIcon) { icon ->
            selectedIcon = icon
        }
        rv.adapter = iconAdapter

        view.findViewById<View>(R.id.btnManageCategories).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            ManageCategoriesSheet.newInstance().show(parentFragmentManager, ManageCategoriesSheet.TAG)
        }

        // Listen for delete from manage sheet to refresh picker if needed
        parentFragmentManager.setFragmentResultListener(ManageCategoriesSheet.REQ_DELETED, this) { _, _ ->
            // No direct UI update needed here (picker will reload next time)
        }

        view.findViewById<View>(R.id.btnSaveCustom).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                tilName.error = "Enter category name"
                shake(tilName)
                return@setOnClickListener
            }
            if (name.length > 20) {
                tilName.error = "Max 20 chars"
                return@setOnClickListener
            }
            if (CustomCategoryStore.exists(requireContext(), name)) {
                tilName.error = "Category already exists"
                shake(tilName)
                return@setOnClickListener
            }
            if (!NetworkUtils.requireOnline(requireContext())) {
                // Still allow local save when offline - will sync later
            }
            CustomCategoryStore.add(requireContext(), CustomCategory(name, selectedIcon)) {
                Toast.makeText(requireContext(), "\"$name\" added", Toast.LENGTH_SHORT).show()
                // Notify parent via fragment result
                parentFragmentManager.setFragmentResult(REQ_KEY, Bundle().apply {
                    putString("name", name)
                    putString("icon", selectedIcon)
                })
                dismiss()
            }
        }
        etName.requestFocus()
    }

    private fun shake(view: View) {
        ObjectAnimator.ofFloat(view, "translationX", 0f, -16f, 16f, -10f, 10f, -5f, 5f, 0f).apply {
            duration = 450
            interpolator = UiUtils.EASE_OUT
            start()
        }
    }

    companion object {
        const val TAG = "CustomCategorySheet"
        const val REQ_KEY = "custom_category_added"
        fun newInstance(): CustomCategorySheet = CustomCategorySheet()
    }

    private class IconPickerAdapter(
        private val icons: List<String>,
        private var selected: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<IconPickerAdapter.VH>() {

        inner class VH(val card: com.google.android.material.card.MaterialCardView, val img: android.widget.ImageView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (8 * ctx.resources.displayMetrics.density).toInt()
                    setMargins(m, m, m, m)
                }
                radius = 14 * ctx.resources.displayMetrics.density
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                val out = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                foreground = ContextCompat.getDrawable(ctx, out.resourceId)
            }
            val img = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (20 * ctx.resources.displayMetrics.density).toInt(),
                    (20 * ctx.resources.displayMetrics.density).toInt()
                ).apply { gravity = android.view.Gravity.CENTER }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            val inner = android.widget.FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (56 * ctx.resources.displayMetrics.density).toInt()
                )
                addView(img)
            }
            card.addView(inner)
            return VH(card, img)
        }

        override fun getItemCount(): Int = icons.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val iconName = icons[position]
            val isSel = iconName == selected
            val ctx = holder.card.context
            val resId = CustomCategoryStore.iconResId(ctx, iconName)
            holder.img.setImageResource(resId)
            holder.img.imageTintList = androidx.core.content.ContextCompat.getColorStateList(
                ctx, if (isSel) R.color.primary else R.color.grey_soft
            )
            holder.card.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(ctx, if (isSel) R.color.primary_container else R.color.field_bg)
            )
            holder.card.strokeColor = androidx.core.content.ContextCompat.getColor(ctx, if (isSel) R.color.primary else R.color.border_light)
            holder.card.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
            holder.card.setOnClickListener {
                selected = iconName
                onSelect(iconName)
                notifyDataSetChanged()
            }
        }
    }
}
