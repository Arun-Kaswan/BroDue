package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

class LogsDisplaySheet : BaseSheet() {

    private var selectedKey: String = "net"
    private var selCurrency: String = "₹"
    private var currencies: List<String> = emptyList()
    // symbol -> [net, pos, neg, sendCount, receiveCount, sendAmount, receiveAmount]
    private var totals: Map<String, LongArray> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_logs_display, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        selectedKey = args.getString(ARG_SELECTED, "net")
        selCurrency = args.getString(ARG_CURRENCY, "₹")
        currencies = args.getStringArrayList(ARG_CURRENCIES) ?: emptyList()
        totals = parseTotals(args.getString(ARG_TOTALS).orEmpty())
        if (selCurrency !in totals && totals.isNotEmpty()) {
            selCurrency = totals.keys.first()
        }

        val rvCurrency = view.findViewById<RecyclerView>(R.id.rvCurrencyOptions)
        if (currencies.size <= 1) {
            rvCurrency.visibility = View.GONE
        } else {
            rvCurrency.visibility = View.VISIBLE
            rvCurrency.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            rvCurrency.adapter = CurrencyAdapter(currencies, selCurrency) { sym ->
                selCurrency = sym
                displayAdapter?.update(valuesFor(sym), sliceFor(sym)[0])
                (rvCurrency.adapter as? CurrencyAdapter)?.update(sym)
                // Apply instantly on the logs page too - no second tap needed
                parentFragmentManager.setFragmentResult(
                    REQ_KEY,
                    Bundle().apply {
                        putString("selected", selectedKey)
                        putString("currency", selCurrency)
                    }
                )
            }
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvDisplayOptions)
        rv.layoutManager = LinearLayoutManager(requireContext())
        displayAdapter = DisplayAdapter(valuesFor(selCurrency), selectedKey, sliceFor(selCurrency)[0]) { key ->
            parentFragmentManager.setFragmentResult(
                REQ_KEY,
                Bundle().apply {
                    putString("selected", key)
                    putString("currency", selCurrency)
                }
            )
            dismiss()
        }
        rv.adapter = displayAdapter
    }

    private var displayAdapter: DisplayAdapter? = null

    private fun sliceFor(sym: String): LongArray =
        totals[sym] ?: totals.values.firstOrNull() ?: LongArray(7)

    private fun valuesFor(sym: String): LinkedHashMap<String, Pair<String, CharSequence>> {
        val t = sliceFor(sym)
        val netVal = t[0]
        val posVal = t[1]
        val negVal = t[2]
        val sendCount = t[3].toInt()
        val receiveCount = t[4].toInt()
        val sendAmount = t[5]
        val receiveAmount = t[6]
        return linkedMapOf(
            "net" to ("Net Balance" to Formatters.amountWithSymbol(kotlin.math.abs(netVal), sym)),
            "neg" to ("Payable" to Formatters.amountWithSymbol(kotlin.math.abs(negVal), sym)),
            "pos" to ("Receivable" to Formatters.amountWithSymbol(posVal, sym)),
            "send" to ("$sendCount Send" to Formatters.amountWithSymbol(sendAmount, sym)),
            "receive" to ("$receiveCount Received" to Formatters.amountWithSymbol(receiveAmount, sym))
        )
    }

    companion object {
        const val TAG = "LogsDisplaySheet"
        const val REQ_KEY = "logs_display_selected"
        private const val ARG_TOTALS = "totals"
        private const val ARG_CURRENCIES = "currencies"
        private const val ARG_CURRENCY = "currency"
        private const val ARG_SELECTED = "selected"

        fun newInstance(
            totalsByCurrency: Map<String, LongArray>,
            currencies: List<String>,
            currency: String,
            selected: String
        ): LogsDisplaySheet = LogsDisplaySheet().apply {
            val json = JSONObject()
            totalsByCurrency.forEach { (sym, t) ->
                val arr = org.json.JSONArray()
                t.forEach { arr.put(it) }
                json.put(sym, arr)
            }
            arguments = Bundle().apply {
                putString(ARG_TOTALS, json.toString())
                putStringArrayList(ARG_CURRENCIES, ArrayList(currencies))
                putString(ARG_CURRENCY, currency)
                putString(ARG_SELECTED, selected)
            }
        }

        private fun parseTotals(json: String): Map<String, LongArray> {
            val out = linkedMapOf<String, LongArray>()
            try {
                val o = JSONObject(json)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val sym = keys.next()
                    val arr = o.optJSONArray(sym) ?: continue
                    val t = LongArray(7)
                    for (i in 0 until minOf(7, arr.length())) t[i] = arr.optLong(i)
                    out[sym] = t
                }
            } catch (_: Exception) {}
            return out
        }
    }

    private class CurrencyAdapter(
        private val items: List<String>,
        private var selected: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<CurrencyAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val tv: TextView = view.findViewById(R.id.tvCurrencyChip)
        }

        fun update(sym: String) {
            selected = sym
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_currency_chip, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val sym = items[position]
            val isSel = sym == selected
            holder.tv.text = sym
            holder.tv.setTextColor(
                holder.itemView.context.getColor(if (isSel) R.color.primary else R.color.text_secondary)
            )
            holder.card.setCardBackgroundColor(
                holder.itemView.context.getColor(if (isSel) R.color.primary_container else R.color.field_bg)
            )
            holder.card.strokeColor =
                holder.itemView.context.getColor(if (isSel) R.color.primary else R.color.border_light)
            holder.itemView.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (sym != selected) onSelect(sym)
            }
        }
    }

    private class DisplayAdapter(
        private var values: Map<String, Pair<String, CharSequence>>,
        private var selected: String,
        private var netVal: Long,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<DisplayAdapter.VH>() {

        private var keys = values.keys.toList()

        fun update(newValues: Map<String, Pair<String, CharSequence>>, newNet: Long) {
            values = newValues
            keys = newValues.keys.toList()
            netVal = newNet
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val cardIcon: MaterialCardView = view.findViewById(R.id.cardDisplayIcon)
            val imgIcon: ImageView = view.findViewById(R.id.imgDisplayIcon)
            val tvLabel: TextView = view.findViewById(R.id.tvDisplayLabel)
            val tvSub: TextView = view.findViewById(R.id.tvDisplaySub)
            val tvValue: TextView = view.findViewById(R.id.tvDisplayValue)
            val imgCheck: ImageView = view.findViewById(R.id.imgDisplayCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_logs_display, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = keys.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val key = keys[position]
            val pair = values[key] ?: ("" to "")
            holder.tvLabel.text = pair.first
            holder.tvValue.text = pair.second
            holder.tvSub.text = when (key) {
                "net" -> "Total"
                "neg" -> "Total payable"
                "pos" -> "Total receivable"
                "send" -> "Total send"
                "receive" -> "Total received"
                else -> ""
            }
            val (iconRes, bgRes, tintRes) = when (key) {
                "net" -> Triple(R.drawable.ic_swap_vert, R.color.primary_container, R.color.primary)
                "neg" -> Triple(R.drawable.ic_person, R.color.save_red_soft, R.color.save_red)
                "pos" -> Triple(R.drawable.ic_person, R.color.cat_green_soft, R.color.cat_green)
                "send" -> Triple(R.drawable.ic_arrow_up, R.color.save_red_soft, R.color.save_red)
                "receive" -> Triple(R.drawable.ic_arrow_down, R.color.cat_green_soft, R.color.cat_green)
                else -> Triple(R.drawable.ic_person, R.color.field_bg, R.color.grey_soft)
            }
            holder.imgIcon.setImageResource(iconRes)
            holder.imgIcon.imageTintList = holder.itemView.context.getColorStateList(tintRes)
            holder.cardIcon.setCardBackgroundColor(holder.itemView.context.getColor(bgRes))

            // Drawer: net grey if 0 else red/green, others always in respective colors (even if 0)
            val valueColorRes = when (key) {
                "net" -> when {
                    netVal == 0L -> R.color.text_primary
                    netVal > 0 -> R.color.positive
                    else -> R.color.negative
                }
                "neg", "send" -> R.color.negative
                "pos", "receive" -> R.color.positive
                else -> R.color.navy_text
            }
            // For net zero, already grey, otherwise use respective
            holder.tvValue.setTextColor(holder.itemView.context.getColor(valueColorRes))

            val isSel = key == selected
            holder.card.setCardBackgroundColor(
                holder.itemView.context.getColor(if (isSel) R.color.primary_container else R.color.field_bg)
            )
            holder.card.strokeWidth = (1 * holder.itemView.context.resources.displayMetrics.density).toInt()
            holder.card.strokeColor = holder.itemView.context.getColor(if (isSel) R.color.primary else R.color.border_light)
            holder.imgCheck.visibility = if (isSel) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                selected = key
                onSelect(key)
                notifyDataSetChanged()
            }
        }
    }
}
