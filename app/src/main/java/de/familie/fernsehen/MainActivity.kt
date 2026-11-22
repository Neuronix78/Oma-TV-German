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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Das Fernsehbild.
 *
 *  Start:  ggf. "Warte auf Internet …" -> sender.m3u von GitHub holen
 *          (sonst letzte geladene Kopie, sonst Notfall-Kopie aus der App) -> Bild startet.
 *
 *  Fernsehbild
 *    hoch / runter        -> nächster / vorheriger Sender aus "Meine Sender"
 *    OK, links, rechts    -> Liste "Meine Sender"
 *    Zurück 3 s halten    -> "OmaTV verlassen?" (Lite: schon kurzes Zurück)
 *
 *  Meine Sender (= sender.m3u aus dem GitHub-Repo, an der Box nicht änderbar)
 *    OK                   -> umschalten
 *    "Alle Sender …"      -> OK 3 Sekunden halten öffnet die Gesamtliste
 *
 *  Alle Sender
 *    links Gruppen, rechts Sender, OK -> ansehen
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

    // ---------- Start-Ablauf ----------
    /** true, sobald die Senderliste für diesen Start abgeglichen ist – erst dann läuft der Stream. */
    private var listReady = false
    private var listLoading = false
    private var waitingForNet = false
    private var networkCallbackRegistered = false
    private var lastDefaultsRefresh = 0L

    // ---------- Wiedergabe ----------
    private var player: ExoPlayer? = null
    private var httpFactory: DefaultHttpDataSource.Factory? = null
    private var retryCount = 0

    // ---------- Bedienung ----------
    private var selectedGroup = 0
    private var pendingGroup = -1
    @Volatile private var checkGeneration = 0
    private var dialogActions: List<() -> Unit> = emptyList()
    private var focusBeforeDialog: View? = null

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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (waitingForNet) {
                    handler.removeCallbacks(waitNet)
                    handler.postDelayed(waitNet, 1_500)
                }
            }
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

        // Vorläufig die zuletzt bekannte Liste; abgespielt wird erst nach dem Abgleich mit GitHub.
        favorites = favStore.loadLocal().toMutableList()
        favIndex = favStore.findLastIndex(favorites)

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

        // Gesamtliste im Hintergrund laden (nur für Ersatzadressen und "Alle Sender")
        runInBackground {
            val local = catalogRepo.loadLocal()
            if (local != null) handler.post { if (catalog == null) applyCatalog(local) }
        }
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        registerNetworkCallback()
        listReady = false
        loadListThenPlay()
        refreshCatalog()
        handler.removeCallbacks(periodicRefresh)
        handler.postDelayed(periodicRefresh, REFRESH_INTERVAL)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        handler.removeCallbacks(showLoading)
        handler.removeCallbacks(periodicRefresh)
        handler.removeCallbacks(waitNet)
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
        // Home-Taste gedrückt, während OmaTV schon läuft
        closeAllPanels()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun runInBackground(task: () -> Unit) {
        if (io.isShutdown) return
        runCatching { io.execute(task) }
    }

    // =====================================================================
    // Start-Ablauf: Internet -> Senderliste von GitHub -> Stream
    // =====================================================================

    private fun loadListThenPlay() {
        if (!isOnline()) {
            waitForInternet()
            return
        }
        showStatus(getString(R.string.status_loading_list))
        if (listLoading) return
        listLoading = true
        runInBackground {
            val fresh = favStore.fetchDefaultRemote()
            handler.post {
                listLoading = false
                // GitHub nicht erreichbar: letzte geladene Kopie bzw. Notfall-Kopie aus der App
                applyList(fresh ?: favStore.loadLocal(), atStart = true)
                listReady = true
                lastDefaultsRefresh = System.currentTimeMillis()
                val first = favorites.getOrNull(favIndex)
                if (first == null) {
                    showStatus(getString(R.string.status_no_channels))
                } else {
                    hideStatus()
                    startChannel(first, immediately = true)
                }
            }
        }
    }

    /** Übernimmt die Senderliste. GitHub hat immer das letzte Wort. */
    private fun applyList(list: List<Channel>, atStart: Boolean) {
        if (list.isEmpty()) return
        val wasFavorite = playingIsFavorite()
        val previous = playing
        favorites = list.toMutableList()
        favIndex = if (atStart) {
            favStore.findLastIndex(favorites)
        } else {
            previous?.let { old -> favorites.indexOfFirst { it.key == old.key } }
                ?.takeIf { it >= 0 } ?: favIndex.coerceIn(0, favorites.size - 1)
        }
        syncFavorites()
        refreshFavList()
        if (atStart || !listReady) return
        // Änderung im laufenden Betrieb: nur neu starten, wenn sich die Adresse geändert hat
        if (wasFavorite || previous == null) {
            val now = favorites.getOrNull(favIndex) ?: return
            val restart = previous == null ||
                now.key != previous.key ||
                now.urls.firstOrNull()?.url != previous.urls.firstOrNull()?.url
            if (restart) startChannel(now, immediately = true) else playing = now
        }
    }

    // ---------- Internetverbindung ----------

    /** true, wenn die Box mit einem Netzwerk verbunden ist (auch aus Hintergrund-Threads nutzbar). */
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

    /** Internet ist (wieder) da. */
    private fun onNetworkBack() {
        if (!waitingForNet) return
        waitingForNet = false
        handler.removeCallbacks(waitNet)
        hideStatus()
        refreshCatalog()
        if (!listReady) {
            loadListThenPlay()
        } else {
            retryCount = 0
            playCurrent()
        }
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
        // Puffer: 3 Sekunden vorladen, bevor das Bild startet (nach einem Stocker 5 Sekunden),
        // im laufenden Betrieb werden bis zu 50 Sekunden im Voraus geladen.
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
        if (!listReady) return          // der Start-Ablauf startet den Stream selbst
        if (!isOnline()) {
            waitForInternet()
            return
        }
        val ch = playing
        if (ch == null || ch.urls.isEmpty()) {
            showStatus(getString(R.string.status_no_channels))
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

    private fun zap(delta: Int) {
        if (favorites.isEmpty() || !listReady) return
        // Lief gerade ein Sender aus "Alle Sender": erst zurück zum letzten eigenen Sender
        if (!playingIsFavorite()) {
            switchToFav(favIndex.coerceIn(0, favorites.size - 1), immediately = false)
            return
        }
        val size = favorites.size
        switchToFav(((favIndex + delta) % size + size) % size, immediately = false)
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
    // Senderlisten aus dem Internet (im laufenden Betrieb)
    // =====================================================================

    private fun refreshDefaults() {
        lastDefaultsRefresh = System.currentTimeMillis()
        runInBackground {
            val fresh = favStore.fetchDefaultRemote() ?: return@runInBackground
            handler.post { if (listReady) applyList(fresh, atStart = false) }
        }
    }

    private fun refreshCatalog() {
        runInBackground {
            val fresh = catalogRepo.fetchRemote() ?: return@runInBackground
            handler.post { applyCatalog(fresh) }
        }
    }

    private fun applyCatalog(fresh: Catalog) {
        catalog = fresh
        syncFavorites()
        if (allVisible() && groupAdapter.itemCount == 0) populateAll()
    }

    /**
     * Ergänzt "Meine Sender" um Ersatzadressen aus der Gesamtliste.
     * Die Adressen aus sender.m3u bleiben immer vorne; Sender werden nie hinzugefügt oder entfernt.
     */
    private fun syncFavorites() {
        val cat = catalog ?: return
        val wasFavorite = playingIsFavorite()
        for (i in favorites.indices) {
            val fav = favorites[i]
            val match = cat.match(fav) ?: continue
            val urls = (fav.urls + match.urls).distinctBy { it.url }.take(5)
            favorites[i] = fav.copy(urls = urls, logo = fav.logo.ifEmpty { match.logo })
        }
        if (wasFavorite) playing = favorites.getOrNull(favIndex)
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
        if (!listReady) return
        if (System.currentTimeMillis() - lastDefaultsRefresh > 15 * 60_000L) refreshDefaults()
        infoBox.visibility = View.GONE
        allPanel.visibility = View.GONE
        refreshFavList(focus = false)
        listPanel.visibility = View.VISIBLE
        val pos = if (playingIsFavorite()) favIndex else 0
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 120)
        focusItem(channelList, pos)
        restartTimer(hideList, LIST_TIMEOUT)
    }

    private fun hideFavList() {
        handler.removeCallbacks(hideList)
        listPanel.visibility = View.GONE
        currentFocus?.clearFocus()
    }

    private fun refreshFavList(focus: Boolean = true) {
        val pos = focusedPosition(channelList)
        favAdapter.playingIndex = if (playingIsFavorite()) favIndex else -1
        favAdapter.submit(favorites)
        if (focus && listVisible() && pos >= 0) {
            focusItem(channelList, pos.coerceAtMost(favAdapter.itemCount - 1))
        }
    }

    override fun onFavClick(index: Int) {
        hideFavList()
        switchToFav(index, immediately = true)
    }

    override fun onAction(action: Int) {
        // "Alle Sender …" kurz gedrückt: nur Hinweis, geöffnet wird mit 3 Sekunden Halten
        Toast.makeText(this, R.string.all_hold_hint, Toast.LENGTH_SHORT).show()
    }

    // ---------- Alle Sender ----------

    private fun showAll() {
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
        val favKeys = favorites.mapNotNull { cat.match(it)?.key }.toSet()
        allAdapter.submit(visible, favKeys)
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
        closeAllPanels()
        startChannel(ch, immediately = true)
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
            restartTimer(hideList, LIST_TIMEOUT)
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

    /** OK 3 Sekunden gehalten: auf "Alle Sender …" öffnet das die Gesamtliste. */
    private fun onOkLong() {
        if (!listVisible() || dialogVisible() || allVisible()) return
        val pos = focusedPosition(channelList)
        if (pos >= favorites.size) showAll() else currentFocus?.performClick()
    }

    private fun onBackShort() {
        when {
            dialogVisible() -> hideDialog()
            allVisible() -> hideAll(backToList = true)
            listVisible() -> hideFavList()
            !BuildConfig.IS_LAUNCHER -> showExitDialog()
            else -> showInfo()
        }
    }

    private fun onBackLong() {
        showExitDialog()
    }
}
