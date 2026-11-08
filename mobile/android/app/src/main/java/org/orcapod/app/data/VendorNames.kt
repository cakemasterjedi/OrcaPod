package org.orcapod.app.data

/**
 * Names shown for printer brands. The bundled profiles name a brand by its
 * folder (`BBL`) and by the `name` in its vendor JSON (`Bambulab`), and the
 * engine depends on both, so they are only translated for display here.
 */
object VendorNames {
    private val FULL = mapOf(
        "BBL" to "Bambu Lab",
        "Bambulab" to "Bambu Lab",
        "iQ" to "innovatiQ",
        "OrcaArena" to "Orca Arena",
        "Positron3D" to "Positron 3D",
        "Ratrig" to "RatRig",
        "Vzbot" to "VzBot",
        "Custom Printer" to "Custom",
    )

    /** The brand name to show for a vendor folder id or vendor profile name. */
    fun display(vendor: String): String = FULL[vendor] ?: vendor

    /** Vendor ids ordered by their shown names. */
    fun sorted(vendors: Collection<String>): List<String> =
        vendors.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { display(it) })
}
