package com.renu.waexporter

/**
 * Builds a CSV in the Google Contacts import format (see DATA/google_contacts_import.csv):
 * Name, Given Name, Family Name, Phone 1 - Label, Phone 1 - Value. A Labels column is appended
 * only when a label is given, so imported contacts can be grouped (leave it empty for the exact sample format).
 */
object CsvExport {
    private const val BOM = "\uFEFF"

    fun build(entries: Map<String, String>, fallbackPrefix: String, label: String): String {
        val withLabel = label.isNotBlank()
        val sb = StringBuilder(BOM)
        sb.append("Name,Given Name,Family Name,Phone 1 - Label,Phone 1 - Value")
        sb.append(if (withLabel) ",Labels\n" else "\n")
        for ((number, name) in entries) {
            val full = name.ifBlank { "${fallbackPrefix.trim()} $number".trim() }
            val cols = mutableListOf(full, full, "", "Mobile", number)
            if (withLabel) cols += label.trim()
            sb.append(cols.joinToString(",") { esc(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
