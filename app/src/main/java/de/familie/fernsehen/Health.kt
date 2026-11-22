package de.familie.fernsehen

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Merkt sich, welche Sender von dieser Box aus erreichbar sind.
 * Ein Sender, der an 3 verschiedenen Tagen nicht erreichbar war, wird
 * ausgeblendet und nach einer Woche erneut geprüft.
 */
class HealthStore(dir: File) {

    enum class State { UNKNOWN, OK, FAIL, HIDDEN }

    private class Entry(var failDays: Int, var lastFailDay: Int, var lastCheckDay: Int, var lastOk: Boolean)

    private val file = File(dir, "erreichbarkeit.json")
    private val data = ConcurrentHashMap<String, Entry>()
    @Volatile private var dirty = false

    init {
        runCatching {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val p = json.optString(k).split(",")
                    if (p.size == 4) {
                        data[k] = Entry(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3] == "1")
                    }
                }
            }
        }
    }

    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    fun report(key: String, ok: Boolean) {
        val day = today()
        val e = data.getOrPut(key) { Entry(0, -1, day, ok) }
        synchronized(e) {
            e.lastCheckDay = day
            e.lastOk = ok
            if (ok) {
                e.failDays = 0
            } else if (e.lastFailDay != day) {
                e.failDays++
                e.lastFailDay = day
            }
        }
        dirty = true
    }

    fun state(key: String): State {
        val e = data[key] ?: return State.UNKNOWN
        return when {
            e.failDays >= 3 && today() - e.lastCheckDay < 7 -> State.HIDDEN
            !e.lastOk -> State.FAIL
            else -> State.OK
        }
    }

    fun checkedToday(key: String): Boolean = data[key]?.lastCheckDay == today()

    fun flush(executor: Executor) {
        if (!dirty) return
        dirty = false
        runCatching {
            executor.execute {
                val json = JSONObject()
                for ((k, e) in data) {
                    json.put(k, "${e.failDays},${e.lastFailDay},${e.lastCheckDay},${if (e.lastOk) 1 else 0}")
                }
                runCatching { file.writeText(json.toString()) }
            }
        }
    }
}

/** Prüft, ob ein Sender antwortet (lädt nur die ersten Bytes). */
object StreamChecker {

    fun check(channel: Channel): Boolean = channel.urls.take(2).any { checkUrl(it) }

    private fun checkUrl(s: StreamUrl): Boolean = try {
        val conn = URL(s.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", s.userAgent ?: DEFAULT_USER_AGENT)
        s.referrer?.let { conn.setRequestProperty("Referer", it) }
        try {
            if (conn.responseCode !in 200..299) {
                false
            } else {
                val buf = ByteArray(512)
                conn.inputStream.use { it.read(buf) } > 0
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }
}
