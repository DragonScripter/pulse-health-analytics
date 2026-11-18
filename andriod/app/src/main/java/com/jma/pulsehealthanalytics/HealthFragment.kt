package com.jma.pulsehealthanalytics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jma.pulsehealthanalytics.databinding.FragmentHealthBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Glanceable dashboard. Each card opens its own tab. Data comes from WatchStreamViewModel. */
class HealthFragment : Fragment(R.layout.fragment_health) {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!

    // Activity-scoped so the BLE connection survives tab switches.
    private val watchVm: WatchStreamViewModel by activityViewModels()

    private var permissionDenied = false

    private val blePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) watchVm.start()
        render(watchVm.metrics.value)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHealthBinding.bind(view)

        binding.btnRead.setOnClickListener {
            if (watchVm.metrics.value.link == WatchLink.BLUETOOTH_OFF) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } else {
                permissionDenied = false
                if (hasBlePermission()) watchVm.reconnect() else blePermission.launch(
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }
        }
        binding.btnLogout.setOnClickListener {
            ApiClient.logout()
            (requireActivity() as MainActivity).showLogin()
        }
        binding.cardSleep.setOnClickListener {
            (requireActivity() as MainActivity).openSleepTab()
        }
        binding.cardWorkouts.setOnClickListener {
            (requireActivity() as MainActivity).openWorkoutsTab()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Re-render on every change AND every 15 s so "updated 2 min ago" stays true.
                launch {
                    val ticker = flow { while (true) { emit(Unit); delay(15_000) } }
                    combine(watchVm.metrics, ticker) { m, _ -> m }.collect { render(it) }
                }

                launch {
                    watchVm.sleep.collect { s ->
                        val empty = getString(R.string.sleep_empty)
                        val t = s.totals
                        binding.tvSleepTotal.text = t?.let { fmtDuration(it.asleepMin) } ?: empty
                        binding.tvSleepDeep.text = t?.let { fmtDuration(it.deepMin) } ?: empty
                        binding.tvSleepLight.text = t?.let { fmtDuration(it.lightMin) } ?: empty
                        binding.tvSleepAwake.text = t?.let { fmtDuration(it.awakeMin) } ?: empty
                        binding.tvSleepStatus.text = sleepStatusText(requireContext(), s)
                    }
                }

                // Refreshes each time the dashboard becomes visible (e.g. after logging a workout).
                launch { renderWorkouts(WorkoutSummaryApi.fetch()) }
            }
        }

        startWatch()
    }

    private fun renderWorkouts(s: WorkoutSummary?) {
        if (_binding == null) return
        if (s == null) {
            binding.tvWorkoutWeek.text = "Couldn't load workouts"
            binding.tvWorkoutLast.text = "Check your connection and reopen this tab."
            return
        }
        if (s.last == null) {
            binding.tvWorkoutWeek.text = "No workouts yet"
            binding.tvWorkoutLast.text = "Tap to log your first one."
            return
        }
        val entries = if (s.weekEntries == 1) "1 entry" else "${s.weekEntries} entries"
        binding.tvWorkoutWeek.text = "$entries · ${"%,.0f".format(s.weekVolume)} lbs"
        val l = s.last
        val ago = DateUtils.getRelativeTimeSpanString(
            l.atMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        binding.tvWorkoutLast.text =
            "Last: ${l.name} · ${l.sets}×${l.reps} @ ${num(l.weight)} · $ago"
    }

    private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

    private fun render(m: WatchMetrics) {
        if (_binding == null) return
        val none = getString(R.string.no_data)

        binding.tvSteps.text = m.steps?.let { "%,d".format(it) } ?: none
        binding.tvDistance.text = m.distanceMetres?.let { "%.1f km".format(it / 1000.0) } ?: none
        binding.tvCalories.text = m.caloriesKcal?.toString() ?: none
        binding.tvHeartRate.text = m.heartRate?.toString() ?: none
        binding.tvSpo2.text = m.spo2?.let { "$it%" } ?: none

        // Last known values stay visible but look stale while we're not connected.
        val alpha = if (m.connected) 1f else 0.5f
        listOf(
            binding.tvSteps, binding.tvDistance, binding.tvCalories,
            binding.tvHeartRate, binding.tvSpo2,
        ).forEach { it.alpha = alpha }

        binding.tvResult.text = hint(m)
        // The reconnect button only appears when it's useful.
        binding.btnRead.visibility = if (m.connected) View.GONE else View.VISIBLE
        binding.btnRead.text =
            if (m.link == WatchLink.BLUETOOTH_OFF) "Bluetooth settings" else "Reconnect watch"
    }

    private fun hint(m: WatchMetrics): String {
        m.error?.let { return it }
        if (permissionDenied) {
            return "Bluetooth permission is needed to talk to your watch. " +
                    "Tap Reconnect watch to allow it."
        }
        val last = m.lastUpdateMillis?.let { "Last reading ${ago(it)}." }
        return when (m.link) {
            WatchLink.IDLE -> "Starting up…"
            WatchLink.BLUETOOTH_OFF ->
                "Bluetooth is off. Turn it on and the app will connect on its own."
            WatchLink.CONNECTING -> "Looking for your watch…"
            WatchLink.RECONNECTING ->
                "Lost contact with your watch, retrying every few seconds. " +
                        "Keep it nearby, and make sure the GloryFit app isn't holding the connection." +
                        (last?.let { " $it" } ?: "")
            WatchLink.CONNECTED ->
                last?.let { "Connected · $it" }
                    ?: "Connected. Waiting for the first reading from your watch…"
        }
    }

    private fun ago(millis: Long): String {
        val s = (System.currentTimeMillis() - millis) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun hasBlePermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

    private fun startWatch() {
        // BLUETOOTH_CONNECT is a runtime permission only on API 31+.
        if (hasBlePermission()) watchVm.start() else blePermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}