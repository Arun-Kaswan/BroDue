package com.abk.brodue

import android.content.Context
import org.json.JSONArray

object CategoryOrderStore {
    private const val PREF_PREFIX = "category_order_"
    private const val KEY_ORDER = "order"
    private const val FB_CHILD = "categoryOrder"

    private fun prefsName(): String {
        val uid = UserDb.uid() ?: "anon"
        return "$PREF_PREFIX$uid"
    }

    fun getOrder(context: Context): List<String> {
        val s = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE).getString(KEY_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveOrder(context: Context, order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE).edit().putString(KEY_ORDER, arr.toString()).apply()
        // Local-first: mirror into local store settings
        try {
            LocalStore.setSetting(context, FB_CHILD, order)
        } catch (_: Exception) {}
    }

    fun syncFromFirebase(context: Context, onDone: (() -> Unit)? = null) {
        // Local-first: nothing to sync from Firebase
        onDone?.invoke()
    }

    fun applyOrder(context: Context, allNames: List<String>): List<String> {
        val order = getOrder(context)
        if (order.isEmpty()) return allNames
        val ordered = mutableListOf<String>()
        order.forEach { name ->
            if (name in allNames) ordered.add(name)
        }
        allNames.forEach { if (it !in ordered) ordered.add(it) }
        return ordered
    }
}
