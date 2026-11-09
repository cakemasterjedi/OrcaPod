package org.orcapod.app.data

import org.orcapod.app.engine.PresetEntry

/**
 * The presets of one kind the user picked to show in its picker, so a long
 * printer or filament list shrinks to what they own.
 *
 * [seen] holds every preset that was listed when the user last picked, so a
 * preset that appears later (an imported filament, a profile update, another
 * brand's printers) is shown until the user decides on it.
 */
data class PresetChoice(val shown: Set<String> = emptySet(), val seen: Set<String> = emptySet()) {
    /**
     * The entries to list. With nothing picked among [entries] (no choice
     * yet, or none of the picks is compatible or loaded) every entry is
     * listed; [current] is always listed.
     */
    fun filter(entries: List<PresetEntry>, current: String): List<PresetEntry> {
        if (entries.none { it.name in shown }) return entries
        return entries.filter { it.name in shown || it.name !in seen || it.name == current }
    }

    /** True when [filter] hides some of [entries]. */
    fun hidesAny(entries: List<PresetEntry>, current: String) = filter(entries, current).size < entries.size

    /**
     * The choice after the user picked [picked] out of [entries]. Picks among
     * other entries (other brands, incompatible filaments) are kept; picking
     * nothing lists every entry again.
     */
    fun pick(entries: List<PresetEntry>, picked: Set<String>): PresetChoice =
        pick(entries.mapTo(HashSet()) { it.name }, picked)

    /** As [pick], over preset names that need not be loaded, such as a whole brand's. */
    fun pick(names: Set<String>, picked: Set<String>): PresetChoice =
        PresetChoice(shown = shown - names + (picked intersect names), seen = seen + names)
}
