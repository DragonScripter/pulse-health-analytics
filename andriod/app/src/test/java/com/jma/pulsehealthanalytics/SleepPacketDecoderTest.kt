package com.jma.pulsehealthanalytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SleepPacketDecoderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val utc = ZoneId.of("UTC")

    // Header + first four records of the verified night (README).
    private val sample = bytes(
        0x32,
        0x02, 0x02, 0x02, 0x01, 0x00, 0x0C,
        0x02, 0x0E, 0x03, 0x01, 0x00, 0x03,
        0x02, 0x11, 0x02, 0x01, 0x00, 0x01,
        0x02, 0x12, 0x01, 0x01, 0x00, 0x1A,
    )

    @Test fun decodesRecords() {
        val s = SleepPacketDecoder.decode(sample)!!
        assertEquals(4, s.size)
        assertEquals(RawSleepSegment(2, 2, SleepStage.LIGHT, 12), s[0])
        assertEquals(RawSleepSegment(2, 14, SleepStage.AWAKE, 3), s[1])
        assertEquals(RawSleepSegment(2, 18, SleepStage.DEEP, 26), s[3])
    }

    @Test fun totals() {
        val t = SleepPacketDecoder.totals(SleepPacketDecoder.decode(sample)!!)
        assertEquals(SleepTotals(deepMin = 26, lightMin = 13, awakeMin = 3), t)
    }

    @Test fun rejectsBadLengthAndHeader() {
        assertNull(SleepPacketDecoder.decode(sample.copyOf(sample.size - 1)))
        val badHeader = sample.copyOf().also { it[0] = 0x31 }
        assertNull(SleepPacketDecoder.decode(badHeader))
    }

    @Test fun nightWithoutMidnightStaysOnWakeDate() {
        val segs = SleepTimeline.toTimestamps(SleepPacketDecoder.decode(sample)!!, LocalDate.of(2026, 11, 11), utc)
        assertEquals(Instant.parse("2026-11-11T02:02:00Z").toEpochMilli(), segs[0].startTimeMillis)
    }

    @Test fun midnightCrossingStartsPreviousDay() {
        val raw = listOf(
            RawSleepSegment(23, 50, SleepStage.LIGHT, 20),
            RawSleepSegment(0, 10, SleepStage.DEEP, 30),
        )
        val segs = SleepTimeline.toTimestamps(raw, LocalDate.of(2026, 11, 11), utc)
        assertEquals(Instant.parse("2026-11-10T23:50:00Z").toEpochMilli(), segs[0].startTimeMillis)
        assertEquals(Instant.parse("2026-11-11T00:10:00Z").toEpochMilli(), segs[1].startTimeMillis)
    }
}