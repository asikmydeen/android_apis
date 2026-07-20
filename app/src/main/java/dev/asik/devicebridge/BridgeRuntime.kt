package dev.asik.devicebridge

import android.content.Context
import dev.asik.devicebridge.collectors.BatteryCollector
import dev.asik.devicebridge.collectors.CameraCollector
import dev.asik.devicebridge.collectors.CapabilityScanner
import dev.asik.devicebridge.collectors.LocationCollector
import dev.asik.devicebridge.collectors.NetworkCollector
import dev.asik.devicebridge.collectors.SensorCollector
import dev.asik.devicebridge.collectors.TelephonyCollector
import dev.asik.devicebridge.collectors.UsbCollector
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.server.BridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide runtime shared by the UI and foreground service.
 */
object BridgeRuntime {
    const val DEFAULT_BIND = "127.0.0.1"
    const val DEFAULT_PORT = 8765
    const val VERSION = "1.1.0"

    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)

    val hub = StreamHub()

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
    lateinit var cameraCollector: CameraCollector
        private set
    lateinit var usbCollector: UsbCollector
        private set

    private var server: BridgeServer? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _startedAtMs = MutableStateFlow(0L)
    val startedAtMs: StateFlow<Long> = _startedAtMs.asStateFlow()

    val bindHost: String = DEFAULT_BIND
    val port: Int = DEFAULT_PORT

    fun baseUrl(): String = "http://$bindHost:$port"

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
        cameraCollector = CameraCollector(app, hub)
        usbCollector = UsbCollector(app, hub, scope)
    }

    fun requireContext(): Context =
        appContext ?: error("BridgeRuntime not initialized")

    suspend fun startServer() {
        init(requireContext())
        if (server != null) {
            _running.value = true
            return
        }
        startCollectors()
        val s = BridgeServer(
            context = requireContext(),
            hub = hub,
            capabilityScanner = capabilityScanner,
            cameraCollector = cameraCollector,
            usbCollector = usbCollector,
            bindHost = bindHost,
            port = port,
            version = VERSION,
            startedAtMsProvider = { _startedAtMs.value },
        )
        s.start()
        server = s
        _startedAtMs.value = System.currentTimeMillis()
        _running.value = true
    }

    fun startCollectors() {
        batteryCollector.start()
        sensorCollector.start()
        locationCollector.start()
        networkCollector.start()
        telephonyCollector.start()
        usbCollector.start()
    }

    fun stopCollectors() {
        batteryCollector.stop()
        sensorCollector.stop()
        locationCollector.stop()
        networkCollector.stop()
        telephonyCollector.stop()
        usbCollector.stop()
    }

    suspend fun stopServer() {
        server?.stop()
        server = null
        stopCollectors()
        _running.value = false
        _startedAtMs.value = 0L
    }

    fun dispose() {
        job.cancel()
    }
}
