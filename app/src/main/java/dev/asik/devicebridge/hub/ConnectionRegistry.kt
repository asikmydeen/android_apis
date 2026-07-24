package dev.asik.devicebridge.hub

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks who is talking to the bridge right now and what they recently did — the
 * keystone for every trust/observability feature (connected-clients view, panic
 * kill-switch, in-app audit log, honest privacy dots).
 *
 * Two datasets:
 *  - active WebSocket sessions (with a handle so they can be force-closed)
 *  - a rolling ring buffer of accepted requests (the audit trail)
 *
 * Plus per-signal "last remote read" timestamps so the Dashboard can glow a tile
 * only when a remote client is actually reading that signal — not merely when the
 * local collector has fresh data.
 */
class ConnectionRegistry {

    data class ClientInfo(
        val id: Long,
        val remoteIp: String,
        val topics: List<String>,
        val connectedAtMs: Long,
        val kind: String, // e.g. "stream", "stream/audio/pcm", "usb/serial"
    )

    data class RequestEntry(
        val tsMs: Long,
        val remoteIp: String,
        val method: String,
        val path: String,
        val status: Int,
    )

    private class Session(
        val info: ClientInfo,
        val session: DefaultWebSocketServerSession,
    )

    private val idGen = AtomicLong(0)
    private val sessions = ConcurrentHashMap<Long, Session>()

    private val _activeClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val activeClients: StateFlow<List<ClientInfo>> = _activeClients.asStateFlow()

    private val requestLog = ArrayDeque<RequestEntry>()
    private val requestLogLock = Any()
    private val _recentRequests = MutableStateFlow<List<RequestEntry>>(emptyList())
    val recentRequests: StateFlow<List<RequestEntry>> = _recentRequests.asStateFlow()

    // Per-signal timestamp of the last remote read (REST or a delivered stream event).
    private val lastReadMs = ConcurrentHashMap<String, Long>()

    // ---- WebSocket session tracking ----------------------------------

    /** Register a live WS session; returns its id (pass to [remove] on close). */
    fun register(
        remoteIp: String,
        topics: List<String>,
        kind: String,
        session: DefaultWebSocketServerSession,
        nowMs: Long,
    ): Long {
        val id = idGen.incrementAndGet()
        sessions[id] = Session(
            ClientInfo(id, remoteIp, topics, nowMs, kind),
            session,
        )
        publishClients()
        return id
    }

    fun remove(id: Long) {
        sessions.remove(id)
        publishClients()
    }

    private fun publishClients() {
        _activeClients.value = sessions.values.map { it.info }.sortedBy { it.connectedAtMs }
    }

    fun activeCount(): Int = sessions.size

    /**
     * Force-close every active session (panic path). Suspends because WS close is
     * suspending. Safe to call even with no sessions.
     */
    suspend fun closeAll(reason: String) {
        val snapshot = sessions.values.toList()
        sessions.clear()
        publishClients()
        for (s in snapshot) {
            runCatching { s.session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        }
    }

    // ---- Request audit log -------------------------------------------

    fun recordRequest(remoteIp: String, method: String, path: String, status: Int, nowMs: Long) {
        val entry = RequestEntry(nowMs, remoteIp, method, path, status)
        synchronized(requestLogLock) {
            requestLog.addLast(entry)
            while (requestLog.size > MAX_LOG) requestLog.removeFirst()
            _recentRequests.value = requestLog.toList().asReversed() // newest first
        }
    }

    // ---- Per-signal remote-read tracking (privacy dots) --------------

    /** Mark that a remote client just read [signal] (e.g. "location", "audio"). */
    fun markRead(signal: String, nowMs: Long) {
        lastReadMs[signal] = nowMs
    }

    /**
     * True if [signal] is being remotely read right now: an active WS session carries
     * it as a topic, OR a REST read touched it within [windowMs].
     */
    fun isRemotelyActive(signal: String, nowMs: Long, windowMs: Long = 3000): Boolean {
        val recentRest = (lastReadMs[signal] ?: 0L) >= nowMs - windowMs
        if (recentRest) return true
        return sessions.values.any { it.info.topics.contains(signal) }
    }

    companion object {
        private const val MAX_LOG = 200
    }
}
