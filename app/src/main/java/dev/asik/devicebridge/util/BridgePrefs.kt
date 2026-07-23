package dev.asik.devicebridge.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class NetworkMode {
    /** Bind 127.0.0.1 only — Termux/Ubuntu on device */
    LOCAL,

    /** Bind 0.0.0.0 — same Wi‑Fi LAN (auth forced) */
    LAN,

    /** Bind 0.0.0.0 — access via Tailscale IP (auth forced) */
    TAILSCALE,

    /** Bind 127.0.0.1 — expose with cloudflared in Termux (auth forced for public URL) */
    CLOUDFLARE,
}

object BridgePrefs {
    private const val NAME = "device_bridge_prefs"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_NETWORK_MODE = "network_mode"
    private const val KEY_BIND_HOST = "bind_host"
    private const val KEY_PORT = "port"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_ENABLED = "auth_enabled"
    private const val KEY_PUBLIC_URL = "public_url"
    private const val KEY_STREAM_LOCATION = "stream_location"
    private const val KEY_STREAM_SENSORS = "stream_sensors"
    private const val KEY_STREAM_AUDIO = "stream_audio"
    private const val KEY_STREAM_TOUCH = "stream_touch"
    private const val KEY_STREAM_USB = "stream_usb"

    private val _themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        val mode = runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
        _themeModeFlow.value = mode
        return mode
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeModeFlow.value = mode
    }

    fun themeModeFlow(context: Context): StateFlow<ThemeMode> {
        themeMode(context)
        return _themeModeFlow.asStateFlow()
    }

    fun keepAwake(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_AWAKE, enabled).apply()
    }

    fun startOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    fun networkMode(context: Context): NetworkMode {
        val raw = prefs(context).getString(KEY_NETWORK_MODE, NetworkMode.LOCAL.name)
        return runCatching { NetworkMode.valueOf(raw ?: NetworkMode.LOCAL.name) }
            .getOrDefault(NetworkMode.LOCAL)
    }

    fun setNetworkMode(context: Context, mode: NetworkMode) {
        val forceAuth = mode != NetworkMode.LOCAL
        prefs(context).edit()
            .putString(KEY_NETWORK_MODE, mode.name)
            // Bind host is derived dynamically in bindHost() so it can't go stale
            // (the Tailscale IP in particular changes); don't persist it here.
            .remove(KEY_BIND_HOST)
            .putBoolean(KEY_AUTH_ENABLED, if (forceAuth) true else prefs(context).getBoolean(KEY_AUTH_ENABLED, false))
            .apply()
        if (forceAuth) {
            ensureToken(context)
        }
    }

    /**
     * The interface the server binds to. Computed at read time so TAILSCALE tracks
     * the current tailnet IP. TAILSCALE binds ONLY the Tailscale interface (not
     * 0.0.0.0) so the plaintext API isn't also exposed on the untrusted local Wi-Fi;
     * if no Tailscale IP is present it falls back to loopback (safe) rather than
     * all-interfaces (leaky).
     */
    fun bindHost(context: Context): String {
        return when (networkMode(context)) {
            NetworkMode.LOCAL, NetworkMode.CLOUDFLARE -> "127.0.0.1"
            NetworkMode.LAN -> "0.0.0.0"
            NetworkMode.TAILSCALE ->
                NetworkAddresses.tailscaleIps().firstOrNull() ?: "127.0.0.1"
        }
    }

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, 8765).coerceIn(1024, 65535)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun authEnabled(context: Context): Boolean {
        val mode = networkMode(context)
        if (mode != NetworkMode.LOCAL) return true
        return prefs(context).getBoolean(KEY_AUTH_ENABLED, false)
    }

    fun setAuthEnabled(context: Context, enabled: Boolean) {
        val mode = networkMode(context)
        val effective = if (mode != NetworkMode.LOCAL) true else enabled
        prefs(context).edit().putBoolean(KEY_AUTH_ENABLED, effective).apply()
        if (effective) ensureToken(context)
    }

    fun authToken(context: Context): String = ensureToken(context)

    fun rotateToken(context: Context): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        prefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
        return token
    }

    fun ensureToken(context: Context): String {
        val existing = prefs(context).getString(KEY_AUTH_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return rotateToken(context)
    }

    fun publicUrl(context: Context): String =
        prefs(context).getString(KEY_PUBLIC_URL, "") ?: ""

    fun setPublicUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_PUBLIC_URL, url.trim().trimEnd('/')).apply()
    }

    fun streamLocation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_LOCATION, true)

    fun setStreamLocation(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_LOCATION, v).apply()
    }

    fun streamSensors(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_SENSORS, true)

    fun setStreamSensors(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_SENSORS, v).apply()
    }

    fun streamAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_AUDIO, false)

    fun setStreamAudio(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_AUDIO, v).apply()
    }

    fun streamTouch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_TOUCH, true)

    fun setStreamTouch(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_TOUCH, v).apply()
    }

    fun streamUsb(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM_USB, true)

    fun setStreamUsb(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM_USB, v).apply()
    }
}
