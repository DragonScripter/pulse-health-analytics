package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.platform.client.impl.ipc.Client
import com.jma.pulsehealthanalytics.databinding.FragmentHealthBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.Duration

class HealthFragment : Fragment(R.layout.fragment_health) {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!

    private lateinit var client: HealthConnectClient

    private val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
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
            val sleepStart = end.minus(2, ChronoUnit.DAYS)
            val range = TimeRangeFilter.between(start, end)
            //heart rate
            val heartRecords = readAll<HeartRateRecord>(start, end)

            val samples = heartRecords
                .flatMap { it.samples }
                .sortedByDescending { it.time }

            val lastSample = samples.firstOrNull()

            val lastBpm = lastSample?.beatsPerMinute
            val lastTime = lastSample?.time
                ?.atZone(ZoneId.systemDefault())
                ?.toLocalTime()
            //resting heart rate
            val restingRecord = readAll<RestingHeartRateRecord>(start,end)
                .maxByOrNull { it.time }
                ?.beatsPerMinute

            //sleep
            val lastSleep = readAll<SleepSessionRecord>(sleepStart, end)
                .maxByOrNull { it.endTime }
            val sleepHours = lastSleep?.let{
                Duration.between(it.startTime, it.endTime).toMinutes()/60.0
            }
            //steps
            val steps = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range)
            )[StepsRecord.COUNT_TOTAL]

            //heart rate list
            val list = samples.take(100).joinToString("\n") {
                "${it.time.atZone(ZoneId.systemDefault()).toLocalTime()}  ${it.beatsPerMinute} bpm"
            }
            binding.tvResult.text = getString(
                R.string.health_result,
                lastBpm?.toString() ?: getString(R.string.no_data),
                lastTime?.toString() ?: "-",
                (steps ?: 0).toString(),
                restingRecord?.toString() ?: getString(R.string.no_data),
                sleepHours?.let { String.format("%.1f", it) } ?: getString(R.string.no_data),
                list
            )
        }
    }
    private suspend inline fun<reified T : Record> readAll(start: Instant, end: Instant): List<T>{
        val all = mutableListOf<T>()
        var pageToken: String? = null

        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
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