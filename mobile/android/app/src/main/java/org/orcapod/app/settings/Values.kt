package org.orcapod.app.settings

/**
 * Serialized option values (the engine's own ConfigOption::serialize forms)
 * split into editable elements and joined back.
 *
 * Vector options hold one element per extruder or filament: numbers, bools
 * and enums are comma-separated, strings are C-escaped and quoted, joined by
 * ';'. Points and point lists are edited as their raw text.
 */
object Values {
    fun elements(def: OptionDef, serialized: String): List<String> = when {
        !def.vector -> listOf(if (def.type == "string") unescape(serialized) else serialized)
        def.type == "string" -> splitStrings(serialized)
        def.type == "point" || def.type.endsWith("groups") -> listOf(serialized)
        serialized.isEmpty() -> emptyList()
        else -> serialized.split(',')
    }

    fun join(def: OptionDef, elements: List<String>): String = when {
        !def.vector -> if (def.type == "string") escape(elements.firstOrNull().orEmpty()) else elements.firstOrNull().orEmpty()
        def.type == "string" -> elements.joinToString(";") { "\"" + escape(it) + "\"" }
        def.type == "point" || def.type.endsWith("groups") -> elements.firstOrNull().orEmpty()
        else -> elements.joinToString(",")
    }

    /** Replaces element [index] of [serialized], keeping the others. */
    fun withElement(def: OptionDef, serialized: String, index: Int, value: String): String {
        val list = elements(def, serialized).toMutableList()
        if (index !in list.indices) return join(def, listOf(value))
        list[index] = value
        return join(def, list)
    }

    /** Desktop escape_string_cstyle. */
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\r' -> b.append("\\r")
            '\n' -> b.append("\\n")
            '\\', '"' -> b.append('\\').append(c)
            else -> b.append(c)
        }
        return b.toString()
    }

    /** Desktop unescape_string_cstyle. */
    fun unescape(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    else -> b.append(n)
                }
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }

    /** Desktop unescape_strings_cstyle: "a";"b" (quotes optional). */
    private fun splitStrings(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i <= s.length) {
            val cur = StringBuilder()
            if (i < s.length && s[i] == '"') {
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) {
                        cur.append(when (val n = s[i + 1]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> n })
                        i += 2
                    } else {
                        cur.append(s[i]); i++
                    }
                }
                i++ // closing quote
            } else {
                while (i < s.length && s[i] != ';') { cur.append(s[i]); i++ }
            }
            out.add(cur.toString())
            // Skip to past the separator.
            while (i < s.length && s[i] != ';') i++
            i++
        }
        return out
    }
}
