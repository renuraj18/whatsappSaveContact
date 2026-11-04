package com.renu.waexporter

object PhoneUtil {
    // WhatsApp wraps numbers in invisible direction marks (LRE/PDF etc.) and uses non-breaking spaces.
    private val INVISIBLE = Regex("[‎‏‪-‮⁦-⁩ ]")
    private val PHONE = Regex("^\\+?[0-9][0-9 ()\\-.]{5,}$")

    fun clean(raw: CharSequence?): String =
        raw?.toString()?.replace(INVISIBLE, " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    /** Returns "+919876543210" style number if the text is a phone number, else null. */
    fun normalize(raw: CharSequence?): String? {
        val t = clean(raw)
        if (!PHONE.matches(t)) return null
        val digits = t.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return if (t.startsWith("+")) "+$digits" else digits
    }

    /** WhatsApp shows a non-contact's own profile name as "~ Name". */
    fun pushName(raw: CharSequence?): String? {
        val t = clean(raw)
        if (!t.startsWith("~")) return null
        val name = t.removePrefix("~").trim()
        if (name.isEmpty() || name.contains(':') || normalize(name) != null) return null
        return name
    }
}
