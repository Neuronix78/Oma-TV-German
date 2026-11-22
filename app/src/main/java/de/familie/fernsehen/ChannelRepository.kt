package de.familie.fernsehen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Meine Sender".
 *
 * Solange an der Box nichts bearbeitet wurde, gilt die Vorgabe sender.m3u aus dem
 * GitHub-Repo (wird automatisch aktualisiert). Sobald an der Box etwas geändert wird,
 * gilt die eigene Liste der Box (favoriten.json). "Auf Vorgabe zurücksetzen" kehrt
 * wieder zur GitHub-Vorgabe zurück.
 */
class FavoritesStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("fernsehen", Context.MODE_PRIVATE)
    private val favFile = File(context.filesDir, "favoriten.json")
    private val defaultCache = File(context.filesDir, "sender.m3u")

    var edited: Boolean
        get() = prefs.getBoolean("favs_edited", false) && favFile.exists()
        set(value) = prefs.edit().putBoolean("favs_edited", value).apply()

    fun load(): MutableList<Channel> {
        if (edited) {
            val own = runCatching { fromJson(favFile.readText()) }.getOrDefault(emptyList())
            if (own.isNotEmpty()) return own.toMutableList()
        }
        return loadDefaultLocal().toMutableList()
    }

    fun save(list: List<Channel>) {
        runCatching { favFile.writeText(toJson(list)) }
    }

    fun reset() {
        edited = false
        favFile.delete()
    }

    fun loadDefaultLocal(): List<Channel> {
        if (defaultCache.exists()) {
            val cached = runCatching { M3uParser.parse(defaultCache.readText()) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return cached
        }
        return runCatching {
            context.assets.open("sender.m3u").bufferedReader().use { M3uParser.parse(it.readText()) }
        }.getOrDefault(emptyList())
    }

    /** Läuft im Hintergrund. null, wenn nichts Brauchbares geladen wurde. */
    fun fetchDefaultRemote(): List<Channel>? {
        val address = BuildConfig.PLAYLIST_URL
        if (address.isBlank()) return null
        return try {
            val conn = URL(address).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val list = M3uParser.parse(text)
                if (list.isEmpty()) return null
                defaultCache.writeText(text)
                list
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveLastKey(key: String) {
        prefs.edit().putString("last_key", key).apply()
    }

    fun findLastIndex(list: List<Channel>): Int {
        val key = prefs.getString("last_key", null)
        if (key != null) {
            val i = list.indexOfFirst { it.key == key }
            if (i >= 0) return i
        }
        // Übernahme aus Version 1: dort wurde der Name gespeichert
        val name = prefs.getString("last_channel", null) ?: return 0
        return list.indexOfFirst { it.name == name }.coerceAtLeast(0)
    }

    private fun toJson(list: List<Channel>): String {
        val arr = JSONArray()
        for (ch in list) {
            val urls = JSONArray()
            for (u in ch.urls) {
                urls.put(JSONObject().apply {
                    put("u", u.url)
                    u.referrer?.let { put("r", it) }
                    u.userAgent?.let { put("a", it) }
                })
            }
            arr.put(JSONObject().apply {
                put("k", ch.key)
                put("n", ch.name)
                put("c", ch.country)
                put("l", ch.logo)
                put("u", urls)
            })
        }
        return arr.toString()
    }

    private fun fromJson(text: String): List<Channel> {
        val arr = JSONArray(text)
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urlsJson = o.optJSONArray("u") ?: continue
            val urls = ArrayList<StreamUrl>()
            for (j in 0 until urlsJson.length()) {
                val u = urlsJson.optJSONObject(j) ?: continue
                val url = u.optString("u", "")
                if (url.isEmpty()) continue
                urls.add(
                    StreamUrl(
                        url,
                        if (u.has("r")) u.optString("r") else null,
                        if (u.has("a")) u.optString("a") else null
                    )
                )
            }
            val key = o.optString("k", "")
            val name = o.optString("n", "")
            if (key.isEmpty() || name.isEmpty() || urls.isEmpty()) continue
            out.add(Channel(key, name, o.optString("c", "DE"), o.optString("l", ""), urls))
        }
        return out
    }
}
