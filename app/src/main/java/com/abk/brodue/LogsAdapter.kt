package com.abk.brodue

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class LogsAdapter(
    private val onItemClick: (LogEntry) -> Unit
) : ListAdapter<LogEntry, LogsAdapter.LogViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
        }
    }

    class LogViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {

        private val iconBox: View = view.findViewById(R.id.logIconBox)
        private val icon: ImageView = view.findViewById(R.id.logIcon)
        private val tvPerson: TextView = view.findViewById(R.id.tvPerson)
        private val tvDate: TextView = view.findViewById(R.id.tvLogDate)
        private val tvAmount: TextView = view.findViewById(R.id.tvLogAmount)
        private val txNewDot: View = view.findViewById(R.id.txNewDot)

        fun bind(entry: LogEntry) {
            val tx = entry.tx
            if (tx.unseen) {
                if (txNewDot.tag == null) {
                    txNewDot.tag = "shown"
                    UiUtils.popShow(txNewDot)
                } else {
                    txNewDot.visibility = View.VISIBLE
                }
            } else {
                txNewDot.tag = null
                UiUtils.popHide(txNewDot)
            }
            tvPerson.text = entry.personName
            tvDate.text = Formatters.date(if (tx.savedAt > 0) tx.savedAt else tx.createdAt)

            val prefs = view.context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            val useCategoryIcon = prefs.getBoolean("logs_use_category_icon", false)
            val synced = ShareSync.isSynced(view.context, entry.personId)
            val received = tx.type == "received"
            if (useCategoryIcon) {
                val style = CategoryStyle.forCategory(view.context, tx.category)
                icon.setImageResource(style.iconRes)
                val color = ContextCompat.getColor(view.context, if (received) R.color.positive else R.color.negative)
                val container = ContextCompat.getColor(view.context, if (received) R.color.positive_container else R.color.negative_container)
                icon.setColorFilter(color)
                (iconBox.background as? GradientDrawable)?.setTint(container)
                tvAmount.setTextColor(color)
            } else {
                val color = ContextCompat.getColor(view.context, if (received) R.color.positive else R.color.negative)
                val container = ContextCompat.getColor(view.context, if (received) R.color.positive_container else R.color.negative_container)
                icon.setImageResource(
                    if (synced) {
                        if (received) R.drawable.ic_synced_receive else R.drawable.ic_synced_send
                    } else {
                        if (received) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up
                    }
                )
                icon.setColorFilter(color)
                (iconBox.background as? GradientDrawable)?.setTint(container)
                tvAmount.setTextColor(color)
            }
            tvAmount.text = android.text.TextUtils.concat(
                if (received) "+" else "-",
                Formatters.amountWithSymbol(
                    tx.amount,
                    CurrencyManager.symbolForPerson(view.context, entry.personId)
                )
            )
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem.tx.id == newItem.tx.id
            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
        }
    }
}
