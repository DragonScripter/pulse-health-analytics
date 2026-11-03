package com.jma.pulsehealthanalytics

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var started = false
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

    override fun onCleared() {
        ble.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "WatchStream"
    }
}