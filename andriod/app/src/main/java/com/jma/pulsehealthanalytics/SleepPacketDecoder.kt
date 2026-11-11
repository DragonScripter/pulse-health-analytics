package com.jma.pulsehealthanalytics

import android.util.Log

/** Stage codes as sent by the watch (verified against GloryFit). */
object SleepStage {
    const val DEEP = 1
    const val LIGHT = 2
    const val AWAKE = 3
}

data class SleepTotals(val deepMin: Int, val lightMin: Int, val awakeMin: Int) {
    val totalMin: Int get() = deepMin + lightMin + awakeMin
}

/**
 * Decodes the 0x34F2 sleep packet:
 *   byte 0  header 0x32
 *   then 6-byte records: [hour][minute][stage][01][00][duration_min]
 * Times are plain binary time of day; there is no date in the packet.
 */
object SleepPacketDecoder {
    private const val TAG = "SleepPacketDecoder"
    private const val HEADER = 0x32
    private const val RECORD_SIZE = 6

    /** Returns null if the packet is not a well-formed sleep packet. */
    fun decode(raw: ByteArray): List<RawSleepSegment>? {
        if (raw.size < 1 + RECORD_SIZE || (raw.size - 1) % RECORD_SIZE != 0) {
            Log.w(TAG, "Unexpected sleep packet length ${raw.size}")
            return null
        }
        if ((raw[0].toInt() and 0xFF) != HEADER) {
            Log.w(TAG, "Unexpected sleep header %02X".format(raw[0]))
            return null
        }

        val out = ArrayList<RawSleepSegment>((raw.size - 1) / RECORD_SIZE)
        var i = 1
        while (i < raw.size) {
            val hour = raw[i].toInt() and 0xFF
            val minute = raw[i + 1].toInt() and 0xFF
            val stage = raw[i + 2].toInt() and 0xFF
            val duration = raw[i + 5].toInt() and 0xFF
            if (hour > 23 || minute > 59 || stage !in SleepStage.DEEP..SleepStage.AWAKE || duration == 0) {
                Log.w(TAG, "Bad record at offset $i: h=$hour m=$minute stage=$stage dur=$duration")
                return null
            }
            val seg = RawSleepSegment(hour, minute, stage, duration)
            out.lastOrNull()?.let { prev ->
                val expected = (prev.hour * 60 + prev.minute + prev.durationMin) % (24 * 60)
                if (expected != hour * 60 + minute) {
                    // Records are expected to be contiguous. Keep the data, but flag it.
                    Log.w(TAG, "Gap/overlap before record at offset $i")
                }
            }
            out.add(seg)
            i += RECORD_SIZE
        }
        return out
    }

    fun totals(segments: List<RawSleepSegment>): SleepTotals {
        var deep = 0; var light = 0; var awake = 0
        for (s in segments) when (s.stage) {
            SleepStage.DEEP -> deep += s.durationMin
            SleepStage.LIGHT -> light += s.durationMin
            SleepStage.AWAKE -> awake += s.durationMin
        }
        return SleepTotals(deep, light, awake)
    }
}