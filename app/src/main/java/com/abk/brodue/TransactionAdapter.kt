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

class TransactionAdapter(
    private val onItemClick: (Transaction) -> Unit
) : ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(DiffCallback) {

    // Per-person currency of the open person page (null = default).
    // Set by MainActivity before submitting; forced rebind on change.
    var currencySymbol: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return TransactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        holder.bind(getItem(position))
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
        }
    }

    inner class TransactionViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {

        private val iconBox: View = view.findViewById(R.id.iconBox)
        private val icon: ImageView = view.findViewById(R.id.txIcon)
        private val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        private val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        private val tvDate: TextView = view.findViewById(R.id.tvDate)
        private val txNewDot: View = view.findViewById(R.id.txNewDot)

        fun bind(tx: Transaction) {
            tvCategory.text = tx.category
            tvDate.text = Formatters.date(tx.createdAt)
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

            val prefs = view.context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            val useCategoryIcon = prefs.getBoolean("home_use_category_icon", false)
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
                icon.setImageResource(if (received) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
                icon.setColorFilter(color)
                (iconBox.background as? GradientDrawable)?.setTint(container)
                tvAmount.setTextColor(color)
            }
            tvAmount.text = android.text.TextUtils.concat(
                if (received) "+" else "-",
                Formatters.amountWithSymbol(tx.amount, currencySymbol ?: Formatters.getCurrencySymbol())
            )
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Transaction>() {
            override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction) = oldItem == newItem
        }
    }
}