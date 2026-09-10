package com.abk.brodue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class CurrencyAdapter(
    private val items: List<Currency>,
    private val onSelect: (Currency) -> Unit
) : RecyclerView.Adapter<CurrencyAdapter.VH>() {

    private var selectedPosition = -1
    private var selectedCurrency: Currency? = null

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSymbol: TextView = view.findViewById(R.id.tvCurrencySymbol)
        val tvName: TextView = view.findViewById(R.id.tvCurrencyName)
        val ivCheck: ImageView = view.findViewById(R.id.ivCheck)
        val card: MaterialCardView = view as MaterialCardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_currency, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvSymbol.text = item.symbol
        holder.tvName.text = item.name

        val isSelected = position == selectedPosition
        holder.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

        val context = holder.itemView.context
        val strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
        if (isSelected) {
            holder.card.strokeColor = ContextCompat.getColor(context, R.color.primary)
            holder.card.strokeWidth = strokeWidth
            holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.primary_container))
        } else {
            holder.card.strokeColor = ContextCompat.getColor(context, R.color.card_border)
            holder.card.strokeWidth = strokeWidth
            holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.white))
        }

        // Grouped corners like settings page: 18dp outer, 8dp inner, 4dp gap
        val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        lp.topMargin = if (position == 0) 0 else (4 * context.resources.displayMetrics.density).toInt()
        holder.itemView.layoutParams = lp
        val outerRadius = 18 * context.resources.displayMetrics.density
        val innerRadius = 8 * context.resources.displayMetrics.density
        val shape = com.google.android.material.shape.ShapeAppearanceModel.builder()
            .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == 0) outerRadius else innerRadius)
            .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == 0) outerRadius else innerRadius)
            .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == itemCount - 1) outerRadius else innerRadius)
            .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, if (position == itemCount - 1) outerRadius else innerRadius)
            .build()
        holder.card.shapeAppearanceModel = shape

        holder.itemView.setOnClickListener {
            val old = selectedPosition
            selectedPosition = holder.adapterPosition
            selectedCurrency = item
            if (old != -1) notifyItemChanged(old)
            notifyItemChanged(selectedPosition)
            onSelect(item)
        }
    }

    fun setSelected(currency: Currency) {
        val idx = items.indexOfFirst { it.symbol == currency.symbol }
        if (idx != -1) {
            selectedPosition = idx
            selectedCurrency = currency
            notifyDataSetChanged()
        }
    }
}
