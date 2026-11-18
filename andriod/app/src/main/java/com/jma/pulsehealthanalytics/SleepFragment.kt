package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jma.pulsehealthanalytics.databinding.FragmentSleepBinding
import kotlinx.coroutines.launch

class SleepFragment : Fragment(R.layout.fragment_sleep) {

    private val watchVm: WatchStreamViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentSleepBinding.bind(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchVm.sleep.collect { s ->
                    val t = s.totals
                    b.tvAsleep.text = t?.let { fmtDuration(it.asleepMin) } ?: "—"
                    b.tvInBed.text = t?.let { "asleep · ${fmtDuration(it.totalMin)} in bed" } ?: ""
                    b.tvDeep.text = t?.let { "Deep\n${fmtDuration(it.deepMin)}" } ?: ""
                    b.tvLight.text = t?.let { "Light\n${fmtDuration(it.lightMin)}" } ?: ""
                    b.tvAwake.text = t?.let { "Awake\n${fmtDuration(it.awakeMin)}" } ?: ""
                    b.tvStatus.text = sleepStatusText(requireContext(), s)
                }
            }
        }
    }
}