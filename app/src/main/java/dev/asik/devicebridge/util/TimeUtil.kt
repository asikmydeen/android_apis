package dev.asik.devicebridge.util

import java.time.Instant
import java.time.format.DateTimeFormatter

object TimeUtil {
    private val formatter = DateTimeFormatter.ISO_INSTANT

    fun nowIso(): String = formatter.format(Instant.now())
}
