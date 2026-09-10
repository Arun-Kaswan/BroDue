package com.abk.brodue

import android.content.Context

data class Currency(
    val symbol: String,
    val name: String,
    val code: String
)

object CurrencyManager {

    const val PREFS_NAME = "currency_prefs"
    const val KEY_SYMBOL = "currency_symbol"
    const val KEY_NAME = "currency_name"
    const val KEY_CODE = "currency_code"

    val currencies = listOf(
        Currency("₹", "Rupee", "INR"),
        Currency("$", "Dollar", "USD"),
        Currency("€", "Euro", "EUR"),
        Currency("£", "Pound", "GBP"),
        Currency("¥", "Yen/Yuan", "JPY"),
        Currency("₽", "Ruble", "RUB"),
        Currency("₩", "Won", "KRW"),
        Currency("₺", "Lira", "TRY"),
        Currency("₱", "Peso", "PHP"),
        Currency("₫", "Dong", "VND")
    )

    @Volatile
    private var cachedSymbol: String? = null

    fun getSymbol(context: Context): String {
        cachedSymbol?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val symbol = prefs.getString(KEY_SYMBOL, null)
        if (symbol != null) {
            cachedSymbol = symbol
            return symbol
        }
        return "₹"
    }

    fun getCurrency(context: Context): Currency? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val symbol = prefs.getString(KEY_SYMBOL, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val code = prefs.getString(KEY_CODE, null) ?: return null
        return Currency(symbol, name, code)
    }

    fun saveLocal(context: Context, currency: Currency) {
        cachedSymbol = currency.symbol
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYMBOL, currency.symbol)
            .putString(KEY_NAME, currency.name)
            .putString(KEY_CODE, currency.code)
            .apply()
    }

    fun saveToDatabase(context: Context, currency: Currency, onComplete: ((Boolean) -> Unit)? = null) {
        try {
            // Local-first storage - only the symbol is saved
            LocalStore.setSetting(context, "currency", currency.symbol)
            onComplete?.invoke(true)
        } catch (e: Exception) {
            onComplete?.invoke(false)
        }
    }

    fun save(context: Context, currency: Currency, onComplete: ((Boolean) -> Unit)? = null) {
        saveLocal(context, currency)
        saveToDatabase(context, currency, onComplete)
    }

    // Local-first: currency comes straight from local storage
    fun fetchFromDatabase(context: Context, onResult: (Currency?) -> Unit) {
        onResult(getCurrency(context))
    }

    fun syncFromDatabase(context: Context, onComplete: (Currency?) -> Unit) {
        onComplete(getCurrency(context))
    }

    fun hasLocalCurrency(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_SYMBOL)
    }

    // Per-person override (""): empty means follow the default currency.
    // Stored on the person (local + cloud) so joined users see the owner's pick.
    fun symbolForPerson(context: Context, personId: String): String {
        return try {
            LocalStore.people(context).optJSONObject(personId)?.optString("currency", "").orEmpty()
                .ifBlank { getSymbol(context) }
        } catch (_: Exception) {
            getSymbol(context)
        }
    }

    fun personCurrencyOverride(context: Context, personId: String): String {
        return try {
            LocalStore.people(context).optJSONObject(personId)?.optString("currency", "").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun setPersonCurrency(context: Context, personId: String, symbol: String) {
        // updatePersonFields mirrors to the cloud for synced persons
        LocalStore.updatePersonFields(context, personId, mapOf("currency" to symbol))
    }

    // Persons pin the default at creation; legacy persons that still follow
    // the default get stamped once so later default changes only affect
    // upcoming new persons - never old entries.
    fun pinLegacyDefaults(context: Context) {
        try {
            val people = LocalStore.people(context)
            val def = getSymbol(context)
            var changed = false
            val pk = people.keys()
            while (pk.hasNext()) {
                val o = people.optJSONObject(pk.next()) ?: continue
                if (o.optString("currency", "").isBlank()) {
                    o.put("currency", def)
                    changed = true
                }
            }
            if (changed) LocalStore.persist(context)
        } catch (_: Exception) {}
    }

    fun clearCache() {
        cachedSymbol = null
    }
}
