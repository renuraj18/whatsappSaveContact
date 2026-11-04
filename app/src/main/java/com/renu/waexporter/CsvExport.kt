package com.renu.waexporter

/** Builds a CSV in the column format Google Contacts (contacts.google.com → Import) accepts. */
object CsvExport {
    fun build(entries: Map<String, String>, fallbackPrefix: String, label: String): String {
        val labels = if (label.isBlank()) "* myContacts" else "${label.trim()} ::: * myContacts"
        val sb = StringBuilder("First Name,Last Name,Labels,Phone 1 - Label,Phone 1 - Value\n")
        for ((number, name) in entries) {
            val first = name.ifBlank { "${fallbackPrefix.trim()} $number".trim() }
            sb.append(listOf(first, "", labels, "Mobile", number).joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
