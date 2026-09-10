package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

// Over-limit lockdown: lists every synced person with a per-row
// Disable (owned) / Leave (joined) action. Refreshes on each completed
// action and dismisses itself the moment the count is back in the limit.
class SyncLockSheet : BaseSheet() {

    private data class Row(
        val id: String,
        val name: String,
        val owned: Boolean
    )

    private lateinit var adapter: RowAdapter
    private var rows: List<Row> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_sync_lock, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvSyncLockPeople)
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = RowAdapter(rows) { row -> onRowAction(row) }
        rv.adapter = adapter

        // Completed disable/leave underneath -> refresh, maybe dismiss
        parentFragmentManager.setFragmentResultListener(
            DisableSyncSheet.REQ_DISABLED, this
        ) { _, _ -> refresh() }
        parentFragmentManager.setFragmentResultListener(
            SwipeLeaveSheet.REQ_LEFT, this
        ) { _, _ -> refresh() }

        view.findViewById<MaterialButton>(R.id.btnSyncLockOk).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
        refresh()
    }

    private fun loadRows(): List<Row> {
        val ctx = requireContext()
        return try {
            ShareSync.syncedIds(ctx).mapNotNull { pid ->
                val o = LocalStore.people(ctx).optJSONObject(pid) ?: return@mapNotNull null
                if (o.optBoolean("archived", false)) return@mapNotNull null
                val owned = try {
                    o.optString("dbOwner", "") == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                } catch (_: Exception) { false }
                val name = o.optString("name", "").ifBlank { getString(R.string.shared_person) }
                Row(pid, name, owned)
            }.sortedWith(compareBy({ !it.owned }, { it.name.lowercase() }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun refresh() {
        if (!isAdded) return
        try {
            val ctx = requireContext()
            rows = loadRows()
            adapter.update(rows)
            val cap = ShareSync.cachedSyncCap(ctx)
            val used = ShareSync.syncedCount(ctx)
            view?.findViewById<TextView>(R.id.syncLockBody)?.text =
                getString(R.string.sync_lock_body, used, cap)
            if (used <= cap) {
                Toast.makeText(ctx, R.string.sync_restored, Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.refreshLocalData()
                dismiss()
            }
        } catch (_: Exception) {}
    }

    private fun onRowAction(row: Row) {
        try {
            if (row.owned) {
                DisableSyncSheet.newInstance(row.id, row.name)
                    .show(parentFragmentManager, DisableSyncSheet.TAG)
            } else {
                SwipeLeaveSheet.newInstance(row.id, row.name)
                    .show(parentFragmentManager, SwipeLeaveSheet.TAG)
            }
        } catch (_: Exception) {}
    }

    private class RowAdapter(
        private var items: List<Row>,
        private val onAction: (Row) -> Unit
    ) : RecyclerView.Adapter<RowAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvLockPersonName)
            val tvSub: TextView = view.findViewById(R.id.tvLockPersonSub)
            val btn: MaterialButton = view.findViewById(R.id.btnLockPersonAction)
        }

        fun update(newItems: List<Row>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sync_lock_person, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            val ctx = holder.itemView.context
            holder.tvName.text = row.name
            holder.tvSub.text = if (row.owned) {
                ctx.getString(R.string.sync_lock_owner_row)
            } else {
                ctx.getString(R.string.sync_lock_shared_row)
            }
            holder.btn.text = ctx.getString(
                if (row.owned) R.string.disable_sync else R.string.leave
            )
            holder.btn.backgroundTintList = ctx.getColorStateList(R.color.save_red_soft)
            holder.btn.setTextColor(ctx.getColor(R.color.save_red))
            holder.btn.strokeColor = ctx.getColorStateList(R.color.negative_border)
            holder.itemView.setOnClickListener { onAction(row) }
            holder.btn.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onAction(row)
            }
        }
    }

    companion object {
        const val TAG = "SyncLockSheet"

        fun newInstance(): SyncLockSheet = SyncLockSheet()
    }
}
