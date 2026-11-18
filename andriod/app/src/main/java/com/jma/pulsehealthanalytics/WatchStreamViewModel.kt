package com.jma.pulsehealthanalytics

import android.app.Application
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
    val connected: Boolean = false,
)

class WatchStreamViewModel(app: Application) : AndroidViewModel(app) {

    private val _metrics = MutableStateFlow(WatchMetrics())
    val metrics: StateFlow<WatchMetrics> = _metrics

    private val _sleep = MutableStateFlow<SleepTotals?>(null)
    val sleep: StateFlow<SleepTotals?> = _sleep

    private var started = false

    // Runs on its own single-thread executor, so a blocking HTTP call is fine here.
    private val sleepSync = SleepNightSync(app) { segments -> postSleep(segments) }

    private val ble = WatchBleManager(
        context = app,
        macAddress = BuildConfig.WATCH_MAC,
        listener = object : WatchBleManager.Listener {
            override fun onHeartRate(bpm: Int) {
                _metrics.update { it.copy(heartRate = bpm) }
                post(mapOf("heart_rate" to bpm))
            }

            override fun onSpo2(percent: Int) {
                _metrics.update { it.copy(spo2 = percent) }
                post(mapOf("spo2" to percent))
            }

            override fun onActivity(steps: Int, distanceMetres: Int, caloriesKcal: Int) {
                _metrics.update {
                    it.copy(steps = steps, distanceMetres = distanceMetres, caloriesKcal = caloriesKcal)
                }
                post(
                    mapOf(
                        "steps" to steps,
                        "distance_metres" to distanceMetres,
                        "calories_kcal" to caloriesKcal,
                    )
                )
            }

            override fun onConnectionChanged(connected: Boolean) {
                _metrics.update { it.copy(connected = connected) }
            }

            override fun onSleepPacket(raw: ByteArray) {
                // Show it immediately, then hand off to the deduping uploader.
                SleepPacketDecoder.decode(raw)?.let {
                    _sleep.value = SleepPacketDecoder.totals(it)
                }
                sleepSync.onSleepPacket(raw)
            }
        },
    )

    fun start() {
        if (started) return
        if (BuildConfig.WATCH_MAC.isBlank()) {
            Log.w(TAG, "WATCH_MAC is not set in local.properties")
            return
        }
        started = true
        ble.connect()
    }

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
                it.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "sleep upload failed", e)
            false
        }
    }

    override fun onCleared() {
        ble.shutdown()
        sleepSync.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "WatchStream"
    }
}