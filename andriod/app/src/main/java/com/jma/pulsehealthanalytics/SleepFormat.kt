package com.jma.pulsehealthanalytics

import android.content.Context
import android.text.format.DateUtils

fun fmtDuration(minutes: Int) = "${minutes / 60}h ${minutes % 60}m"

fun sleepStatusText(ctx: Context, s: SleepState): String {
    val at = s.receivedAtMillis ?: return "No sleep data received from the watch yet."
    val whenStr = DateUtils.formatDateTime(
        ctx, at,
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