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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jma.pulsehealthanalytics.databinding.FragmentHealthBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Dashboard fed entirely by our own BLE pipeline (WatchStreamViewModel).
 * No Health Connect / GloryFit.
 *
 * Layout reuse:
 *  - btnRead       -> "Reconnect watch" / "Bluetooth settings"
 *  - tvResult      -> plain-language status and hints
 *  - tvWatch       -> live readings, dimmed while the watch is not connected
 *  - tvSleepStatus -> when last night arrived and whether the server saved it
 */
class HealthFragment : Fragment(R.layout.fragment_health) {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!

    private val watchVm: WatchStreamViewModel by viewModels()

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
        binding.btnGoToLogger.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, WorkoutLogger())
                .addToBackStack(null)
                .commit()
        }
        binding.btnLogout.setOnClickListener {
            ApiClient.logout()
            (requireActivity() as MainActivity).showLogin()
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
                        binding.tvSleepTotal.text = t?.let { fmt(it.totalMin) } ?: empty
                        binding.tvSleepDeep.text = t?.let { fmt(it.deepMin) } ?: empty
                        binding.tvSleepLight.text = t?.let { fmt(it.lightMin) } ?: empty
                        binding.tvSleepAwake.text = t?.let { fmt(it.awakeMin) } ?: empty
                        binding.tvSleepStatus.text = sleepStatus(s)
                    }
                }
            }
        }

        startWatch()
    }

    private fun sleepStatus(s: SleepState): String {
        val at = s.receivedAtMillis ?: return "No sleep data received from the watch yet."
        val whenStr = DateUtils.formatDateTime(
            requireContext(), at,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        )
        return when (s.upload) {
            SleepUpload.PENDING -> "Received $whenStr · saving…"
            SleepUpload.UPLOADED -> "Received $whenStr · saved to your account"
            SleepUpload.FAILED ->
                "Received $whenStr · not saved (${s.detail ?: "error"}). " +
                        "Will retry the next time the watch reconnects."
        }
    }

    private fun render(m: WatchMetrics) {
        if (_binding == null) return
        val none = getString(R.string.no_data)
        val label = when (m.link) {
            WatchLink.IDLE -> "Not started"
            WatchLink.BLUETOOTH_OFF -> "Bluetooth off"
            WatchLink.CONNECTING -> "Connecting…"
            WatchLink.CONNECTED -> "Connected"
            WatchLink.RECONNECTING -> "Reconnecting…"
        }
        binding.tvWatch.text = getString(
            R.string.watch_live,
            m.heartRate?.toString() ?: none,
            m.spo2?.toString() ?: none,
            m.steps?.toString() ?: none,
            m.distanceMetres?.toString() ?: none,
            m.caloriesKcal?.toString() ?: none,
            label,
        )
        // Last known values stay visible but look stale while we're not connected.
        binding.tvWatch.alpha = if (m.connected) 1f else 0.5f

        binding.tvResult.text = hint(m)
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
                last?.let { "Connected. $it" }
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
        if (hasBlePermission()) {
            watchVm.start()
        } else {
            blePermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun fmt(minutes: Int) = "${minutes / 60}h ${minutes % 60}m"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}