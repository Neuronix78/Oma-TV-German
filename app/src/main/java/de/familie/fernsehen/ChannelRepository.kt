package de.familie.fernsehen

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Meine Sender" = sender.m3u aus dem GitHub-Repo. GitHub hat immer das letzte Wort.
 *
 * Reihenfolge der Quellen:
 *  1. sender.m3u frisch von GitHub (bei jedem Start von OmaTV)
 *  2. die zuletzt von GitHub geladene Kopie auf der Box
 *  3. die Notfall-Kopie, die beim Bau in die App gepackt wurde
 */
class FavoritesStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("fernsehen", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "sender.m3u")

    init {
        // Aus der Vorversion: an der Box bearbeitete Liste gibt es nicht mehr
        File(context.filesDir, "favoriten.json").delete()
    }

    /** Letzte Kopie von GitHub, sonst die Notfall-Kopie aus der App. */
    fun loadLocal(): List<Channel> {
        if (cacheFile.exists()) {
            val cached = runCatching { M3uParser.parse(cacheFile.readText()) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return cached
        }
        return loadBuiltIn()
    }

    fun loadBuiltIn(): List<Channel> = runCatching {
        context.assets.open("sender.m3u").bufferedReader().use { M3uParser.parse(it.readText()) }
    }.getOrDefault(emptyList())

    /** Läuft im Hintergrund. null, wenn GitHub nicht erreichbar war oder die Liste leer ist. */
    fun fetchDefaultRemote(): List<Channel>? {
        val address = BuildConfig.PLAYLIST_URL
        if (address.isBlank()) return null
        return try {
            // Zeitstempel umgeht den Zwischenspeicher von GitHub: Änderungen sind sofort da
            val conn = URL("$address?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val list = M3uParser.parse(text)
                if (list.isEmpty()) return null
                cacheFile.writeText(text)
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
}
