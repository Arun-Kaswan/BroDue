package com.abk.brodue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomCategory(val name: String, val iconResName: String)

object CustomCategoryStore {
    private const val PREF_NAME_PREFIX = "custom_categories_"
    private const val KEY_LIST = "list"
    private const val FB_CHILD = "customCategories"
    private const val KEY_HIDDEN = "hidden_list"
    private const val FB_HIDDEN = "hiddenCategories"

    // Custom icons provided for public custom categories
    val availableIcons = listOf(
        "ic_custom_01", "ic_custom_02", "ic_custom_03", "ic_custom_04", "ic_custom_05",
        "ic_custom_06", "ic_custom_07", "ic_custom_08", "ic_custom_09", "ic_custom_10",
        "ic_custom_11", "ic_custom_12", "ic_custom_13", "ic_custom_14", "ic_custom_15",
        "ic_custom_16", "ic_custom_17", "ic_custom_18", "ic_custom_19", "ic_custom_20",
        "ic_custom_21", "ic_custom_22", "ic_custom_23", "ic_custom_24", "ic_custom_25",
        "ic_custom_26", "ic_custom_27", "ic_custom_28"
    )

    fun iconResId(context: Context, name: String): Int {
        return context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 } ?: R.drawable.ic_custom_01
    }

    private fun prefsName(): String {
        val uid = UserDb.uid() ?: "anon"
        return "$PREF_NAME_PREFIX$uid"
    }

    fun getAll(context: Context): List<CustomCategory> {
        val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val icon = o.optString("icon").ifBlank { "ic_custom_01" }
                CustomCategory(name, icon)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun exists(context: Context, name: String): Boolean {
        return getAll(context).any { it.name.equals(name, ignoreCase = true) }
    }

    fun add(context: Context, category: CustomCategory, onComplete: (() -> Unit)? = null) {
        val list = getAll(context).toMutableList()
        if (list.any { it.name.equals(category.name, ignoreCase = true) }) {
            onComplete?.invoke()
            return
        }
        list.add(category)
        saveLocal(context, list)
        // Local-first mirror into local store settings
        try {
            LocalStore.setSetting(context, FB_CHILD, list.associate { it.name to it.iconResName })
        } catch (_: Exception) {}
        onComplete?.invoke()
    }

    fun remove(context: Context, name: String, onComplete: (() -> Unit)? = null) {
        val list = getAll(context).toMutableList()
        val removed = list.removeAll { it.name.equals(name, ignoreCase = true) }
        if (!removed) {
            onComplete?.invoke()
            return
        }
        saveLocal(context, list)
        // Local-first mirror
        try {
            LocalStore.setSetting(context, FB_CHILD, list.associate { it.name to it.iconResName })
        } catch (_: Exception) {}
        onComplete?.invoke()
    }

    private fun saveLocal(context: Context, list: List<CustomCategory>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("name", c.name)
            o.put("icon", c.iconResName)
            arr.put(o)
        }
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun syncFromFirebase(context: Context, onDone: (() -> Unit)? = null) {
        // Local-first: nothing to sync from Firebase
        onDone?.invoke()
    }

    // Hidden defaults handling
    fun getHidden(context: Context): Set<String> {
        return context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
    }

    fun isHidden(context: Context, name: String): Boolean {
        return getHidden(context).any { it.equals(name, ignoreCase = true) }
    }

    fun hideCategory(context: Context, name: String, onComplete: (() -> Unit)? = null) {
        val defaults = setOf("Online", "Recharge", "Cash", "Item", "Roundoff")
        if (defaults.any { it.equals(name, ignoreCase = true) }) {
            val set = getHidden(context).toMutableSet()
            // Find exact default name casing
            val canonical = defaults.find { it.equals(name, ignoreCase = true) } ?: name
            if (set.add(canonical)) {
                context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
                    .edit().putStringSet(KEY_HIDDEN, set).apply()
                try {
                    LocalStore.setSetting(context, FB_HIDDEN, set.toList())
                } catch (_: Exception) {}
            }
            onComplete?.invoke()
            return
        }
        // Custom -> remove
        remove(context, name, onComplete)
    }

    fun unhideCategory(context: Context, name: String, onComplete: (() -> Unit)? = null) {
        val set = getHidden(context).toMutableSet()
        val toRemove = set.find { it.equals(name, ignoreCase = true) } ?: run { onComplete?.invoke(); return }
        set.remove(toRemove)
        context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN, set).apply()
        try {
            LocalStore.setSetting(context, FB_HIDDEN, set.toList())
        } catch (_: Exception) {}
        onComplete?.invoke()
    }

    fun getAllWithDefaults(context: Context): List<CustomCategory> {
        val defaults = listOf(
            CustomCategory("Online", "ic_custom_01"),
            CustomCategory("Recharge", "ic_custom_02"),
            CustomCategory("Cash", "ic_custom_03"),
            CustomCategory("Item", "ic_custom_04"),
            CustomCategory("Roundoff", "ic_custom_27")
        )
        val hidden = getHidden(context)
        val visibleDefaults = defaults.filterNot { d -> hidden.any { it.equals(d.name, ignoreCase = true) } }
        return visibleDefaults + getAll(context)
    }
}
