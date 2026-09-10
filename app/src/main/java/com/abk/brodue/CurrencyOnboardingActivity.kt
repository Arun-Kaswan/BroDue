package com.abk.brodue

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

// Onboarding currency picker shown once after the restore page
// (fresh installs / missing selection). Back is blocked until saved.
class CurrencyOnboardingActivity : AppCompatActivity() {

    private var selectedCurrency: Currency? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeBlackIcons()
        setContentView(R.layout.activity_currency_onboarding)

        // System-bars insets: content clears the status bar, Continue
        // clears the navigation bar
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootCurrencyOnboarding)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                (12 * density).toInt() + sb.top,
                v.paddingRight,
                (12 * density).toInt()
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.btnContinueCurrency)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.bottomMargin = sb.bottom
                v.layoutParams = it
            }
            insets
        }

        val rv = findViewById<RecyclerView>(R.id.rvCurrencyOnboarding)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        val adapter = TileAdapter(CurrencyManager.currencies) { currency ->
            selectedCurrency = currency
        }
        // Sensible default pre-selected: Continue works immediately,
        // picking another tile just changes the choice
        val initial = CurrencyManager.currencies.find { it.symbol == "₹" }
            ?: CurrencyManager.currencies.first()
        selectedCurrency = initial
        adapter.setSelected(initial)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnContinueCurrency).setOnClickListener {
            val currency = selectedCurrency ?: return@setOnClickListener
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            it.isEnabled = false
            CurrencyManager.saveLocal(this, currency)
            Formatters.setCurrencySymbol(currency.symbol)
            CurrencyManager.saveToDatabase(this, currency) { _ -> }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        blockBack()
    }

    // 2-column tile grid: big symbol + code, check badge on selection
    private class TileAdapter(
        private val items: List<Currency>,
        private val onSelect: (Currency) -> Unit
    ) : RecyclerView.Adapter<TileAdapter.VH>() {

        private var selectedPosition = -1

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: com.google.android.material.card.MaterialCardView =
                view as com.google.android.material.card.MaterialCardView
            val tvSymbol: android.widget.TextView = view.findViewById(R.id.tvTileSymbol)
            val tvName: android.widget.TextView = view.findViewById(R.id.tvTileName)
            val ivCheck: android.widget.ImageView = view.findViewById(R.id.ivTileCheck)
        }

        fun setSelected(currency: Currency) {
            val idx = items.indexOfFirst { it.symbol == currency.symbol }
            if (idx != -1) {
                val old = selectedPosition
                selectedPosition = idx
                if (old != -1) notifyItemChanged(old)
                notifyItemChanged(idx)
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_currency_tile, parent, false)
            // Grid gaps
            val gap = (8 * parent.context.resources.displayMetrics.density).toInt()
            (v.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                v.layoutParams = it
            }
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isSel = position == selectedPosition
            val ctx = holder.itemView.context
            holder.tvSymbol.text = item.symbol
            holder.tvSymbol.setTextColor(
                ctx.getColor(if (isSel) R.color.primary else R.color.navy_text)
            )
            holder.tvName.text = item.name
            holder.tvName.setTextColor(
                ctx.getColor(if (isSel) R.color.primary else R.color.text_secondary)
            )
            holder.ivCheck.visibility = if (isSel) View.VISIBLE else View.GONE
            holder.card.setCardBackgroundColor(
                ctx.getColor(if (isSel) R.color.primary_container else R.color.white)
            )
            holder.card.strokeColor =
                ctx.getColor(if (isSel) R.color.primary else R.color.card_border)
            holder.itemView.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                val old = selectedPosition
                selectedPosition = holder.adapterPosition
                if (old != -1) notifyItemChanged(old)
                notifyItemChanged(selectedPosition)
                onSelect(item)
            }
        }
    }

    // Unlosable until a currency is saved
    private fun blockBack() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@CurrencyOnboardingActivity,
                    "Please select a currency to continue",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
}
