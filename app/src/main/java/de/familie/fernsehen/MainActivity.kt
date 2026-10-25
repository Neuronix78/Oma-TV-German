package de.familie.fernsehen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.concurrent.Executors

/**
 * Das Fernsehbild. Bedienung:
 *  hoch / runter      -> Sender wechseln
 *  OK, links, rechts  -> Senderliste
 *  Zurück kurz        -> Liste bzw. Abfrage schließen
 *  Zurück 3 s halten  -> "Fernsehen verlassen?"
 *  (Lite-Version: schon kurzes Zurück fragt "Fernsehen beenden?")
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var infoBox: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoText: TextView
    private lateinit var listPanel: View
    private lateinit var channelList: RecyclerView
    private lateinit var exitPanel: View
    private lateinit var exitNo: View
    private lateinit var exitYes: View

    private val handler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var repository: ChannelRepository
    private lateinit var adapter: ChannelAdapter

    private var channels: List<Channel> = emptyList()
    private var currentIndex = 0
    private var player: ExoPlayer? = null
    private var retryCount = 0

    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val hideList = Runnable { hideChannelList() }
    private val hideExit = Runnable { hideExitDialog() }
    private val showLoading = Runnable { showStatus(getString(R.string.status_loading)) }
    private val retry = Runnable { playCurrent() }
    private val delayedStart = Runnable { playCurrent() }

    // ---------- Lebenszyklus ----------

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
        exitPanel = findViewById(R.id.exitPanel)
        exitNo = findViewById(R.id.exitNo)
        exitYes = findViewById(R.id.exitYes)

        repository = ChannelRepository(this)
        channels = repository.loadLocal()
        currentIndex = repository.findLastIndex(channels)

        adapter = ChannelAdapter { index ->
            hideChannelList()
            switchTo(index, immediately = true)
        }
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.itemAnimator = null
        channelList.adapter = adapter
        adapter.submit(channels)

        exitNo.setOnClickListener { hideExitDialog() }
        if (BuildConfig.IS_LAUNCHER) {
            exitYes.setOnClickListener {
                hideExitDialog()
                startActivity(Intent(this, AppsActivity::class.java))
            }
        } else {
            // Lite-Version: die App wird einfach beendet
            (exitYes as TextView).setText(R.string.exit_yes_lite)
            exitYes.setOnClickListener {
                hideExitDialog()
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        createPlayer()
        playCurrent()
        showInfo()
        refreshChannelList()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        handler.removeCallbacks(showLoading)
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        background.shutdownNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Taste gedrückt, während das Fernsehen schon läuft
        hideChannelList()
        hideExitDialog()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- Wiedergabe ----------

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
        val p = ExoPlayer.Builder(this)
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
        player = p
    }

    private fun playCurrent() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        val p = player ?: return
        val channel = channels.getOrNull(currentIndex)
        if (channel == null) {
            showStatus(getString(R.string.status_no_channels))
            return
        }
        val item = MediaItem.Builder().setUri(channel.url)
        if (channel.url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
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

    private fun switchTo(index: Int, immediately: Boolean) {
        if (channels.isEmpty()) return
        val size = channels.size
        currentIndex = ((index % size) + size) % size
        repository.saveLast(channels[currentIndex])
        retryCount = 0
        handler.removeCallbacks(retry)
        handler.removeCallbacks(delayedStart)
        handler.removeCallbacks(showLoading)
        hideStatus()
        showInfo()
        player?.stop()
        // Beim schnellen Durchschalten wird erst der Sender geladen, bei dem man stehen bleibt.
        if (immediately) playCurrent() else handler.postDelayed(delayedStart, 600)
    }

    // ---------- Senderliste aus dem Internet ----------

    private fun refreshChannelList() {
        if (background.isShutdown) return
        background.execute {
            val fresh = repository.fetchRemote() ?: return@execute
            handler.post { applyChannels(fresh) }
        }
    }

    private fun applyChannels(fresh: List<Channel>) {
        if (fresh.isEmpty()) return
        val previous = channels.getOrNull(currentIndex)
        channels = fresh
        adapter.submit(fresh)
        val sameName = previous?.let { old -> fresh.indexOfFirst { it.name == old.name } } ?: -1
        currentIndex = if (sameName >= 0) sameName else repository.findLastIndex(fresh)
        if (previous == null || fresh[currentIndex].url != previous.url) playCurrent()
    }

    // ---------- Anzeigen ----------

    private fun showInfo() {
        val channel = channels.getOrNull(currentIndex) ?: return
        infoText.text = getString(R.string.channel_info, currentIndex + 1, channel.name)
        if (channel.logo.isNotEmpty()) {
            infoLogo.visibility = View.VISIBLE
            infoLogo.load(channel.logo)
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

    private fun showChannelList() {
        if (channels.isEmpty()) return
        infoBox.visibility = View.GONE
        adapter.currentIndex = currentIndex
        adapter.notifyDataSetChanged()
        listPanel.visibility = View.VISIBLE
        (channelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(currentIndex, 120)
        focusListItem(currentIndex, 0)
        restartTimer(hideList, 10_000)
    }

    private fun focusListItem(position: Int, attempt: Int) {
        channelList.post {
            val view = channelList.findViewHolderForAdapterPosition(position)?.itemView
            if (view != null) {
                view.requestFocus()
            } else if (attempt < 10) {
                channelList.postDelayed({ focusListItem(position, attempt + 1) }, 50)
            }
        }
    }

    private fun hideChannelList() {
        handler.removeCallbacks(hideList)
        listPanel.visibility = View.GONE
        currentFocus?.clearFocus()
    }

    private fun showExitDialog() {
        hideChannelList()
        infoBox.visibility = View.GONE
        exitPanel.visibility = View.VISIBLE
        exitNo.requestFocus()
        restartTimer(hideExit, 15_000)
    }

    private fun hideExitDialog() {
        handler.removeCallbacks(hideExit)
        exitPanel.visibility = View.GONE
        currentFocus?.clearFocus()
    }

    private fun restartTimer(action: Runnable, delayMs: Long) {
        handler.removeCallbacks(action)
        handler.postDelayed(action, delayMs)
    }

    // ---------- Fernbedienung ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            return handleBackKey(event)
        }
        if (exitPanel.visibility == View.VISIBLE) {
            restartTimer(hideExit, 15_000)
            return super.dispatchKeyEvent(event)
        }
        if (listPanel.visibility == View.VISIBLE) {
            restartTimer(hideList, 10_000)
            return super.dispatchKeyEvent(event)
        }

        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) switchTo(currentIndex + 1, immediately = false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) switchTo(currentIndex - 1, immediately = false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_GUIDE -> {
                if (up) showChannelList()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (up) showInfo()
                return true
            }
        }
        // Lautstärke usw. ganz normal an Android weitergeben
        return super.dispatchKeyEvent(event)
    }

    // Zurück-Taste: kurz = schließen, 3 Sekunden halten = Abfrage "verlassen?".
    // Billige Infrarot-Fernbedienungen senden beim Halten manchmal kurze
    // Loslass-Signale; deshalb gilt die Taste erst nach 400 ms als losgelassen.
    private var backTracking = false
    private var backLongFired = false

    private val backLongPress = Runnable {
        backLongFired = true
        showExitDialog()
    }

    private val backReleased = Runnable {
        backTracking = false
        handler.removeCallbacks(backLongPress)
        if (!backLongFired) onShortBack()
        backLongFired = false
    }

    private fun handleBackKey(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                handler.removeCallbacks(backReleased)
                if (!backTracking) {
                    backTracking = true
                    backLongFired = false
                    handler.postDelayed(backLongPress, 3000)
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(backReleased)
                handler.postDelayed(backReleased, 400)
            }
        }
        return true
    }

    private fun onShortBack() {
        when {
            exitPanel.visibility == View.VISIBLE -> hideExitDialog()
            listPanel.visibility == View.VISIBLE -> hideChannelList()
            // Lite-Version: kurzes Zurück fragt direkt, ob man beenden möchte
            !BuildConfig.IS_LAUNCHER -> showExitDialog()
            else -> showInfo()
        }
    }
}
