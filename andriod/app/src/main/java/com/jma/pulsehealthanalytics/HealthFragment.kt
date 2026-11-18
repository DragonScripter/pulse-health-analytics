package com.jma.pulsehealthanalytics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jma.pulsehealthanalytics.databinding.FragmentHealthBinding
import kotlinx.coroutines.launch

/**
 * Dashboard fed entirely by our own BLE pipeline (WatchStreamViewModel).
 * No Health Connect / GloryFit.
 */
class HealthFragment : Fragment(R.layout.fragment_health) {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!

    private val watchVm: WatchStreamViewModel by viewModels()

    private val blePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) watchVm.start()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHealthBinding.bind(view)

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

                launch {
                    watchVm.metrics.collect { m ->
                        val none = getString(R.string.no_data)
                        binding.tvWatch.text = getString(
                            R.string.watch_live,
                            m.heartRate?.toString() ?: none,
                            m.spo2?.toString() ?: none,
                            m.steps?.toString() ?: none,
                            m.distanceMetres?.toString() ?: none,
                            m.caloriesKcal?.toString() ?: none,
                            if (m.connected) "connected" else "disconnected",
                        )
                    }
                }

                launch {
                    watchVm.sleep.collect { t ->
                        val empty = getString(R.string.sleep_empty)
                        binding.tvSleepTotal.text = t?.let { fmt(it.totalMin) } ?: empty
                        binding.tvSleepDeep.text = t?.let { fmt(it.deepMin) } ?: empty
                        binding.tvSleepLight.text = t?.let { fmt(it.lightMin) } ?: empty
                        binding.tvSleepAwake.text = t?.let { fmt(it.awakeMin) } ?: empty
                    }
                }
            }
        }

        startWatch()
    }

    private fun startWatch() {
        // BLUETOOTH_CONNECT is a runtime permission only on API 31+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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