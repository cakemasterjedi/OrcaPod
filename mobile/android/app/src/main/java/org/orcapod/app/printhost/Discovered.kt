package org.orcapod.app.printhost

/** A printer found on the local network, ready to prefill a connection. */
data class DiscoveredPrinter(
    val name: String,
    val type: HostType,
    val host: String,
    val serial: String = "",
    val model: String = "",
) {
    companion object {
        /**
         * Bambu printers announce themselves with SSDP-style NOTIFY messages
         * broadcast to UDP port 2021, carrying the IP, serial and model.
         */
        fun parseBambuNotify(message: String): DiscoveredPrinter? {
            val headers = message.lineSequence()
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
                .toMap()
            if (headers["nt"]?.contains("bambulab", true) != true) return null
            val host = headers["location"]?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
            val serial = headers["usn"].orEmpty()
            val model = headers["devmodel.bambu.com"].orEmpty()
            val name = headers["devname.bambu.com"]?.takeIf { it.isNotEmpty() } ?: "Bambu Lab ${modelName(model)}".trim()
            return DiscoveredPrinter(name, HostType.BAMBU_LAN, host, serial, modelName(model))
        }

        /** Bambu's internal model codes, as the printers announce them. */
        fun modelName(code: String) = when (code) {
            "3DPrinter-X1-Carbon", "BL-P001" -> "X1 Carbon"
            "3DPrinter-X1", "BL-P002" -> "X1"
            "C11" -> "P1P"
            "C12" -> "P1S"
            "C13" -> "X1E"
            "N1" -> "A1 mini"
            "N2S" -> "A1"
            else -> code
        }
    }
}
