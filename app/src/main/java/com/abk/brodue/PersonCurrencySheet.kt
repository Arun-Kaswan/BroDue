package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Per-person currency picker. No Default row: every person pins an
// explicit currency at creation, so the only way to change it afterwards
// is picking one here manually. Saved on the person (local + cloud) so
// joined users see the owner's pick.
class PersonCurrencySheet : BaseSheet() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_person_currency, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = requireArguments().getString(ARG_PERSON_ID).orEmpty()
        val ctx = requireContext()
        var override = CurrencyManager.personCurrencyOverride(ctx, personId)
        if (override.isBlank()) {
            // Heal the invariant at the entry point: every person explicit
            override = CurrencyManager.getSymbol(ctx)
            CurrencyManager.setPersonCurrency(ctx, personId, override)
        }
        val items = CurrencyManager.currencies

        val rv = view.findViewById<RecyclerView>(R.id.rvPersonCurrency)
        rv.layoutManager = LinearLayoutManager(ctx)
        val adapter = CurrencyAdapter(items) { currency ->
            CurrencyManager.setPersonCurrency(ctx, personId, currency.symbol)
            (activity as? MainActivity)?.onPersonCurrencyChanged()
            parentFragmentManager.setFragmentResult(
                REQ_KEY, Bundle().apply { putString("personId", personId) }
            )
            Toast.makeText(ctx, R.string.currency_updated, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        rv.adapter = adapter
        // Preselect the person's pinned currency
        adapter.setSelected(Currency(override, "", override))
    }

    companion object {
        private const val ARG_PERSON_ID = "personId"
        const val TAG = "PersonCurrencySheet"
        const val REQ_KEY = "person_currency_changed"

        fun newInstance(personId: String): PersonCurrencySheet = PersonCurrencySheet().apply {
            arguments = Bundle().apply { putString(ARG_PERSON_ID, personId) }
        }
    }
}
