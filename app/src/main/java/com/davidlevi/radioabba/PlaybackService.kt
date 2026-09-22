package com.davidlevi.radioabba

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.min

/**
 * Holds the ExoPlayer + MediaSession and is the only place that knows how to
 * recover a dropped stream: on error, or when the network comes back after a
 * loss, it re-prepares the same station and keeps playing without anyone
 * having to touch the phone. See loadStations() in Station.kt for the
 * per-station backup URLs it cycles through after repeated failures.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var connectivityManager: ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    private var lastMediaId: String? = null
    private var urlIndex = 0
    private var errorsOnCurrentUrl = 0
    private var attempt = 0
    private var waitingForNetwork = false

    // Seconds to wait before each successive retry; stays at the last value
    // once exhausted so we keep trying forever, just not too aggressively.
    private val backoffSeconds = intArrayOf(2, 4, 8, 15, 30)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (waitingForNetwork) {
                reconnectRunnable?.let { handler.removeCallbacks(it) }
                attempt = 0
                handler.post { reconnectNow() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setUserAgent("RadioAbba/1.0 (Android)")

        val loadControl = DefaultLoadControl.Builder()
            // Generous buffers for a live audio stream: absorb short network
            // hiccups before the player has to stall or rebuffer at all.
            .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
            // Keeps a partial CPU + Wi-Fi wake lock for as long as we're
            // playing, so Doze / app-standby doesn't strangle the stream
            // once the screen turns off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.setHandleAudioBecomingNoisy(true)
        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, player).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .build()
        )

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != lastMediaId) {
                lastMediaId = id
                urlIndex = 0
                errorsOnCurrentUrl = 0
                attempt = 0
                waitingForNetwork = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                errorsOnCurrentUrl = 0
                attempt = 0
                waitingForNetwork = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val item = player.currentMediaItem ?: return
        val urls = item.requestMetadata.extras?.getStringArrayList("urls")
            ?: item.localConfiguration?.uri?.toString()?.let { arrayListOf(it) }
            ?: return

        errorsOnCurrentUrl++
        // After 3 failures in a row on this address, try the next mirror
        // for the same station (if one is configured) before continuing.
        if (errorsOnCurrentUrl >= 3 && urls.size > 1) {
            urlIndex = (urlIndex + 1) % urls.size
            errorsOnCurrentUrl = 0
        }

        waitingForNetwork = true
        val delayMs = backoffSeconds[min(attempt, backoffSeconds.lastIndex)] * 1000L
        attempt++

        val runnable = Runnable { reconnectNow() }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun reconnectNow() {
        val item = player.currentMediaItem ?: return
        val urls = item.requestMetadata.extras?.getStringArrayList("urls")
            ?: item.localConfiguration?.uri?.toString()?.let { arrayListOf(it) }
            ?: return
        val nextUrl = urls[min(urlIndex, urls.size - 1)]
        val newItem = item.buildUpon().setUri(nextUrl).setMimeType(guessMimeType(nextUrl)).build()
        player.setMediaItem(newItem, /* resetPosition= */ true)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        player.removeListener(playerListener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
