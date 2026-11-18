package com.jma.pulsehealthanalytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant

data class LastWorkout(
    val name: String,
    val sets: Int,
    val reps: Int,
    val weight: Double,
    val atMillis: Long,
)

data class WorkoutSummary(
    val weekEntries: Int,
    val weekVolume: Double,
    val last: LastWorkout?,
)

object WorkoutSummaryApi {
    /** Returns null on any failure (network, HTTP error, bad JSON). Never throws. */
    suspend fun fetch(): WorkoutSummary? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.API_URL}/api/workouts/summary")
                .get()
                .build()
            ApiClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val j = JSONObject(text)
                val l = j.optJSONObject("last")
                WorkoutSummary(
                    weekEntries = j.getInt("week_entries"),
                    weekVolume = j.getDouble("week_volume"),
                    last = l?.let {
                        LastWorkout(
                            name = it.getString("exercise_name"),
                            sets = it.getInt("sets"),
                            reps = it.getInt("reps"),
                            weight = it.getDouble("weight"),
                            atMillis = Instant.parse(it.getString("created_at")).toEpochMilli(),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WorkoutSummary", "fetch failed", e)
            null
        }
    }
}