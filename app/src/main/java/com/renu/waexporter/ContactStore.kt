package com.renu.waexporter

import android.content.Context

/** number -> name (name may be blank). Kept in insertion order, stored locally only. */
class ContactStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    fun all(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        prefs.getString(KEY, "").orEmpty().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            map[parts[0]] = parts.getOrElse(1) { "" }
        }
        return map
    }

    private fun save(map: Map<String, String>) {
        val text = map.entries.joinToString("\n") { (n, name) ->
            n + "\t" + name.replace('\t', ' ').replace('\n', ' ')
        }
        prefs.edit().putString(KEY, text).apply()
    }

    /** Adds numbers (with optional names). Returns how many numbers were new. */
    fun addAll(entries: List<Pair<String, String?>>): Int {
        if (entries.isEmpty()) return 0
        val map = all()
        var added = 0
        for ((number, name) in entries) {
            val existing = map[number]
            if (existing == null) { map[number] = name.orEmpty(); added++ }
            else if (existing.isBlank() && !name.isNullOrBlank()) map[number] = name
        }
        save(map)
        return added
    }

    fun setName(number: String, name: String) {
        val map = all()
        map[number] = name
        save(map)
    }

    fun count() = all().size
    fun pendingNames() = all().count { it.value.isBlank() }
    fun clear() = prefs.edit().remove(KEY).apply()

    companion object { private const val KEY = "entries" }
}
