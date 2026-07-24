package dev.asik.devicebridge.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeUtil {
    private val formatter = DateTimeFormatter.ISO_INSTANT
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun nowIso(): String = formatter.format(Instant.now())

    /** Local wall-clock HH:mm:ss for an epoch-millis timestamp (for log display). */
    fun clockOf(epochMs: Long): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
