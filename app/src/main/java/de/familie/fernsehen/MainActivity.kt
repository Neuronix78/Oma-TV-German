package de.familie.fernsehen

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Das Fernsehbild. Bedienung:
 *
 *  Fernsehbild
 *    hoch / runter        -> nächster / vorheriger Sender aus "Meine Sender"
 *    OK, links, rechts    -> Liste "Meine Sender"
 *    Zurück 3 s halten    -> "Fernsehen verlassen?" (Lite: schon kurzes Zurück)
 *
 *  Meine Sender
 *    OK                   -> umschalten; letzte Zeile "Alle Sender …"
 *    OK 3 s halten        -> Bearbeiten-Modus
 *
 *  Bearbeiten-Modus
 *    OK                   -> Sender anheben / ablegen, dazwischen hoch/runter = verschieben
 *    links                -> Sender entfernen (mit Rückfrage)
 *    Zurück               -> fertig
 *
 *  Alle Sender
 *    links Gruppen, rechts Sender, OK -> "Ansehen" / "Zu Meine Sender hinzufügen"
 *
 *  Ohne Internet steht "Warte auf Internet …" da; sobald die Verbindung steht,
 *  werden die Listen von GitHub geholt und das Bild startet von selbst.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity(), FavAdapter.Listener {

    // ---------- Ansichten ----------
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var infoBox: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoText: TextView
    private lateinit var listPanel: View
    private lateinit var listTitle: TextView
    private lateinit var listHint: TextView
    private lateinit var channelList: RecyclerView
    private lateinit var allPanel: View
    private lateinit var allEmpty: TextView
    private lateinit var groupList: RecyclerView
    private lateinit var allList: RecyclerView
    private lateinit var dialogPanel: View
    private lateinit var dialogTitle: TextView
    private lateinit var dialogText: TextView
    private lateinit var dialogButtons: List<TextView>

    // ---------- Daten ----------
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val checkPool: ExecutorService = Executors.newFixedThreadPool(6)
    private lateinit var favStore: FavoritesStore
    private lateinit var catalogRepo: CatalogRepository
    private lateinit var health: HealthStore
    private lateinit var favAdapter: FavAdapter
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var allAdapter: AllAdapter

    private var favorites: MutableList<Channel> = mutableListOf()
    private var favIndex = 0
    private var playing: Channel? = null
    private var urlIndex = 0
    private val workingUrl = HashMap<String, Int>()
    private var reportedOk = false
    private var catalog: Catalog? = null
    private var missingKeys: Set<String> = emptySet()

    // ---------- Wiedergabe ----------
    private var player: ExoPlayer? = null
    private var httpFactory: DefaultHttpDataSource.Factory? = null
    private var retryCount = 0

    // ---------- Bedienung ----------
    private var editMode = false
    private var movingIndex = -1
    private var allOpenedFromEdit = false
    private var selectedGroup = 0
    private var pendingGroup = -1
    @Volatile private var checkGeneration = 0
    private var dialogActions: List<() -> Unit> = emptyList()
    private var focusBeforeDialog: View? = null
    private var lastDefaultsRefresh = 0L
    private var waitingForNet = false
    private var networkCallbackRegistered = false

    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val hideList = Runnable { hideFavList() }
    private val closeAll = Runnable { closeAllPanels() }
    private val hideDialogTimer = Runnable { hideDialog() }
    private val showLoading = Runnable { showStatus(getString(R.string.status_loading)) }
    private val retry = Runnable { playCurrent() }
    private val delayedStart = Runnable { playCurrent() }
    private val applyGroup = Runnable { if (pendingGroup >= 0) selectGroup(pendingGroup) }
    /** Solange kein Internet da ist: alle 5 Sekunden nachsehen. */
    private val waitNet = object : Runnable {
        override fun run() {
            if (isOnline()) onNetworkBack() else handler.postDelayed(this, 5_000)
        }
    }

    /** Solange noch gar keine Senderliste da ist: alle 15 Sekunden erneut laden. */
    private val fetchUntilChannels = object : Runnable {
        override fun run() {
            if (playing != null) return
            refreshDefaults()
            refreshCatalog()
            handler.postDelayed(this, 15_000)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (waitingForNet) handler.postDelayed(waitNet, 1_500) }
        }
    }

    private val periodicRefresh = object : Runnable {
        override fun run() {
            refreshDefaults()
            refreshCatalog()
            handler.postDelayed(this, REFRESH_INTERVAL)
        }
    }

    private val backKey = KeyHold(handler, 3000, { onBackShort() }, { onBackLong() }) { true }
    private val okKey = KeyHold(handler, 3000, { onOkShort() }, { onOkLong() }) {
        listVisible() && !dialogVisible() && !allVisible()
    }

    companion object {
        private const val REFRESH_INTERVAL = 3 * 60 * 60 * 1000L
        private const val LIST_TIMEOUT = 10_000L
        private const val EDIT_TIMEOUT = 120_000L
        private const val ALL_TIMEOUT = 120_000L
        private const val DIALOG_TIMEOUT = 30_000L
        private const val MAX_CHECKS = 400
    }

    // =====================================================================
    // Lebenszyklus
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.status)
        infoBox = findViewById(R.id.infoBox)
        infoLogo = findViewById(R.id.infoLogo)
        infoText = findViewById(R.id.infoText)
        listPanel = findViewById(R.id.listPanel)
        listTitle = findViewById(R.id.listTitle)
        listHint = findViewById(R.id.listHint)
        channelList = findViewById(R.id.channelList)
        allPanel = findViewById(R.id.allPanel)
        allEmpty = findViewById(R.id.allEmpty)
        groupList = findViewById(R.id.countryList)
        allList = findViewById(R.id.allList)
        dialogPanel = findViewById(R.id.dialogPanel)
        dialogTitle = findViewById(R.id.dialogTitle)
        dialogText = findViewById(R.id.dialogText)
        dialogButtons = listOf(
            findViewById(R.id.dialogBtn1),
            findViewById(R.id.dialogBtn2),
            findViewById(R.id.dialogBtn3)
        )
        dialogButtons.forEachIndexed { i, button ->
            button.setOnClickListener {
                val action = dialogActions.getOrNull(i)
                hideDialog()
                action?.invoke()
            }
        }

        favStore = FavoritesStore(this)
        catalogRepo = CatalogRepository(this)
        health = HealthStore(filesDir)

        favorites = favStore.load()
        favIndex = favStore.findLastIndex(favorites)
        playing = favorites.getOrNull(favIndex)

        favAdapter = FavAdapter(this)
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.itemAnimator = null
        channelList.adapter = favAdapter
        favAdapter.submit(favorites)

        groupAdapter = GroupAdapter(
            onFocus = { pos -> scheduleGroup(pos) },
            onClick = { pos ->
                selectGroup(pos)
                focusFirstChannel()
            }
        )
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.itemAnimator = null
        groupList.adapter = groupAdapter

        allAdapter = AllAdapter(health) { index -> onAllChannelClick(index) }
        allList.layoutManager = LinearLayoutManager(this)
        allList.itemAnimator = null
        allList.adapter = allAdapter

        // Gesamtliste im Hintergrund laden (eigene Kopie oder eingebaute Notfall-Kopie)
        runInBackground {
            val local = catalogRepo.loadLocal()
            if (local != null) handler.post { if (catalog == null) applyCatalog(local) }
        }
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        playCurrent()
        showInfo()
        refreshDefaults()
        refreshCatalog()
        handler.removeCallbacks(periodicRefresh)
        handler.postDelayed(periodicRefresh, REFRESH_INTERVAL)
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        handler.removeCallbacks(showLoading)
        handler.removeCallbacks(periodicRefresh)
        handler.removeCallbacks(waitNet)
        handler.removeCallbacks(fetchUntilChannels)
        waitingForNet = false
        unregisterNetworkCallback()
        playerView.player = null
        player?.release()
        player = null
        health.flush(io)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        checkPool.shutdownNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Taste gedrückt, während das Fernsehen schon läuft
        closeAllPanels()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- Internetverbindung ----------

    /** true, wenn die Box mit einem Netzwerk verbunden ist (auch von Hintergrund-Threads aus nutzbar). */
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        } catch (e: Exception) {
            true
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            // Kein Problem: das Warten funktioniert auch ohne, über waitNet
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
    }

    private fun waitForInternet() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(showLoading)
        showStatus(getString(R.string.status_waiting_net))
        if (waitingForNet) return
        waitingForNet = true
        handler.postDelayed(waitNet, 5_000)
    }

    /** Internet ist (wieder) da: aktuelle Listen von GitHub holen und das Bild starten. */
    private fun onNetworkBack() {
        if (!waitingForNet) return
        waitingForNet = false
        handler.removeCallbacks(waitNet)
        hideStatus()
        refreshDefaults()
        refreshCatalog()
        retryCount = 0
        playCurrent()
    }

    private fun runInBackground(task: () -> Unit) {
        if (io.isShutdown) return
        runCatching { io.execute(task) }
    }

    // =====================================================================
    // Wiedergabe
    // =====================================================================

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    handler.removeCallbacks(showLoading)
                    handler.postDelayed(showLoading, 1500)
                }
                Player.STATE_READY -> {
                    handler.removeCallbacks(showLoading)
                    hideStatus()
                    retryCount = 0
                    val ch = playing
                    if (ch != null) {
                        workingUrl[ch.key] = urlIndex
                        if (!reportedOk) {
                            reportedOk = true
                            health.report(ch.key, true)
                        }
                    }
                }
                Player.STATE_ENDED -> scheduleRetry()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handler.removeCallbacks(showLoading)
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Ohne Internet: kein Senderfehler, sondern warten
            if (!isOnline()) {
                waitForInternet()
                return
            }
            val ch = playing ?: return
            // Erst alle Ersatzadressen probieren, bevor wir aufgeben
            if (urlIndex + 1 < ch.urls.size) {
                urlIndex++
                playCurrent()
                return
            }
            urlIndex = 0
            workingUrl.remove(ch.key)
            health.report(ch.key, false)
            showStatus(getString(R.string.status_error))
            scheduleRetry()
        }
    }

    private fun createPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(DEFAULT_USER_AGENT)
        // 3 Sekunden vorladen, bevor das Bild startet (nach einem Stocker 5 Sekunden)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 50_000, 3_000, 5_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        // Mehr als Full HD überfordert schwache Boxen nur. Untertitel sind abgeschaltet.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1080)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.addListener(playerListener)
        playerView.player = p
        playerView.subtitleView?.visibility = View.GONE
        httpFactory = http
        player = p
    }

    private fun playCurrent() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        val p = player ?: return
        if (!isOnline()) {
            waitForInternet()
            return
        }
        val ch = playing
        if (ch == null || ch.urls.isEmpty()) {
            showStatus(getString(R.string.status_no_channels))
            handler.removeCallbacks(fetchUntilChannels)
            handler.postDelayed(fetchUntilChannels, 5_000)
            return
        }
        if (urlIndex !in ch.urls.indices) urlIndex = 0
        val stream = ch.urls[urlIndex]
        httpFactory?.setUserAgent(stream.userAgent ?: DEFAULT_USER_AGENT)
        httpFactory?.setDefaultRequestProperties(
            stream.referrer?.let { mapOf("Referer" to it) } ?: emptyMap()
        )
        val item = MediaItem.Builder().setUri(stream.url)
        val lower = stream.url.lowercase()
        when {
            lower.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        val delay = minOf(30_000L, 5_000L * (1L shl minOf(retryCount, 3)))
        retryCount++
        handler.postDelayed(retry, delay)
    }

    private fun playingIsFavorite(): Boolean {
        val p = playing ?: return false
        return favorites.getOrNull(favIndex)?.key == p.key
    }

    private fun isSkippable(ch: Channel): Boolean =
        ch.key in missingKeys && health.state(ch.key) != HealthStore.State.OK

    private fun zap(delta: Int) {
        if (favorites.isEmpty()) return
        // Lief gerade ein Sender aus "Alle Sender": erst zurück zum letzten eigenen Sender
        if (!playingIsFavorite()) {
            switchToFav(favIndex.coerceIn(0, favorites.size - 1), immediately = false)
            return
        }
        val size = favorites.size
        var idx = favIndex
        repeat(size) {
            idx = ((idx + delta) % size + size) % size
            if (!isSkippable(favorites[idx])) {
                switchToFav(idx, immediately = false)
                return
            }
        }
    }

    private fun switchToFav(index: Int, immediately: Boolean) {
        val ch = favorites.getOrNull(index) ?: return
        favIndex = index
        favStore.saveLastKey(ch.key)
        startChannel(ch, immediately)
    }

    private fun startChannel(ch: Channel, immediately: Boolean) {
        playing = ch
        urlIndex = workingUrl[ch.key] ?: 0
        retryCount = 0
        reportedOk = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        handler.removeCallbacks(showLoading)
        hideStatus()
        showInfo()
        player?.stop()
        // Beim schnellen Durchschalten wird erst der Sender geladen, bei dem man stehen bleibt.
        if (immediately) playCurrent() else handler.postDelayed(delayedStart, 600)
    }

    // =====================================================================
    // Senderlisten aus dem Internet
    // =====================================================================

    private fun refreshDefaults() {
        lastDefaultsRefresh = System.currentTimeMillis()
        runInBackground {
            val fresh = favStore.fetchDefaultRemote() ?: return@runInBackground
            handler.post { applyDefaults(fresh) }
        }
    }

    private fun refreshCatalog() {
        runInBackground {
            val fresh = catalogRepo.fetchRemote() ?: return@runInBackground
            handler.post { applyCatalog(fresh) }
        }
    }

    /** Neue Vorgabe aus sender.m3u – gilt nur, solange an der Box nichts bearbeitet wurde. */
    private fun applyDefaults(fresh: List<Channel>) {
        if (favStore.edited || editMode || fresh.isEmpty()) return
        val wasFavorite = playingIsFavorite()
        val previous = playing
        favorites = fresh.toMutableList()
        favIndex = previous?.let { old -> favorites.indexOfFirst { it.key == old.key } }
            ?.takeIf { it >= 0 } ?: favStore.findLastIndex(favorites)
        syncFavorites(updatePlaying = false)
        refreshFavList()
        if (wasFavorite || previous == null) {
            val now = favorites.getOrNull(favIndex) ?: return
            val restart = previous == null ||
                now.urls.firstOrNull()?.url != previous.urls.firstOrNull()?.url
            playing = now
            if (restart) {
                urlIndex = 0
                playCurrent()
            }
        }
    }

    private fun applyCatalog(fresh: Catalog) {
        catalog = fresh
        if (favorites.isEmpty() && !favStore.edited) {
            // Noch keine Vorgabe da (z. B. erster Start ohne Internet): Hauptsender als Startliste
            favorites = fresh.groups.firstOrNull()?.channels.orEmpty().toMutableList()
            favIndex = 0
        }
        syncFavorites()
        refreshFavList()
        if (playing == null && favorites.isNotEmpty()) switchToFav(favIndex, immediately = true)
        if (allVisible() && groupAdapter.itemCount == 0) populateAll()
    }

    /**
     * Gleicht "Meine Sender" mit der Gesamtliste ab: neue Adressen werden übernommen,
     * eigene Adressen aus sender.m3u bleiben vorne. Nichts wird automatisch gelöscht.
     */
    private fun syncFavorites(updatePlaying: Boolean = true) {
        val cat = catalog ?: return
        val missing = HashSet<String>()
        val wasFavorite = updatePlaying && playingIsFavorite()
        for (i in favorites.indices) {
            val fav = favorites[i]
            val match = cat.byKey[fav.key] ?: if (fav.isFromM3u) cat.match(fav) else null
            if (match == null) {
                if (!fav.isFromM3u) missing.add(fav.key)
                continue
            }
            val urls = if (fav.isFromM3u) (fav.urls + match.urls).distinctBy { it.url } else match.urls
            favorites[i] = fav.copy(urls = urls.take(5), logo = fav.logo.ifEmpty { match.logo })
        }
        missingKeys = missing
        if (wasFavorite) playing = favorites.getOrNull(favIndex)
        if (favStore.edited) favStore.save(favorites)
    }

    // =====================================================================
    // Anzeigen
    // =====================================================================

    private fun showInfo() {
        val ch = playing ?: return
        infoText.text = if (playingIsFavorite()) getString(R.string.channel_info, favIndex + 1, ch.name) else ch.name
        if (ch.logo.isNotEmpty()) {
            infoLogo.visibility = View.VISIBLE
            infoLogo.load(ch.logo)
        } else {
            infoLogo.visibility = View.GONE
        }
        infoBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    private fun listVisible() = listPanel.visibility == View.VISIBLE
    private fun allVisible() = allPanel.visibility == View.VISIBLE
    private fun dialogVisible() = dialogPanel.visibility == View.VISIBLE

    private fun restartTimer(action: Runnable, delayMs: Long) {
        handler.removeCallbacks(action)
        handler.postDelayed(action, delayMs)
    }

    private fun focusItem(list: RecyclerView, position: Int, attempt: Int = 0) {
        if (position < 0) return
        list.post {
            val view = list.findViewHolderForAdapterPosition(position)?.itemView
            if (view != null) {
                view.requestFocus()
            } else if (attempt < 10) {
                list.scrollToPosition(position)
                list.postDelayed({ focusItem(list, position, attempt + 1) }, 50)
            }
        }
    }

    private fun focusedPosition(list: RecyclerView): Int {
        val f = currentFocus ?: return -1
        return if (f.parent === list) list.getChildAdapterPosition(f) else -1
    }

    // ---------- Meine Sender ----------

    private fun showFavList() {
        if (System.currentTimeMillis() - lastDefaultsRefresh > 15 * 60_000L) refreshDefaults()
        infoBox.visibility = View.GONE
        allPanel.visibility = View.GONE
        updateListHeader()
        refreshFavList(focus = false)
        listPanel.visibility = View.VISIBLE
        val pos = if (playingIsFavorite()) favIndex else 0
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 120)
        focusItem(channelList, pos)
        restartTimer(hideList, if (editMode) EDIT_TIMEOUT else LIST_TIMEOUT)
    }

    private fun hideFavList() {
        handler.removeCallbacks(hideList)
        listPanel.visibility = View.GONE
        if (editMode) leaveEditMode(refocus = false)
        currentFocus?.clearFocus()
    }

    private fun refreshFavList(focus: Boolean = true, focusPos: Int? = null) {
        val pos = focusPos ?: focusedPosition(channelList)
        favAdapter.playingIndex = if (playingIsFavorite()) favIndex else -1
        favAdapter.editMode = editMode
        favAdapter.movingIndex = movingIndex
        favAdapter.missingKeys = missingKeys
        favAdapter.submit(favorites)
        if (focus && listVisible() && pos >= 0) {
            focusItem(channelList, pos.coerceAtMost(favAdapter.itemCount - 1))
        }
    }

    private fun updateListHeader() {
        listTitle.setText(if (editMode) R.string.edit_title else R.string.list_title)
        listHint.setText(
            when {
                movingIndex >= 0 -> R.string.edit_hint_moving
                editMode -> R.string.edit_hint
                else -> R.string.list_hint
            }
        )
    }

    override fun onFavClick(index: Int) {
        if (editMode) {
            toggleMove(index)
        } else {
            hideFavList()
            switchToFav(index, immediately = true)
        }
    }

    override fun onAction(action: Int) {
        when (action) {
            FavAdapter.ACTION_ALL, FavAdapter.ACTION_ADD -> showAll()
            FavAdapter.ACTION_RESET -> showDialog(
                getString(R.string.reset_question),
                getString(R.string.reset_text),
                getString(R.string.cancel) to {},
                getString(R.string.reset_yes) to { resetFavorites() }
            )
        }
    }

    // ---------- Bearbeiten ----------

    private fun enterEditMode() {
        val pos = focusedPosition(channelList)
        editMode = true
        movingIndex = -1
        updateListHeader()
        refreshFavList(focusPos = pos.coerceAtLeast(0))
        restartTimer(hideList, EDIT_TIMEOUT)
    }

    private fun leaveEditMode(refocus: Boolean = true) {
        if (movingIndex >= 0) markEdited()
        editMode = false
        movingIndex = -1
        updateListHeader()
        val pos = focusedPosition(channelList).coerceAtMost(favorites.size - 1)
        refreshFavList(focus = refocus, focusPos = pos)
        if (listVisible()) restartTimer(hideList, LIST_TIMEOUT)
    }

    private fun toggleMove(index: Int) {
        if (index !in favorites.indices) return
        if (movingIndex == index) {
            movingIndex = -1
            markEdited()
        } else {
            movingIndex = index
        }
        favAdapter.movingIndex = movingIndex
        favAdapter.notifyItemChanged(index)
        updateListHeader()
    }

    private fun moveFav(delta: Int) {
        val from = movingIndex
        val to = from + delta
        if (from !in favorites.indices || to !in favorites.indices) return
        val playingKey = if (playingIsFavorite()) playing?.key else null
        Collections.swap(favorites, from, to)
        if (playingKey != null) favIndex = favorites.indexOfFirst { it.key == playingKey }
        movingIndex = to
        favAdapter.movingIndex = to
        favAdapter.playingIndex = -1
        favAdapter.notifyItemMoved(from, to)
        favAdapter.notifyItemRangeChanged(minOf(from, to), 2)
        channelList.scrollToPosition(to)
        focusItem(channelList, to)
        markEdited()
    }

    private fun confirmRemove(index: Int) {
        val ch = favorites.getOrNull(index) ?: return
        showDialog(
            getString(R.string.remove_question, ch.name),
            getString(R.string.remove_text),
            getString(R.string.remove_no) to {},
            getString(R.string.remove_yes) to { removeFavorite(index) }
        )
    }

    private fun removeFavorite(index: Int) {
        if (index !in favorites.indices) return
        // Wird der laufende Sender entfernt, läuft er weiter, zählt aber nicht mehr zu "Meine Sender".
        favorites.removeAt(index)
        if (index < favIndex) favIndex--
        if (favIndex >= favorites.size) favIndex = (favorites.size - 1).coerceAtLeast(0)
        markEdited()
        refreshFavList(focusPos = index.coerceAtMost(favAdapter.itemCount - 1))
    }

    private fun resetFavorites() {
        favStore.reset()
        editMode = false
        movingIndex = -1
        val previousKey = playing?.key
        favorites = favStore.loadDefaultLocal().toMutableList()
        syncFavorites()
        favIndex = favorites.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
        updateListHeader()
        refreshFavList(focusPos = 0)
        refreshDefaults()
    }

    private fun markEdited() {
        favStore.edited = true
        favStore.save(favorites)
    }

    private fun addFavorite(ch: Channel) {
        if (favorites.any { it.key == ch.key }) return
        favorites.add(ch)
        markEdited()
        refreshFavList(focus = false)
        allAdapter.favoriteKeys = favorites.map { it.key }.toSet()
        allAdapter.refreshKey(ch.key)
        Toast.makeText(this, getString(R.string.added_toast, ch.name, favorites.size), Toast.LENGTH_LONG).show()
    }

    // ---------- Alle Sender ----------

    private fun showAll() {
        allOpenedFromEdit = editMode
        handler.removeCallbacks(hideList)
        listPanel.visibility = View.GONE
        allPanel.visibility = View.VISIBLE
        populateAll()
        restartTimer(closeAll, ALL_TIMEOUT)
    }

    private fun populateAll() {
        val cat = catalog
        if (cat == null) {
            groupAdapter.submit(emptyList())
            allAdapter.submit(emptyList(), emptySet())
            allEmpty.setText(R.string.all_loading)
            allEmpty.visibility = View.VISIBLE
            refreshCatalog()
            return
        }
        val groups = cat.groups
        groupAdapter.selected = selectedGroup.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        groupAdapter.submit(groups)
        selectGroup(groupAdapter.selected)
        (groupList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(selectedGroup, 120)
        focusItem(groupList, selectedGroup)
    }

    private fun hideAll(backToList: Boolean) {
        handler.removeCallbacks(closeAll)
        handler.removeCallbacks(applyGroup)
        checkGeneration++
        allPanel.visibility = View.GONE
        health.flush(io)
        if (backToList) showFavList() else currentFocus?.clearFocus()
    }

    private fun scheduleGroup(pos: Int) {
        if (pos == selectedGroup) return
        pendingGroup = pos
        restartTimer(applyGroup, 300)
    }

    private fun selectGroup(pos: Int) {
        handler.removeCallbacks(applyGroup)
        pendingGroup = -1
        val cat = catalog ?: return
        val group = cat.groups.getOrNull(pos) ?: return
        val old = selectedGroup
        selectedGroup = pos
        groupAdapter.selected = pos
        groupAdapter.notifyItemChanged(old)
        groupAdapter.notifyItemChanged(pos)
        val visible = group.channels.filter { health.state(it.key) != HealthStore.State.HIDDEN }
        allAdapter.submit(visible, favorites.map { it.key }.toSet())
        allList.scrollToPosition(0)
        if (visible.isEmpty()) {
            allEmpty.setText(R.string.all_empty)
            allEmpty.visibility = View.VISIBLE
        } else {
            allEmpty.visibility = View.GONE
        }
        startChecks(visible)
    }

    private fun focusFirstChannel() {
        if (allAdapter.itemCount > 0) focusItem(allList, 0)
    }

    /** Prüft im Hintergrund, welche Sender dieser Gruppe von hier aus erreichbar sind. */
    private fun startChecks(list: List<Channel>) {
        val generation = ++checkGeneration
        if (!isOnline()) return
        val todo = list.filter { !health.checkedToday(it.key) }.take(MAX_CHECKS)
        if (checkPool.isShutdown) return
        for (ch in todo) {
            runCatching {
                checkPool.execute {
                    if (generation != checkGeneration) return@execute
                    val ok = StreamChecker.check(ch)
                    // Ohne Internet ist ein Fehlschlag keine Aussage über den Sender
                    if (ok || isOnline()) health.report(ch.key, ok)
                    handler.post { if (generation == checkGeneration) allAdapter.refreshKey(ch.key) }
                }
            }
        }
    }

    private fun onAllChannelClick(index: Int) {
        val ch = allAdapter.channels.getOrNull(index) ?: return
        val cat = catalog
        val isFav = favorites.any { it.key == ch.key }
        val groupName = cat?.groupOf(ch).orEmpty()
        val line = if (health.state(ch.key) == HealthStore.State.FAIL)
            getString(R.string.group_unreachable, groupName.ifEmpty { ch.name }) else groupName
        val watch: Pair<String, () -> Unit> = getString(R.string.action_watch) to {
            closeAllPanels()
            startChannel(ch, immediately = true)
        }
        val add: Pair<String, () -> Unit> =
            if (isFav) getString(R.string.action_already) to {}
            else getString(R.string.action_add) to { addFavorite(ch) }
        val cancel: Pair<String, () -> Unit> = getString(R.string.cancel) to {}
        if (allOpenedFromEdit) showDialog(ch.name, line, add, watch, cancel)
        else showDialog(ch.name, line, watch, add, cancel)
    }

    // ---------- Rückfragen ----------

    private fun showDialog(title: String, text: String?, vararg buttons: Pair<String, () -> Unit>) {
        focusBeforeDialog = currentFocus
        dialogTitle.text = title
        if (text.isNullOrEmpty()) {
            dialogText.visibility = View.GONE
        } else {
            dialogText.text = text
            dialogText.visibility = View.VISIBLE
        }
        dialogButtons.forEachIndexed { i, button ->
            val b = buttons.getOrNull(i)
            if (b == null) {
                button.visibility = View.GONE
            } else {
                button.text = b.first
                button.visibility = View.VISIBLE
            }
        }
        dialogActions = buttons.map { it.second }
        dialogPanel.visibility = View.VISIBLE
        dialogButtons.first().requestFocus()
        restartTimer(hideDialogTimer, DIALOG_TIMEOUT)
    }

    private fun hideDialog() {
        handler.removeCallbacks(hideDialogTimer)
        if (!dialogVisible()) return
        dialogPanel.visibility = View.GONE
        val back = focusBeforeDialog
        focusBeforeDialog = null
        if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus()
        else currentFocus?.clearFocus()
    }

    private fun moveDialogFocus(delta: Int) {
        val visible = dialogButtons.filter { it.visibility == View.VISIBLE }
        val i = visible.indexOf(currentFocus)
        val next = (if (i < 0) 0 else i + delta).coerceIn(0, visible.size - 1)
        visible.getOrNull(next)?.requestFocus()
    }

    private fun showExitDialog() {
        closeAllPanels()
        val yes = if (BuildConfig.IS_LAUNCHER) {
            getString(R.string.exit_yes) to { startActivity(Intent(this, AppsActivity::class.java)) }
        } else {
            getString(R.string.exit_yes_lite) to { finish() }
        }
        showDialog(getString(R.string.exit_question), null, getString(R.string.exit_no) to {}, yes)
    }

    private fun closeAllPanels() {
        hideDialog()
        if (allVisible()) hideAll(backToList = false)
        hideFavList()
        handler.removeCallbacks(closeAll)
    }

    // =====================================================================
    // Fernbedienung
    // =====================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return backKey.handle(event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                return okKey.handle(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        val isUp = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP ||
            code == KeyEvent.KEYCODE_PAGE_UP
        val isDown = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            code == KeyEvent.KEYCODE_PAGE_DOWN
        val isSide = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

        // Rückfrage offen: nur zwischen ihren Knöpfen wechseln
        if (dialogVisible()) {
            restartTimer(hideDialogTimer, DIALOG_TIMEOUT)
            if (isUp || isDown) {
                if (down) moveDialogFocus(if (isUp) -1 else 1)
                return true
            }
            if (isSide) return true
            return super.dispatchKeyEvent(event)
        }

        // Alle Sender
        if (allVisible()) {
            restartTimer(closeAll, ALL_TIMEOUT)
            val focus = currentFocus
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && focus?.parent === groupList) {
                    if (pendingGroup >= 0) selectGroup(pendingGroup)
                    focusFirstChannel()
                }
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down && focus?.parent === allList) focusItem(groupList, selectedGroup)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Meine Sender
        if (listVisible()) {
            restartTimer(hideList, if (editMode) EDIT_TIMEOUT else LIST_TIMEOUT)
            if (editMode && movingIndex >= 0) {
                if (isUp || isDown) {
                    if (down) moveFav(if (isUp) -1 else 1)
                    return true
                }
                if (isSide) return true
            }
            if (editMode && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down) {
                    val pos = focusedPosition(channelList)
                    if (pos in favorites.indices) confirmRemove(pos)
                }
                return true
            }
            if (isSide) return true
            return super.dispatchKeyEvent(event)
        }

        // Fernsehbild
        when {
            isUp -> {
                if (down) zap(+1)
                return true
            }
            isDown -> {
                if (down) zap(-1)
                return true
            }
            isSide || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_GUIDE -> {
                if (event.action == KeyEvent.ACTION_UP) showFavList()
                return true
            }
            code == KeyEvent.KEYCODE_INFO -> {
                if (event.action == KeyEvent.ACTION_UP) showInfo()
                return true
            }
        }
        // Lautstärke usw. ganz normal an Android weitergeben
        return super.dispatchKeyEvent(event)
    }

    private fun onOkShort() {
        when {
            dialogVisible() || allVisible() || listVisible() -> currentFocus?.performClick()
            else -> showFavList()
        }
    }

    private fun onOkLong() {
        if (!listVisible() || dialogVisible() || allVisible()) return
        if (editMode) leaveEditMode() else enterEditMode()
    }

    private fun onBackShort() {
        when {
            dialogVisible() -> hideDialog()
            allVisible() -> hideAll(backToList = true)
            listVisible() && movingIndex >= 0 -> toggleMove(movingIndex)
            listVisible() && editMode -> leaveEditMode()
            listVisible() -> hideFavList()
            !BuildConfig.IS_LAUNCHER -> showExitDialog()
            else -> showInfo()
        }
    }

    private fun onBackLong() {
        showExitDialog()
    }
}
