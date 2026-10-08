package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.jma.pulsehealthanalytics.databinding.FragmentHealthBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HealthFragment : Fragment(R.layout.fragment_health) {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!

    private lateinit var client: HealthConnectClient

    private val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(permissions)) readData()
        else binding.tvResult.text = getString(R.string.permission_denied)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHealthBinding.bind(view)
        binding.btnRead.setOnClickListener { checkAndRead() }
    }

    private fun checkAndRead() {
        when (HealthConnectClient.getSdkStatus(requireContext())) {
            HealthConnectClient.SDK_AVAILABLE -> {
                client = HealthConnectClient.getOrCreate(requireContext())
                viewLifecycleOwner.lifecycleScope.launch {
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.containsAll(permissions)) readData()
                    else requestPermissions.launch(permissions)
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                binding.tvResult.text = getString(R.string.health_update_required)
            else ->
                binding.tvResult.text = getString(R.string.health_unavailable)
        }
    }

    private fun readData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val end = Instant.now()
            val start = end.minus(1, ChronoUnit.DAYS)
            val range = TimeRangeFilter.between(start, end)

            val records = readAllHeartRate(start, end)

            val samples = records
                .flatMap { it.samples }
                .sortedByDescending { it.time }

            val lastSample = samples.firstOrNull()

            val lastBpm = lastSample?.beatsPerMinute
            val lastTime = lastSample?.time
                ?.atZone(ZoneId.systemDefault())
                ?.toLocalTime()

            val steps = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range)
            )[StepsRecord.COUNT_TOTAL]

            binding.tvResult.text = getString(
                R.string.health_result,
                lastBpm?.toString() ?: getString(R.string.no_data),
                lastTime?.toString() ?: "-",
                (steps ?: 0).toString()
            )
            val list = samples.take(100).joinToString("\n") {
                "${it.time.atZone(ZoneId.systemDefault()).toLocalTime()}  ${it.beatsPerMinute} bpm"
            }
            binding.tvResult.text = binding.tvResult.text.toString() + "\n\n" + list
        }
    }
    private suspend fun readAllHeartRate(start: Instant, end: Instant): List<HeartRateRecord>{
        val all = mutableListOf<HeartRateRecord>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start,end),
                    pageSize = 1000,
                    pageToken = pageToken
                )
            )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}