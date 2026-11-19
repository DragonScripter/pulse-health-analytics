package com.jma.pulsehealthanalytics

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WeightFragment : Fragment(R.layout.fragment_weight) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val et = view.findViewById<EditText>(R.id.etBodyWeight)
        val btn = view.findViewById<MaterialButton>(R.id.btnSaveWeight)
        val status = view.findViewById<TextView>(R.id.tvWeightStatus)
        val avg = view.findViewById<TextView>(R.id.tvWeightAvg)
        val velocity = view.findViewById<TextView>(R.id.tvWeightVelocity)
        val history = view.findViewById<TextView>(R.id.tvWeightHistory)
        val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d")

        suspend fun load() {
            val t = WeightApi.trend()
            if (t == null) {
                avg.text = "—"
                velocity.text = "Couldn't load your weight history."
                history.text = ""
                return
            }
            avg.text = t.currentAvg?.let { "%.1f lbs".format(it) } ?: "—"
            velocity.text =
                if (t.currentAvg == null) "No weigh-ins yet. Log your first one below."
                else "7-day average · ${velocityText(t.velocityPerWeek)}"
            history.text = t.entries.takeLast(7).reversed().joinToString("\n") {
                "${LocalDate.parse(it.date).format(dayFmt)}   ${"%.1f".format(it.lbs)} lbs   (avg ${"%.1f".format(it.avg7)})"
            }
        }

        btn.setOnClickListener {
            val lbs = et.text.toString().trim().toDoubleOrNull()
            if (lbs == null || lbs < 50 || lbs > 700) {
                status.text = "Enter a weight between 50 and 700 lbs."
                return@setOnClickListener
            }
            btn.isEnabled = false
            status.text = "Saving…"
            viewLifecycleOwner.lifecycleScope.launch {
                val err = WeightApi.save(lbs)
                if (err == null) {
                    status.text = "Saved."
                    et.text?.clear()
                    load()
                } else {
                    status.text = "Not saved ($err)"
                }
                btn.isEnabled = true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch { load() }
    }
}