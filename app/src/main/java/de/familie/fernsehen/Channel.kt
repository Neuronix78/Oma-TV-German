package de.familie.fernsehen

data class Channel(val name: String, val url: String, val logo: String)

/** Liest eine M3U-Senderliste (#EXTINF-Zeile, darunter die Stream-Adresse). */
object M3uParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingLogo = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attribute.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = attribute.replace(line, "").substringAfter(",", "").trim()
                    pendingName = name.ifEmpty { attrs["tvg-name"].orEmpty().trim() }
                    pendingLogo = attrs["tvg-logo"].orEmpty()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val name = pendingName
                    if (name != null) {
                        result += Channel(
                            name = name.ifEmpty { "Sender ${result.size + 1}" },
                            url = line,
                            logo = pendingLogo
                        )
                    }
                    pendingName = null
                    pendingLogo = ""
                }
            }
        }
        return result
    }
}
