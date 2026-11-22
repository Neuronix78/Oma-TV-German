package de.familie.fernsehen

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.util.Locale

/** Eine Gruppe in "Alle Sender" (Hauptsender, Nachrichten, Regional, …). */
class ChannelGroup(val name: String, val channels: List<Channel>)

/** Die Gesamtliste aller deutschen Sender (alle-sender.json), nach Gruppen gegliedert. */
class Catalog(val channels: List<Channel>) {

    val byKey: Map<String, Channel> = channels.associateBy { it.key }

    private val byUrl: Map<String, Channel> = HashMap<String, Channel>().apply {
        for (ch in channels) for (u in ch.urls) putIfAbsent(u.url, ch)
    }

    private val byName: Map<String, Channel> = HashMap<String, Channel>().apply {
        for (ch in channels) putIfAbsent(ch.country + ":" + Names.normalize(ch.name), ch)
    }

    /** Gruppen in der Reihenfolge der Liste, am Ende "Alle A–Z". */
    val groups: List<ChannelGroup> by lazy {
        val collator = Collator.getInstance(Locale.GERMAN)
        val byGroup = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in channels) byGroup.getOrPut(ch.group.ifEmpty { "Weitere Sender" }) { mutableListOf() }.add(ch)
        byGroup.map { (name, list) -> ChannelGroup(name, list) } +
            ChannelGroup(ALL_GROUP, channels.sortedWith { a, b -> collator.compare(a.name, b.name) })
    }

    fun groupOf(channel: Channel): String = byKey[channel.key]?.group?.ifEmpty { null } ?: ""

    companion object {
        const val ALL_GROUP = "Alle A–Z"
    }

    /** Findet den passenden Eintrag für einen Sender aus sender.m3u (über Adresse oder Namen). */
    fun match(channel: Channel): Channel? {
        for (u in channel.urls) byUrl[u.url]?.let { return it }
        return byName[channel.country + ":" + Names.normalize(channel.name)]
    }
}

class CatalogRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("fernsehen", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "alle-sender.json")

    /** Zuletzt geladene Liste, sonst die in die App eingebaute Kopie. Läuft im Hintergrund. */
    fun loadLocal(): Catalog? {
        if (cacheFile.exists()) {
            runCatching { cacheFile.inputStream().use { parse(it) } }.getOrNull()?.let { return it }
        }
        return runCatching { context.assets.open("alle-sender.json").use { parse(it) } }.getOrNull()
    }

    /** Lädt die Liste nur, wenn sie sich geändert hat. null = keine Änderung oder Fehler. */
    fun fetchRemote(): Catalog? {
        val address = BuildConfig.CATALOG_URL
        if (address.isBlank()) return null
        val tmp = File(context.filesDir, "alle-sender.json.tmp")
        return try {
            val conn = URL(address).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            val etag = prefs.getString("catalog_etag", null)
            if (etag != null && cacheFile.exists()) conn.setRequestProperty("If-None-Match", etag)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val catalog = tmp.inputStream().use { parse(it) } ?: return null
                if (!tmp.renameTo(cacheFile)) {
                    tmp.copyTo(cacheFile, overwrite = true)
                }
                prefs.edit().putString("catalog_etag", conn.getHeaderField("ETag")).apply()
                catalog
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun parse(input: InputStream): Catalog? {
        val list = ArrayList<Channel>(16_000)
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "channels") {
                    r.beginArray()
                    while (r.hasNext()) readChannel(r)?.let { list.add(it) }
                    r.endArray()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
        return if (list.isEmpty()) null else Catalog(list)
    }

    private fun readChannel(r: JsonReader): Channel? {
        var key = ""
        var name = ""
        var country = "DE"
        var logo = ""
        var group = ""
        val urls = ArrayList<StreamUrl>(2)
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "k" -> key = r.nextString()
                "n" -> name = r.nextString()
                "c" -> country = r.nextString()
                "l" -> logo = r.nextString()
                "g" -> group = r.nextString()
                "u" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            var u = ""
                            var ref: String? = null
                            var ua: String? = null
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "u" -> u = r.nextString()
                                    "r" -> ref = r.nextString()
                                    "a" -> ua = r.nextString()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (u.isNotEmpty()) urls.add(StreamUrl(u, ref, ua))
                        } else {
                            urls.add(StreamUrl(r.nextString()))
                        }
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (key.isEmpty() || name.isEmpty() || urls.isEmpty()) null
        else Channel(key, name, country, logo, urls, group)
    }
}
