package com.jma.pulsehealthanalytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate

data class WeightEntry(val date: String, val lbs: Double, val avg7: Double)

data class WeightTrend(
    val entries: List<WeightEntry>,
    val currentAvg: Double?,
    val velocityPerWeek: Double?,
)

fun velocityText(v: Double?): String = when {
    v == null -> "Weekly trend appears after about 2 weeks of entries."
    v > 0.2 -> "▲ ${"%.1f".format(v)} lbs/week"
    v < -0.2 -> "▼ ${"%.1f".format(-v)} lbs/week"
    else -> "Steady (${"%+.1f".format(v)} lbs/week)"
}

object WeightApi {
    /** Returns null on success, otherwise the error text to show. Never throws. */
    suspend fun save(lbs: Double): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("weight_lbs", lbs)
                .put("measured_on", LocalDate.now().toString())
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.API_URL}/api/weight")
                .post(body)
                .build()
            ApiClient.http.newCall(req).execute().use {
                if (it.isSuccessful) null else "HTTP ${it.code}"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(90)
        }
    }

    /** Returns null on any failure. Never throws. */
    suspend fun trend(): WeightTrend? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.API_URL}/api/weight/trend?days=30")
                .get()
                .build()
            ApiClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                val arr = j.getJSONArray("entries")
                WeightTrend(
                    entries = List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        WeightEntry(o.getString("date"), o.getDouble("lbs"), o.getDouble("avg7"))
                    },
                    currentAvg = if (j.isNull("current_avg")) null else j.getDouble("current_avg"),
                    velocityPerWeek =
                        if (j.isNull("velocity_lbs_per_week")) null
                        else j.getDouble("velocity_lbs_per_week"),
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WeightApi", "trend failed", e)
            null
        }
    }
}