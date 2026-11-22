package de.familie.fernsehen

import java.text.Normalizer

/** Browser-ähnliche Kennung: manche Sender liefern sonst nichts aus. */
const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** Eine Stream-Adresse, ggf. mit Referer/User-Agent, die der Server verlangt. */
data class StreamUrl(val url: String, val referrer: String? = null, val userAgent: String? = null)

/**
 * Ein Sender. [key] ist eindeutig: die Kennung aus der Gesamtliste (z. B. "DasErste.de")
 * oder "fav:<name>" für Sender aus sender.m3u. [urls] enthält Ersatzadressen,
 * die der Player der Reihe nach probiert.
 */
data class Channel(
    val key: String,
    val name: String,
    val country: String,
    val logo: String,
    val urls: List<StreamUrl>,
    val group: String = ""
) {
    val isFromM3u: Boolean get() = key.startsWith("fav:")
}

/** Vereinheitlicht Sendernamen, damit z. B. "ZDF HD" und "ZDF" als gleich erkannt werden. */
object Names {
    private val stopWords = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "tv", "fernsehen", "television",
        "deutschland", "germany", "german", "de", "live", "channel"
    )
    private val symbols = Regex("[ⓎⓉⒹⓈⒼ]")
    private val quality = Regex("\\s*\\((\\d{3,4}[pi]|SD|HD)\\)\\s*")
    private val squareBrackets = Regex("\\[.*?]")
    private val umlautE = Regex("([aou])e(?=[^aeiouy]|$)")
    private val separators = Regex("[^a-z0-9]+")

    fun normalize(name: String): String {
        var s = symbols.replace(name, "")
        s = quality.replace(s, " ")
        s = squareBrackets.replace(s, " ").replace("ß", "ss")
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
        s = umlautE.replace(s, "$1")
        val words = s.split(separators).filter { it.isNotEmpty() && it !in stopWords }
        return if (words.isNotEmpty()) words.joinToString("") else s.replace(separators, "")
    }
}

/** Liest eine M3U-Senderliste (#EXTINF-Zeile, darunter die Stream-Adresse). */
object M3uParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        val usedKeys = HashSet<String>()
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
                        val display = name.ifEmpty { "Sender ${result.size + 1}" }
                        var key = "fav:" + Names.normalize(display)
                        var n = 2
                        while (!usedKeys.add(key)) key = "fav:" + Names.normalize(display) + "#" + n++
                        result += Channel(key, display, "DE", pendingLogo, listOf(StreamUrl(line)))
                    }
                    pendingName = null
                    pendingLogo = ""
                }
            }
        }
        return result
    }
}
