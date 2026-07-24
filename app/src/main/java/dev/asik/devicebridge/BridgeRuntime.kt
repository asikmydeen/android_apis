package dev.asik.devicebridge

import android.content.Context
import dev.asik.devicebridge.collectors.ActuatorController
import dev.asik.devicebridge.collectors.AudioCollector
import dev.asik.devicebridge.collectors.BatteryCollector
import dev.asik.devicebridge.collectors.CameraCollector
import dev.asik.devicebridge.collectors.CapabilityScanner
import dev.asik.devicebridge.collectors.LocationCollector
import dev.asik.devicebridge.collectors.NetworkCollector
import dev.asik.devicebridge.collectors.SensorCollector
import dev.asik.devicebridge.collectors.TelephonyCollector
import dev.asik.devicebridge.collectors.TouchCollector
import dev.asik.devicebridge.collectors.UsbCollector
import dev.asik.devicebridge.hub.ConnectionRegistry
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.server.BridgeServer
import dev.asik.devicebridge.service.BridgeForegroundService
import dev.asik.devicebridge.util.BridgePrefs
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.MdnsAdvertiser
import dev.asik.devicebridge.util.NetworkAddresses
import dev.asik.devicebridge.util.NetworkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide runtime shared by the UI and foreground service.
 */
object BridgeRuntime {
    const val VERSION = "1.3.4"

    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)

    val hub = StreamHub()
    val registry = ConnectionRegistry()

    @Volatile
    private var appContext: Context? = null

    lateinit var capabilityScanner: CapabilityScanner
        private set
    lateinit var batteryCollector: BatteryCollector
        private set
    lateinit var sensorCollector: SensorCollector
        private set
    lateinit var locationCollector: LocationCollector
        private set
    lateinit var networkCollector: NetworkCollector
        private set
    lateinit var telephonyCollector: TelephonyCollector
        private set
    lateinit var audioCollector: AudioCollector
        private set
    lateinit var touchCollector: TouchCollector
        private set
    lateinit var cameraCollector: CameraCollector
        private set
    lateinit var usbCollector: UsbCollector
        private set
    lateinit var actuator: ActuatorController
        private set

    private var server: BridgeServer? = null
    private val serverMutex = Mutex()
    private var mdns: MdnsAdvertiser? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs.asStateFlow()

    private val _bindHost = MutableStateFlow("127.0.0.1")
    val bindHostState: StateFlow<String> = _bindHost.asStateFlow()

    private val _port = MutableStateFlow(8765)
    val portState: StateFlow<Int> = _port.asStateFlow()

    fun bindHost(): String = _bindHost.value
    fun port(): Int = _port.value

    fun baseUrl(): String = "http://127.0.0.1:${port()}"

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        capabilityScanner = CapabilityScanner(app)
        batteryCollector = BatteryCollector(app, hub, scope)
        sensorCollector = SensorCollector(app, hub)
        locationCollector = LocationCollector(app, hub)
        networkCollector = NetworkCollector(app, hub)
        telephonyCollector = TelephonyCollector(app, hub, scope)
        audioCollector = AudioCollector(app, hub, scope)
        touchCollector = TouchCollector(app, hub)
        cameraCollector = CameraCollector(app, hub)
        usbCollector = UsbCollector(app, hub, scope)
        actuator = ActuatorController(app)
        refreshNetworkConfigFromPrefs()
    }

    fun requireContext(): Context =
        appContext ?: error("BridgeRuntime not initialized")

    fun refreshNetworkConfigFromPrefs() {
        val ctx = appContext ?: return
        _bindHost.value = BridgePrefs.bindHost(ctx)
        _port.value = BridgePrefs.port(ctx)
    }

    fun accessUrls(): List<String> {
        val ctx = appContext ?: return listOf(baseUrl())
        val p = port()
        val urls = NetworkAddresses.urlsForPort(p).toMutableList()
        val publicUrl = BridgePrefs.publicUrl(ctx)
        if (publicUrl.isNotBlank()) urls.add(0, publicUrl)
        return urls.distinct()
    }

    fun cloudflaredCommand(): String =
        "cloudflared tunnel --url http://127.0.0.1:${port()}"

    suspend fun startServer() {
        serverMutex.withLock {
            init(requireContext())
            refreshNetworkConfigFromPrefs()
            if (server != null) {
                _running.value = true
                return
            }
            startCollectors()
            val ctx = requireContext()
            val bind = BridgePrefs.bindHost(ctx)
            val p = BridgePrefs.port(ctx)
            val auth = BridgePrefs.authEnabled(ctx)
            val token = if (auth) BridgePrefs.authToken(ctx) else null
            _bindHost.value = bind
            _port.value = p
            val s = BridgeServer(
                context = ctx,
                hub = hub,
                capabilityScanner = capabilityScanner,
                cameraCollector = cameraCollector,
                usbCollector = usbCollector,
                actuator = actuator,
                registry = registry,
                bindHost = bind,
                port = p,
                version = VERSION,
                startedAtMsProvider = { _startedAtMs.value },
                authToken = token,
                authEnabled = auth,
                networkMode = BridgePrefs.networkMode(ctx).name,
                publicUrlProvider = { BridgePrefs.publicUrl(ctx) },
            )
            try {
                s.start()
                server = s
                _startedAtMs.value = System.currentTimeMillis()
                _running.value = true
                ErrorLog.info(
                    "server_start",
                    "listening $bind:$p mode=${BridgePrefs.networkMode(ctx)} auth=$auth",
                )
                // Advertise on the LAN via mDNS when reachable there, so clients can
                // discover the phone by service name instead of a DHCP IP.
                val mode = BridgePrefs.networkMode(ctx)
                if (mode == NetworkMode.LAN || mode == NetworkMode.TAILSCALE) {
                    mdns = MdnsAdvertiser(ctx).also { it.register(p, VERSION, auth) }
                }
            } catch (e: Exception) {
                ErrorLog.error("server_start_failed", e.message ?: "start failed")
                stopCollectors()
                throw e
            }
        }
    }

    fun startCollectors() {
        val ctx = requireContext()
        batteryCollector.start()
        if (BridgePrefs.streamSensors(ctx)) sensorCollector.start()
        if (BridgePrefs.streamLocation(ctx)) locationCollector.start()
        if (BridgePrefs.streamAudio(ctx)) audioCollector.start()
        if (BridgePrefs.streamTouch(ctx)) touchCollector.start()
        networkCollector.start()
        telephonyCollector.start()
        if (BridgePrefs.streamUsb(ctx)) usbCollector.start()
    }

    fun stopCollectors() {
        batteryCollector.stop()
        sensorCollector.stop()
        locationCollector.stop()
        audioCollector.stop()
        touchCollector.stop()
        networkCollector.stop()
        telephonyCollector.stop()
        usbCollector.stop()
    }

    suspend fun stopServer() {
        serverMutex.withLock {
            mdns?.unregister()
            mdns = null
            server?.stop()
            server = null
            stopCollectors()
            _running.value = false
            _startedAtMs.value = 0L
            ErrorLog.info("server_stop", "Bridge stopped")
        }
    }

    /**
     * Panic kill-switch: hard-disconnect every client and rotate the token so REST
     * callers are locked out instantly. The bridge + collectors stay alive. Clients
     * must re-pair with the new token. Returns the new token.
     */
    suspend fun disconnectAllClients(): String {
        registry.closeAll("disconnected by owner")
        val newToken = BridgePrefs.rotateToken(requireContext())
        // Token is captured at server construction, so bounce the server to apply it.
        restartServerIfRunning()
        ErrorLog.warn("panic_disconnect", "all clients disconnected; token rotated")
        return newToken
    }

    /** Apply prefs and bounce the server if it was running. */
    suspend fun restartServerIfRunning() {
        val was = _running.value
        if (was) {
            stopServer()
            startServer()
            // Collector toggles (esp. Microphone) change the FGS type mask required
            // for background capture on Android 14+. Refresh so AudioRecord isn't
            // silenced the moment the UI leaves the foreground.
            runCatching {
                BridgeForegroundService.refreshTypes(requireContext())
            }
        } else {
            refreshNetworkConfigFromPrefs()
        }
    }

    fun dispose() {
        job.cancel()
    }
}
