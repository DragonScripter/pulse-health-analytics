package com.jma.pulsehealthanalytics

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors

/** Implement with your existing authenticated HTTP client (POST /sleep, JWT). */
fun interface SleepUploader {
    /** Returns true only when the server accepted the night (2xx). */
    fun upload(segments: List<SleepSegment>): Boolean
}

/**
 * Receives raw sleep packets, decodes them and posts each distinct night exactly once.
 * The watch may re-send the same packet (every reconnect, every subscribe), so a night
 * is identified by its first start time + a hash of its content and only marked as
 * posted after the server confirms. The server also ignores duplicates, so a retry
 * after a lost response is safe.
 *
 * The packet has no dates, so the wake date is decided ONCE per distinct packet and
 * remembered. A re-send on a later day reuses the stored date instead of shifting the night.
 */
class SleepNightSync(
    context: Context,
    private val uploader: SleepUploader,
) {
    private val prefs = context.getSharedPreferences("sleep_sync", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    fun onSleepPacket(raw: ByteArray) {
        // Raw bytes were already logged by WatchBleManager. Decode off the BLE thread.
        val copy = raw.copyOf()
        executor.execute { process(copy) }
    }

    private fun process(raw: ByteArray) {
        val decoded = SleepPacketDecoder.decode(raw) ?: return
        val segments = SleepTimeline.toTimestamps(decoded, resolveWakeDate(decoded, raw))
        if (segments.isEmpty()) return

        val totals = SleepPacketDecoder.totals(decoded)
        val key = "${segments.first().startTimeMillis}:${segments.hashCode()}"
        if (prefs.getString(KEY_LAST_POSTED, null) == key) {
            Log.d(TAG, "Night already posted, skipping")
            return
        }

        Log.i(TAG, "Posting night: deep=${totals.deepMin} light=${totals.lightMin} " +
                "awake=${totals.awakeMin} total=${totals.totalMin} min")
        val ok = try {
            uploader.upload(segments)
        } catch (e: Exception) {
            Log.w(TAG, "Sleep upload failed", e)
            false
        }
        if (ok) prefs.edit().putString(KEY_LAST_POSTED, key).apply()
        // On failure we do nothing: the next packet (reconnect) retries.
    }

    /**
     * Picks the wake date once per distinct packet and reuses it for every re-send.
     * First sight: today, unless the last segment would start in the future
     * (packet arrived just after midnight), in which case yesterday.
     */
    private fun resolveWakeDate(decoded: List<RawSleepSegment>, raw: ByteArray): LocalDate {
        val hex = raw.joinToString("") { "%02x".format(it) }
        if (prefs.getString(KEY_ANCHOR_RAW, null) == hex) {
            prefs.getString(KEY_ANCHOR_DATE, null)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()?.let { d -> return d }
            }
        }
        val last = decoded.last()
        val now = LocalDateTime.now()
        val lastStart = LocalDateTime.of(now.toLocalDate(), LocalTime.of(last.hour, last.minute))
        val date = if (lastStart.isAfter(now)) now.toLocalDate().minusDays(1) else now.toLocalDate()
        prefs.edit()
            .putString(KEY_ANCHOR_RAW, hex)
            .putString(KEY_ANCHOR_DATE, date.toString())
            .apply()
        return date
    }

    fun shutdown() = executor.shutdown()

    private companion object {
        const val TAG = "SleepNightSync"
        const val KEY_LAST_POSTED = "last_posted_night"
        const val KEY_ANCHOR_RAW = "anchor_raw"
        const val KEY_ANCHOR_DATE = "anchor_date"
    }
}