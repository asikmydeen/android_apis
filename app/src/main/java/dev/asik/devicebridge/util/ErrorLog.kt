package dev.asik.devicebridge.util

import dev.asik.devicebridge.model.LogEntry
import java.util.ArrayDeque

/**
 * Process-wide ring buffer of recent errors/warnings for diagnostics.
 */
object ErrorLog {
    private const val CAPACITY = 200
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(CAPACITY)

    fun info(code: String, message: String, detail: String? = null) =
        add("info", code, message, detail)

    fun warn(code: String, message: String, detail: String? = null) =
        add("warn", code, message, detail)

    fun error(code: String, message: String, detail: String? = null) =
        add("error", code, message, detail)

    private fun add(level: String, code: String, message: String, detail: String?) {
        val entry = LogEntry(
            ts = TimeUtil.nowIso(),
            level = level,
            code = code,
            message = message,
            detail = detail,
        )
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun recent(n: Int = 50): List<LogEntry> {
        synchronized(lock) {
            return entries.toList().takeLast(n.coerceIn(1, CAPACITY))
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
