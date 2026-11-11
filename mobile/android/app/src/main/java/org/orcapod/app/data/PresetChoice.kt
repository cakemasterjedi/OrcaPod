package org.orcapod.app.data

import org.orcapod.app.engine.PresetEntry

/**
 * The presets of one kind the user picked to show in its picker, so a long
 * printer or filament list shrinks to what they own.
 *
 * Once something is picked, only the picks are listed: system presets the
 * user did not tick stay hidden however they came to be loaded. [seen] holds
 * every preset that was listed when the user last picked, so one of the
 * user's own presets that appears later (an imported filament) is listed
 * until they decide on it.
 *
 * With [emptyShowsAll] (printers), a choice with nothing picked lists every
 * entry. Without it (filaments, whose library runs to thousands), nothing
 * picked lists only the current preset and the user's own new presets.
 */
data class PresetChoice(
    val shown: Set<String> = emptySet(),
    val seen: Set<String> = emptySet(),
    val emptyShowsAll: Boolean = true,
) {
    /**
     * The entries to list: the picks, [current], and the user's own presets
     * they have not decided on yet. With [emptyShowsAll] and nothing picked
     * among [entries] (no choice yet, or none of the picks is compatible or
     * loaded) every entry is listed.
     */
    fun filter(entries: List<PresetEntry>, current: String): List<PresetEntry> {
        if (emptyShowsAll && entries.none { it.name in shown }) return entries
        return entries.filter { it.name in shown || it.name == current || (it.isUser && it.name !in seen) }
    }

    /** True when [filter] hides some of [entries]. */
    fun hidesAny(entries: List<PresetEntry>, current: String) = filter(entries, current).size < entries.size

    /**
     * The choice after the user picked [picked] out of [entries]. Picks among
     * other entries (other brands, incompatible filaments) are kept.
     */
    fun pick(entries: List<PresetEntry>, picked: Set<String>): PresetChoice =
        pick(entries.mapTo(HashSet()) { it.name }, picked)

    /** As [pick], over preset names that need not be loaded, such as a whole brand's. */
    fun pick(names: Set<String>, picked: Set<String>): PresetChoice =
        copy(shown = shown - names + (picked intersect names), seen = seen + names)
}
