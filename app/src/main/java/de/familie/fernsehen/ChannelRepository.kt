package de.familie.fernsehen

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Senderliste: zuerst die zuletzt aus dem Internet geladene Kopie,
 * sonst die in die App eingebaute Liste. Im Hintergrund wird die
 * Liste aus dem GitHub-Repo aktualisiert.
 */
class ChannelRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("fernsehen", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "sender.m3u")

    fun loadLocal(): List<Channel> {
        if (cacheFile.exists()) {
            val cached = runCatching { M3uParser.parse(cacheFile.readText()) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return cached
        }
        return runCatching {
            context.assets.open("sender.m3u").bufferedReader().use { M3uParser.parse(it.readText()) }
        }.getOrDefault(emptyList())
    }

    /** Läuft im Hintergrund. Gibt null zurück, wenn nichts Brauchbares geladen wurde. */
    fun fetchRemote(): List<Channel>? {
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
                cacheFile.writeText(text)
                list
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveLast(channel: Channel) {
        prefs.edit().putString("last_channel", channel.name).apply()
    }

    fun findLastIndex(list: List<Channel>): Int {
        val name = prefs.getString("last_channel", null) ?: return 0
        return list.indexOfFirst { it.name == name }.coerceAtLeast(0)
    }
}
