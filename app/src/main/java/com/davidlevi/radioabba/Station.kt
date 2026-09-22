package com.davidlevi.radioabba

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One radio station. [urls] can hold more than one stream address for the same
 * station (mirrors); the player tries them in order and only moves to the next
 * one after several failures on the current one.
 */
data class Station(
    val id: String,
    val name: String,
    val subtitle: String,
    val urls: List<String>,
    val colorHex: String
)

/**
 * Packs the full mirror list into the MediaItem's request-metadata extras so
 * PlaybackService can cycle through backups on repeated failures without
 * needing to know about Station/StationRepository at all.
 */
fun Station.toMediaItem(): MediaItem {
    val extras = Bundle().apply { putStringArrayList("urls", ArrayList(urls)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(urls.first())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist("הרדיו של אבא")
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setExtras(extras).build()
        )
        .build()
}

object StationRepository {

    private const val TAG = "StationRepository"

    // Once the project is pushed to GitHub, this points at the same repo's
    // stations.json on the default branch. If the app can reach it, it uses
    // whatever is published there — so a broken stream URL can be fixed for
    // Dad's phone without him ever reinstalling anything. If it can't reach
    // it (no internet yet, first run, DNS hiccup, or the URL below hasn't
    // been filled in), it silently falls back to the list bundled below.
    private const val REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/REPLACE_WITH_GITHUB_USERNAME/RadioAbba/main/stations.json"

    private const val TIMEOUT_MS = 4000

    val defaults: List<Station> = listOf(
        Station(
            "kan_bet", "כאן ב׳", "חדשות ואקטואליה, בלי פרסומות",
            listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_BET.mp3"),
            "#2A6F97"
        ),
        Station(
            "kan_tarbut", "כאן תרבות", "תרבות ומוזיקה, בלי פרסומות",
            listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_TARBUT.mp3"),
            "#6A4C93"
        ),
        Station(
            "kan_moreshet", "כאן מורשת", "מוזיקה עברית ישנה, בלי פרסומות",
            listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_MORESHET.mp3"),
            "#8A5A44"
        ),
        Station(
            "kan_88", "כאן 88", "מוזיקה, בלי פרסומות",
            listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_88.mp3"),
            "#1D7874"
        ),
        Station(
            "kan_reka", "כאן רקע", "מוזיקה רגועה, בלי פרסומות",
            listOf("https://playerservices.streamtheworld.com/api/livestream-redirect/KAN_REKA.mp3"),
            "#4A6FA5"
        ),
        Station(
            "glglz", "גלגלצ", "מוזיקה (יש פרסומות בשידור)",
            listOf("https://glzwizzlv.bynetcdn.com/glglz_mp3"),
            "#C1440E"
        ),
        Station(
            "glz", "גלי צה״ל", "חדשות (יש פרסומות בשידור)",
            listOf("https://glzwizzlv.bynetcdn.com/glz_mp3"),
            "#3D5A2A"
        ),
        Station(
            "103fm", "103FM", "אקטואליה (יש פרסומות בשידור)",
            listOf("https://cdn.cybercdn.live/103FM/Live/icecast.audio"),
            "#7B2D26"
        ),
        Station(
            "radius100", "רדיוס 100", "מוזיקה (יש פרסומות בשידור)",
            listOf("https://cdn.cybercdn.live/Radios_100FM/Audio/icecast.audio"),
            "#A0522D"
        )
    )

    /**
     * Tries the remote list first (short timeout), falls back to [defaults].
     * Safe to call from a background thread only.
     */
    fun loadStations(context: Context): List<Station> {
        return try {
            fetchRemote() ?: defaults
        } catch (t: Throwable) {
            Log.w(TAG, "remote station list unavailable, using bundled defaults", t)
            defaults
        }
    }

    private fun fetchRemote(): List<Station>? {
        if (REMOTE_CONFIG_URL.contains("REPLACE_WITH_GITHUB_USERNAME")) return null
        val connection = URL(REMOTE_CONFIG_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): List<Station>? {
        val array = JSONArray(json)
        if (array.length() == 0) return null
        val result = mutableListOf<Station>()
        for (i in 0 until array.length()) {
            val o: JSONObject = array.getJSONObject(i)
            val urls = mutableListOf<String>()
            val urlArray = o.getJSONArray("urls")
            for (j in 0 until urlArray.length()) urls.add(urlArray.getString(j))
            result.add(
                Station(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    subtitle = o.optString("subtitle", ""),
                    urls = urls,
                    colorHex = o.optString("color", "#2A6F97")
                )
            )
        }
        return result
    }
}
