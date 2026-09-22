package com.davidlevi.radioabba

import android.Manifest
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var nowPlaying: TextView
    private lateinit var grid: GridLayout
    private lateinit var stopButton: Button

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var stations: List<Station> = StationRepository.defaults
    private var selectedStation: Station? = null
    private val stationButtons = mutableMapOf<String, Button>()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nowPlaying = findViewById(R.id.nowPlaying)
        grid = findViewById(R.id.grid)
        stopButton = findViewById(R.id.stopButton)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        buildButtons(stations)

        stopButton.setOnClickListener {
            controller?.apply {
                stop()
                clearMediaItems()
            }
            selectedStation = null
            highlight(null)
            nowPlaying.text = getString(R.string.choose_station)
            stopButton.isEnabled = false
        }

        // Bundled list first (instant, works offline), then quietly swap in
        // the remote list if one is reachable — see StationRepository.
        thread {
            val fetched = StationRepository.loadStations(this)
            runOnUiThread {
                if (fetched != stations) {
                    stations = fetched
                    buildButtons(stations)
                }
            }
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                controller = future.get()
                controller?.addListener(playerListener)
            }, MoreExecutors.directExecutor())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val c = controller ?: return
        val station = selectedStation
        nowPlaying.text = when {
            station == null -> getString(R.string.choose_station)
            c.isPlaying -> "🔊 מנגן: ${station.name}"
            c.playWhenReady && (c.playbackState == Player.STATE_BUFFERING || c.playbackState == Player.STATE_IDLE) ->
                getString(R.string.reconnecting)
            else -> getString(R.string.connecting)
        }
    }

    private fun buildButtons(list: List<Station>) {
        grid.removeAllViews()
        stationButtons.clear()
        grid.columnCount = 2
        list.forEach { station ->
            val button = Button(this).apply {
                text = station.name
                setTextColor(Color.WHITE)
                textSize = 18f
                setLineSpacing(0f, 1.1f)
                gravity = Gravity.CENTER
                setAllCaps(false)
                minimumHeight = dp(110)
                background = roundedDrawable(station.colorHex, selected = false)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { play(station, this) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(button, params)
            stationButtons[station.id] = button
        }
    }

    private fun play(station: Station, button: Button) {
        val c = controller ?: return
        selectedStation = station
        highlight(button)
        c.setMediaItem(station.toMediaItem())
        c.prepare()
        c.play()
        stopButton.isEnabled = true
        nowPlaying.text = "${getString(R.string.connecting)} ${station.name}"
    }

    private fun highlight(active: Button?) {
        stationButtons.forEach { (id, button) ->
            val station = stations.firstOrNull { it.id == id } ?: return@forEach
            button.background = roundedDrawable(station.colorHex, selected = button === active)
        }
    }

    private fun roundedDrawable(colorHex: String, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(Color.parseColor(colorHex))
            if (selected) setStroke(dp(6), Color.parseColor("#FFD166"))
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onStop() {
        super.onStop()
        // Keep playing in the background; just detach the UI listener.
        controller?.removeListener(playerListener)
    }

    override fun onStart() {
        super.onStart()
        controller?.addListener(playerListener)
        updateStatus()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
