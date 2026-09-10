package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ManageCategoriesSheet : BaseSheet() {

    private var adapter: ManageAdapter? = null

    data class ManageItem(val name: String, val iconResName: String, val isHidden: Boolean, val isDefault: Boolean)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_manage_categories, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvManageCategories)
        val tvEmpty = view.findViewById<TextView>(R.id.tvNoCustom)
        rv.layoutManager = LinearLayoutManager(requireContext())

        val defaults = listOf(
            CustomCategory("Online", "ic_custom_01"),
            CustomCategory("Recharge", "ic_custom_02"),
            CustomCategory("Cash", "ic_custom_03"),
            CustomCategory("Item", "ic_custom_04"),
            CustomCategory("Roundoff", "ic_custom_27")
        )
        val hidden = CustomCategoryStore.getHidden(requireContext())
        val customs = CustomCategoryStore.getAll(requireContext())

        // Build list: visible first, then hidden defaults at bottom
        val visible = mutableListOf<ManageItem>()
        val hiddenList = mutableListOf<ManageItem>()

        defaults.forEach { d ->
            val isH = hidden.any { it.equals(d.name, ignoreCase = true) }
            if (isH) hiddenList.add(ManageItem(d.name, d.iconResName, true, true))
            else visible.add(ManageItem(d.name, d.iconResName, false, true))
        }
        customs.forEach { c ->
            // Customs are never hidden via hidden set (they are removed), so just visible
            visible.add(ManageItem(c.name, c.iconResName, false, false))
        }

        val list = mutableListOf<ManageItem>()
        list.addAll(visible)
        list.addAll(hiddenList)

        adapter = ManageAdapter(list,
            onDelete = { item, pos ->
                // For default hidden? Actually delete for visible, restore for hidden
                if (item.isHidden) {
                    // Restore
                    CustomCategoryStore.unhideCategory(requireContext(), item.name) {
                        list[pos] = item.copy(isHidden = false)
                        // Move from hidden section to visible section (just before hiddenList start)
                        val moved = list.removeAt(pos)
                        // Find first hidden position
                        val firstHidden = list.indexOfFirst { it.isHidden }
                        val insertPos = if (firstHidden == -1) list.size else firstHidden
                        list.add(insertPos, moved.copy(isHidden = false))
                        adapter?.notifyDataSetChanged()
                        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        // Notify MoneySheet to restore
                        (activity as? MainActivity)?.let { act ->
                            act.supportFragmentManager.fragments.forEach { f ->
                                if (f is MoneySheet) f.restoreCategoryChip(item.name, item.iconResName)
                            }
                            (act.supportFragmentManager.findFragmentByTag(MoneySheet.TAG) as? MoneySheet)?.restoreCategoryChip(item.name, item.iconResName)
                        }
                        parentFragmentManager.setFragmentResult(REQ_RESTORED, Bundle().apply { putString("name", item.name); putString("icon", item.iconResName) })
                        (activity as? MainActivity)?.supportFragmentManager?.setFragmentResult(REQ_RESTORED, Bundle().apply { putString("name", item.name); putString("icon", item.iconResName) })
                        Toast.makeText(requireContext(), "\"${item.name}\" restored", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Delete/hide
                    CustomCategoryStore.hideCategory(requireContext(), item.name) {
                        if (item.isDefault) {
                            // Move to bottom as hidden
                            val removed = list.removeAt(pos)
                            list.add(removed.copy(isHidden = true))
                            adapter?.notifyDataSetChanged()
                        } else {
                            list.removeAt(pos)
                            adapter?.notifyItemRemoved(pos)
                            adapter?.notifyItemRangeChanged(pos, list.size)
                        }
                        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        // Notify MoneySheet to remove instantly
                        (activity as? MainActivity)?.let { act ->
                            act.supportFragmentManager.fragments.forEach { f ->
                                if (f is MoneySheet) f.removeCategoryChip(item.name)
                            }
                            (act.supportFragmentManager.findFragmentByTag(MoneySheet.TAG) as? MoneySheet)?.removeCategoryChip(item.name)
                        }
                        parentFragmentManager.setFragmentResult(REQ_DELETED, Bundle().apply { putString("name", item.name) })
                        (activity as? MainActivity)?.supportFragmentManager?.setFragmentResult(REQ_DELETED, Bundle().apply { putString("name", item.name) })
                        Toast.makeText(requireContext(), "\"${item.name}\" deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        rv.adapter = adapter
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val TAG = "ManageCategoriesSheet"
        const val REQ_DELETED = "custom_category_deleted"
        const val REQ_RESTORED = "custom_category_restored"
        fun newInstance(): ManageCategoriesSheet = ManageCategoriesSheet()
    }

    private class ManageAdapter(
        private val items: MutableList<ManageItem>,
        private val onDelete: (ManageItem, Int) -> Unit
    ) : RecyclerView.Adapter<ManageAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgCategoryIcon)
            val tvName: TextView = view.findViewById(R.id.tvCategoryName)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDeleteCategory)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_manage_category, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            holder.tvName.text = c.name
            val resId = CustomCategoryStore.iconResId(holder.itemView.context, c.iconResName)
            holder.img.setImageResource(resId)
            val ctx = holder.itemView.context
            // Background for manage list is #DDE2F6 with 30% as manage_bg
            (holder.itemView as? com.google.android.material.card.MaterialCardView)?.let { card ->
                card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.manage_bg))
                card.strokeWidth = 0
                card.alpha = if (c.isHidden) 0.5f else 1f
            }
            holder.tvName.alpha = if (c.isHidden) 0.5f else 1f
            holder.img.alpha = if (c.isHidden) 0.5f else 1f
            if (c.isHidden) {
                holder.img.imageTintList = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.grey_soft)
                holder.btnDelete.setIconResource(R.drawable.ic_restore)
                holder.btnDelete.iconTint = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.primary)
                holder.btnDelete.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.white)
                holder.btnDelete.strokeColor = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.outline_variant)
            } else {
                holder.img.imageTintList = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.primary)
                holder.btnDelete.setIconResource(R.drawable.ic_category_delete)
                holder.btnDelete.iconTint = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.negative)
                holder.btnDelete.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.white)
                holder.btnDelete.strokeColor = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.outline_variant)
            }
            holder.btnDelete.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onDelete(c, holder.bindingAdapterPosition)
            }
        }
    }
}
