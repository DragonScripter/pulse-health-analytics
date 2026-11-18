package com.jma.pulsehealthanalytics

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class WatchMetrics(
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val steps: Int? = null,
    val distanceMetres: Int? = null,
    val caloriesKcal: Int? = null,
    val link: WatchLink = WatchLink.IDLE,
    /** Wall-clock time of the last reading from the watch, or null if none yet. */
    val lastUpdateMillis: Long? = null,
    /** Setup problem to show instead of a generic status (e.g. no watch configured). */
    val error: String? = null,
) {
    val connected: Boolean get() = link == WatchLink.CONNECTED
}

enum class SleepUpload { PENDING, UPLOADED, FAILED }

/** Last night as received from the watch, plus whether the server has it. Survives restarts. */
data class SleepState(
    val totals: SleepTotals? = null,
    val receivedAtMillis: Long? = null,
    val upload: SleepUpload = SleepUpload.PENDING,
    val detail: String? = null,
)

class WatchStreamViewModel(app: Application) : AndroidViewModel(app) {

    private val sleepPrefs = app.getSharedPreferences("sleep_last_night", Context.MODE_PRIVATE)

    private val _metrics = MutableStateFlow(WatchMetrics())
    val metrics: StateFlow<WatchMetrics> = _metrics

    private val _sleep = MutableStateFlow(loadSleep())
    val sleep: StateFlow<SleepState> = _sleep

    private var started = false

    // Activity packets are re-sent every ~6 s with identical values; only post real changes
    // (plus a keep-alive so the server still sees the watch is alive).
    private var lastActivity: Triple<Int, Int, Int>? = null
    private var lastActivityPostAt = 0L

    // Runs on its own single-thread executor, so a blocking HTTP call is fine here.
    private val sleepSync = SleepNightSync(app) { segments -> postSleep(segments) }

    private val ble = WatchBleManager(
        context = app,
        macAddress = BuildConfig.WATCH_MAC,
        listener = object : WatchBleManager.Listener {
            override fun onHeartRate(bpm: Int) {
                _metrics.update { it.copy(heartRate = bpm, lastUpdateMillis = now()) }
                post(mapOf("heart_rate" to bpm))
            }

            override fun onSpo2(percent: Int) {
                _metrics.update { it.copy(spo2 = percent, lastUpdateMillis = now()) }
                post(mapOf("spo2" to percent))
            }

            override fun onActivity(steps: Int, distanceMetres: Int, caloriesKcal: Int) {
                _metrics.update {
                    it.copy(
                        steps = steps,
                        distanceMetres = distanceMetres,
                        caloriesKcal = caloriesKcal,
                        lastUpdateMillis = now(),
                    )
                }
                val cur = Triple(steps, distanceMetres, caloriesKcal)
                val due = now() - lastActivityPostAt > ACTIVITY_KEEPALIVE_MS
                if (cur != lastActivity || due) {
                    lastActivity = cur
                    lastActivityPostAt = now()
                    post(
                        mapOf(
                            "steps" to steps,
                            "distance_metres" to distanceMetres,
                            "calories_kcal" to caloriesKcal,
                        )
                    )
                }
            }

            override fun onLinkState(state: WatchLink) {
                _metrics.update { it.copy(link = state) }
            }

            override fun onSleepPacket(raw: ByteArray) {
                val totals = SleepPacketDecoder.decode(raw)?.let { SleepPacketDecoder.totals(it) }
                // Same night re-sent: keep the existing state (incl. "saved"). New night: start fresh.
                if (totals != null && totals != _sleep.value.totals) {
                    setSleep(SleepState(totals, now(), SleepUpload.PENDING))
                }
                // Keep the raw packet so "Reconnect watch" can retry the upload without the watch.
                if (totals != null) sleepPrefs.edit().putString("raw", raw.toHexString()).apply()
                sleepSync.onSleepPacket(raw)
            }
        },
    )

    fun start() {
        if (started) return
        if (BuildConfig.WATCH_MAC.isBlank()) {
            Log.w(TAG, "WATCH_MAC is not set in local.properties")
            _metrics.update { it.copy(error = "No watch is set up for this build (WATCH_MAC is empty).") }
            return
        }
        started = true
        ble.connect()
    }

    /** User tapped "Reconnect": retry any unsaved night, then restart the watch link. */
    fun reconnect() {
        retryPendingSleepUpload()
        if (!started) start() else ble.reconnectNow()
    }

    /**
     * Re-sends last night if the server doesn't have it yet. Only on the same calendar day it was
     * received: timestamps are anchored to today's date, so a retry tomorrow would mis-date the night.
     */
    private fun retryPendingSleepUpload() {
        val s = _sleep.value
        val at = s.receivedAtMillis ?: return
        if (s.upload == SleepUpload.UPLOADED) return
        val sameDay = java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
        if (!sameDay) return
        val hex = sleepPrefs.getString("raw", null) ?: return
        setSleep(s.copy(upload = SleepUpload.PENDING, detail = null))
        sleepSync.onSleepPacket(hex.hexToBytes())
    }

    private fun now() = System.currentTimeMillis()

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun post(fields: Map<String, Int>) {
        viewModelScope.launch {
            val code = ApiClient.postWatchMetrics(fields)
            Log.d(TAG, "watch-metrics $fields -> $code")
        }
    }

    /** Blocking. Called from SleepNightSync's executor thread, never the main thread. */
    private fun postSleep(segments: List<SleepSegment>): Boolean {
        return try {
            val arr = JSONArray()
            segments.forEach {
                arr.put(
                    JSONObject()
                        .put("start_time", java.time.Instant.ofEpochMilli(it.startTimeMillis).toString())
                        .put("stage", it.stage)
                        .put("duration_min", it.durationMin)
                )
            }
            val body = JSONObject().put("segments", arr).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.API_URL}/api/sleep") // must match the app.use(...) mount path for routes/sleep.js
                .post(body)
                .build()
            ApiClient.http.newCall(req).execute().use {
                Log.d(TAG, "sleep upload -> ${it.code}")
                if (it.isSuccessful) markUpload(SleepUpload.UPLOADED, null)
                else markUpload(SleepUpload.FAILED, "HTTP ${it.code}")
                it.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "sleep upload failed", e)
            // Show the real exception on the dashboard so no logcat is needed.
            val reason = "${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(90)
            markUpload(SleepUpload.FAILED, reason)
            false
        }
    }

    // ---- last-night persistence ---------------------------------------------

    private fun markUpload(upload: SleepUpload, detail: String?) {
        val cur = _sleep.value
        if (cur.totals == null) return
        setSleep(cur.copy(upload = upload, detail = detail))
    }

    private fun setSleep(s: SleepState) {
        _sleep.value = s
        val t = s.totals ?: return
        sleepPrefs.edit()
            .putInt("deep", t.deepMin)
            .putInt("light", t.lightMin)
            .putInt("awake", t.awakeMin)
            .putLong("received_at", s.receivedAtMillis ?: 0L)
            .putString("upload", s.upload.name)
            .putString("detail", s.detail)
            .apply()
    }

    private fun loadSleep(): SleepState {
        if (!sleepPrefs.contains("received_at")) return SleepState()
        return SleepState(
            totals = SleepTotals(
                deepMin = sleepPrefs.getInt("deep", 0),
                lightMin = sleepPrefs.getInt("light", 0),
                awakeMin = sleepPrefs.getInt("awake", 0),
            ),
            receivedAtMillis = sleepPrefs.getLong("received_at", 0L),
            upload = runCatching {
                SleepUpload.valueOf(sleepPrefs.getString("upload", null) ?: "PENDING")
            }.getOrDefault(SleepUpload.PENDING),
            detail = sleepPrefs.getString("detail", null),
        )
    }

    override fun onCleared() {
        ble.shutdown()
        sleepSync.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "WatchStream"
        const val ACTIVITY_KEEPALIVE_MS = 5 * 60_000L
    }
}