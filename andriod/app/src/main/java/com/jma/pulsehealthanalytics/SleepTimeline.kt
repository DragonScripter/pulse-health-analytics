package com.jma.pulsehealthanalytics

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One decoded segment as it comes off the watch: time of day only, no date. */
data class RawSleepSegment(
    val hour: Int,
    val minute: Int,
    val stage: Int,
    val durationMin: Int,
)

/** A segment with a real timestamp, ready to store / post. */
data class SleepSegment(
    val startTimeMillis: Long,
    val stage: Int,
    val durationMin: Int,
)

object SleepTimeline {

    /**
     * Turns time-of-day segments (in chronological order) into real timestamps.
     *
     * The watch sends no dates, so we anchor on [wakeDate] (the phone's date at wake-up):
     *  1. walk the segments forward, bumping the day whenever the clock wraps past midnight
     *  2. shift the whole night so the LAST segment starts on [wakeDate]
     *
     * A night that crosses midnight therefore starts on the previous day; a night that
     * doesn't (e.g. a nap or a 00:30 -> 07:00 sleep) stays entirely on [wakeDate].
     * The last segment is assumed not to cross midnight itself (it is the wake-up segment).
     */
    fun toTimestamps(
        segments: List<RawSleepSegment>,
        wakeDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SleepSegment> {
        if (segments.isEmpty()) return emptyList()

        val dayOffsets = IntArray(segments.size)
        var day = 0
        var prevMinuteOfDay = -1
        segments.forEachIndexed { i, s ->
            val minuteOfDay = s.hour * 60 + s.minute
            if (prevMinuteOfDay != -1 && minuteOfDay < prevMinuteOfDay) day++
            dayOffsets[i] = day
            prevMinuteOfDay = minuteOfDay
        }

        val shiftDays = -dayOffsets.last().toLong()
        return segments.mapIndexed { i, s ->
            val date = wakeDate.plusDays(dayOffsets[i] + shiftDays)
            val start = LocalDateTime.of(date, java.time.LocalTime.of(s.hour, s.minute))
            SleepSegment(
                startTimeMillis = start.atZone(zone).toInstant().toEpochMilli(),
                stage = s.stage,
                durationMin = s.durationMin,
            )
        }
    }
}