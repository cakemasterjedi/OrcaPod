package org.orcapod.app.settings

import org.json.JSONArray
import org.json.JSONObject
import org.orcapod.app.engine.PresetKind

/** Desktop option visibility levels (ConfigOptionMode). */
enum class OptionMode(val code: String, val label: String) {
    SIMPLE("simple", "Simple"),
    ADVANCED("advanced", "Advanced"),
    EXPERT("expert", "Expert"),
    DEVELOP("develop", "Developer");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: SIMPLE
    }
}

/** One engine option definition (ConfigOptionDef), from orca_config_option_defs. */
data class OptionDef(
    val key: String,
    /** float, int, string, percent, float_or_percent, point, bool, enum, ... */
    val type: String,
    val vector: Boolean,
    val label: String,
    val fullLabel: String,
    val category: String,
    val tooltip: String,
    val sidetext: String,
    val mode: OptionMode,
    /** GUIType name ("color", "f_enum_open", "i_enum_open", "select_open", "one_string", ...) or "". */
    val gui: String,
    val min: Double?,
    val max: Double?,
    val enumValues: List<String>,
    val enumLabels: List<String>,
    val multiline: Boolean,
    val readonly: Boolean,
    val default: String,
) {
    /** The label to show on its own, outside its group. */
    val title: String get() = fullLabel.ifEmpty { label }.ifEmpty { key }

    val isBool get() = type == "bool"
    val isNumeric get() = type in NUMERIC
    val isColor get() = gui == "color"
    /** A closed list of values; open enums also take free input. */
    val hasChoices get() = enumValues.isNotEmpty()
    val isOpenEnum get() = gui == "f_enum_open" || gui == "i_enum_open" || gui == "select_open"
    val isCode get() = multiline || key.endsWith("_gcode") || key == "post_process"

    fun choiceLabel(value: String): String {
        val i = enumValues.indexOf(value)
        return if (i >= 0) enumLabels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: value else value
    }

    companion object {
        private val NUMERIC = setOf("float", "int", "percent", "float_or_percent")

        fun parseAll(json: String): Map<String, OptionDef> {
            val root = JSONObject(json)
            val out = HashMap<String, OptionDef>(root.length())
            for (key in root.keys()) out[key] = parse(key, root.getJSONObject(key))
            return out
        }

        private fun parse(key: String, o: JSONObject) = OptionDef(
            key = key,
            type = o.optString("type"),
            vector = o.optBoolean("vector"),
            label = o.optString("label"),
            fullLabel = o.optString("full_label"),
            category = o.optString("category"),
            tooltip = o.optString("tooltip"),
            sidetext = o.optString("sidetext"),
            mode = OptionMode.of(o.optString("mode")),
            gui = o.optString("gui"),
            min = if (o.has("min")) o.getDouble("min") else null,
            max = if (o.has("max")) o.getDouble("max") else null,
            enumValues = o.optJSONArray("enum_values").strings(),
            enumLabels = o.optJSONArray("enum_labels").strings(),
            multiline = o.optBoolean("multiline"),
            readonly = o.optBoolean("readonly"),
            default = o.optString("default"),
        )

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
    }
}

/** A titled list of options, as in a desktop settings tab group. */
data class OptionGroup(val title: String, val keys: List<String>)

/** A page of a desktop settings tab ("Quality", "Strength", ...). */
data class OptionPage(val title: String, val groups: List<OptionGroup>)

/**
 * The desktop settings tabs' structure (assets/settings_layout.json, made by
 * tools/gen_settings_layout.py from Tab.cpp): pages and groups per preset kind.
 */
class SettingsLayout(private val pages: Map<PresetKind, List<OptionPage>>) {
    fun pages(kind: PresetKind): List<OptionPage> = pages[kind].orEmpty()

    /** Pages of [kind] keeping only [keys] (in layout order), dropping emptied groups. */
    fun filtered(kind: PresetKind, keys: Set<String>): List<OptionPage> =
        pages(kind).mapNotNull { page ->
            val groups = page.groups.mapNotNull { g -> g.keys.filter { it in keys }.takeIf { it.isNotEmpty() }?.let { OptionGroup(g.title, it) } }
            groups.takeIf { it.isNotEmpty() }?.let { OptionPage(page.title, it) }
        }

    /** Where [key] lives in the process tab, as "Page › Group", or null. */
    fun location(kind: PresetKind, key: String): String? {
        for (p in pages(kind)) for (g in p.groups) if (key in g.keys) return "${p.title} › ${g.title}"
        return null
    }

    companion object {
        fun parse(json: String): SettingsLayout {
            val root = JSONObject(json)
            val kinds = mapOf("process" to PresetKind.PROCESS, "filament" to PresetKind.FILAMENT, "printer" to PresetKind.PRINTER)
            val out = HashMap<PresetKind, List<OptionPage>>()
            for ((name, kind) in kinds) {
                val pages = root.optJSONArray(name) ?: continue
                out[kind] = List(pages.length()) { i ->
                    val p = pages.getJSONObject(i)
                    val groups = p.getJSONArray("groups")
                    OptionPage(p.getString("title"), List(groups.length()) { j ->
                        val g = groups.getJSONObject(j)
                        val keys = g.getJSONArray("options")
                        OptionGroup(g.getString("title"), List(keys.length()) { keys.getString(it) })
                    })
                }
            }
            return SettingsLayout(out)
        }
    }
}

/** Parses a {key: serialized value} JSON dump. */
fun parseValues(json: String): Map<String, String> {
    val o = JSONObject(json)
    val out = HashMap<String, String>(o.length())
    for (k in o.keys()) out[k] = o.optString(k)
    return out
}

/** Parses a JSON string array. */
fun parseKeys(json: String): Set<String> {
    val a = JSONArray(json)
    return HashSet<String>(a.length()).apply { for (i in 0 until a.length()) add(a.getString(i)) }
}
