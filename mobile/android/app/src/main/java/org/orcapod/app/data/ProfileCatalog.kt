package org.orcapod.app.data

import org.json.JSONException
import org.json.JSONObject

/**
 * The printers and filaments in the bundled profiles of some brands, grouped
 * the way a person picks them: printers by model ("Bambu Lab P1S", whatever
 * the nozzle), filaments by product ("Bambu PLA Basic", whatever printer
 * variant). Read from the profile files, so it covers brands whose presets
 * the engine has not loaded.
 */
class ProfileCatalog(val printers: List<Group>, val filaments: List<Group>) {
    /**
     * A pickable item: [name] shown, [brand] it is listed under, [presets] it
     * stands for, and for a filament its material [type] ("PLA", "PETG", ...).
     */
    data class Group(val brand: String, val name: String, val presets: Set<String>, val type: String = "")

    val printerPresets: Set<String> get() = printers.flatMapTo(HashSet()) { it.presets }
    val filamentPresets: Set<String> get() = filaments.flatMapTo(HashSet()) { it.presets }

    companion object {
        /**
         * Builds the catalog from profile files as (path, JSON text), paths
         * relative to the profiles root (`BBL/machine/x.json`). Only
         * instantiated presets count; templates and model files are skipped.
         */
        fun parse(files: Sequence<Pair<String, String>>): ProfileCatalog {
            val printers = HashMap<Pair<String, String>, MutableSet<String>>()
            val filaments = HashMap<String, MutableSet<String>>()
            val makers = HashMap<String, String>()
            for ((path, text) in files) {
                val parts = path.split('/')
                if (parts.size < 3) continue
                val kind = parts[1]
                if (kind != "machine" && kind != "filament") continue
                val json = try {
                    JSONObject(text)
                } catch (_: JSONException) {
                    continue
                }
                if (json.optString("instantiation") != "true") continue
                val name = json.optString("name").takeIf { it.isNotEmpty() } ?: continue
                if (kind == "machine") {
                    if (json.optString("type") != "machine") continue
                    val model = json.optString("printer_model").ifEmpty { name }
                    printers.getOrPut(VendorNames.display(parts[0]) to model) { HashSet() } += name
                } else {
                    if (json.optString("type", "filament") != "filament") continue
                    val product = productOf(name)
                    // The library files sit in one folder per maker; vendor bundles name the maker first.
                    if (parts.size > 3) makers[product] = parts[2]
                    filaments.getOrPut(product) { HashSet() } += name
                }
            }
            val byMaker = filaments.mapKeys { (product, _) -> (makers[product] ?: product.substringBefore(' ')) to product }
            return ProfileCatalog(groups(printers, filaments = false), groups(byMaker, filaments = true))
        }

        /** Filament makers most people buy from, listed first; the rest go under "More brands". */
        val COMMON_MAKERS = listOf(
            "Generic", "Bambu", "Polymaker", "Overture", "eSUN", "Elegoo", "SUNLU", "Prusament",
            "Creality", "Anycubic", "Hatchbox", "Inland", "Jayo", "Sovol", "Qidi", "Kingroon",
        )

        /** Materials most prints use, offered first; the rest go under "More types". */
        val COMMON_TYPES = listOf("PLA", "PETG", "ABS", "ASA", "TPU", "PA", "PC")

        // Longer names first, so "PETG" wins over "PET" and "PCTG" over "PC".
        private val TYPES = listOf(
            "PETG", "PCTG", "PLA", "ABS", "ASA", "TPU", "TPE", "PVA", "BVOH", "HIPS", "PPS", "PPA",
            "PEEK", "PEKK", "PEI", "PET", "PHA", "SBS", "EVA", "COPE", "PA", "PC", "PP", "PE",
        )

        /**
         * The material of a filament product, read from its name: "Bambu PLA
         * Basic" → PLA, "Overture PETG-CF" → PETG, "Bambu PA6-CF" → PA, "PolyMax
         * Nylon" → PA; "Other" when no known material is named.
         */
        fun typeOf(product: String): String {
            for (raw in product.split(' ', '-', '_')) {
                // A lowercase lead letter marks a variant: "ePLA", "rPETG", "rABS".
                val word = (if (raw.length > 3 && raw[0].isLowerCase()) raw.substring(1) else raw).uppercase()
                if (word.startsWith("NYLON")) return "PA"
                if (word.startsWith("FLEX")) return "TPU"
                for (t in TYPES) {
                    if (!word.startsWith(t)) continue
                    val rest = word.substring(t.length)
                    // "PLA", "PLA+", "PA6", "PA12", "PAHT", "PACF" match; "PLAY" or "PEBA" do not.
                    val ok = rest.isEmpty() || !rest[0].isLetter() ||
                        (t == "PA" && (rest.startsWith("HT") || rest.startsWith("CF") || rest.startsWith("GF")))
                    if (ok) return if (t == "COPE") "CoPE" else t
                }
            }
            return "Other"
        }

        /** "Bambu PLA Basic @BBL X1C" → "Bambu PLA Basic". */
        fun productOf(preset: String): String = preset.substringBefore(" @").trim()

        private fun groups(map: Map<Pair<String, String>, Set<String>>, filaments: Boolean): List<Group> =
            map.map { (k, v) -> Group(k.first, k.second, v, if (filaments) typeOf(k.second) else "") }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Group::brand).thenBy(String.CASE_INSENSITIVE_ORDER, Group::name))
    }
}
