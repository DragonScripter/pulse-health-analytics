package com.jma.pulsehealthanalytics

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jma.pulsehealthanalytics.databinding.FragmentWorkoutLoggerBinding
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

class WorkoutLogger : Fragment() {
    private lateinit var binding: FragmentWorkoutLoggerBinding
    private val http = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWorkoutLoggerBinding.inflate(inflater, container, false)
        // Inflate the layout for this fragment
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSubmit.setOnClickListener {
            submitWorkoutData()
        }
    }
    private fun submitWorkoutData() {
        val exerciseName = binding.etExerciseName.text.toString().trim()
        val setsText = binding.etSets.text.toString().trim()
        val repsText = binding.etReps.text.toString().trim()
        val weightText = binding.etWeight.text.toString().trim()

        if (exerciseName.isEmpty() || setsText.isEmpty() || repsText.isEmpty() || weightText.isEmpty()) {
            Toast.makeText(context, "Please fill out all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val sets = setsText.toIntOrNull() ?: 0
        val reps = repsText.toIntOrNull() ?: 0
        val weight = weightText.toDoubleOrNull() ?: 0.0

        binding.btnSubmit.isEnabled = false
        binding.tvAiResult.text = getString(R.string.syncing)

        viewLifecycleOwner.lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("exercise_name", exerciseName)
                put("sets", sets)
                put("reps", reps)
                put("weight", weight)
            }

            val aiRecommendation = sendWorkoutToServer(payload)

            binding.tvAiResult.text = aiRecommendation ?: "Failed to get AI recommendation."
            binding.btnSubmit.isEnabled = true

            if (aiRecommendation != null) {
                Toast.makeText(requireContext(), "Workout Saved!", Toast.LENGTH_SHORT).show()
                binding.etSets.text.clear()
                binding.etReps.text.clear()
                binding.etWeight.text.clear()

                kotlinx.coroutines.delay(2500.milliseconds)
                parentFragmentManager.popBackStack()
            }

        }
    }

    private suspend fun sendWorkoutToServer(payload: JSONObject): String? = suspendCancellableCoroutine { continuation ->
        try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.API_URL}/api/workouts")
                .addHeader("x-api-key", BuildConfig.API_KEY)
                .post(body)
                .build()

            // Use enqueue() instead of execute() to force it off the UI thread completely
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    android.util.Log.e("SendWorkout", "Network call failed", e)
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (resp.isSuccessful && resp.body != null) {
                            try {
                                val responseString = resp.body!!.string()
                                val jsonResponse = JSONObject(responseString)
                                val aiAnalysis = jsonResponse.getJSONObject("ai_analysis")
                                val recommendation = aiAnalysis.getString("recommendation")

                                if (continuation.isActive) continuation.resume(recommendation)
                            } catch (e: Exception) {
                                android.util.Log.e("SendWorkout", "JSON parsing failed", e)
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } else {
                            android.util.Log.e("SendWorkout", "Server error code: ${resp.code}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("SendWorkout", "Setup failed", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

}