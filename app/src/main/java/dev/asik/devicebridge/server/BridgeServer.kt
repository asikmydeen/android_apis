package dev.asik.devicebridge.server

import android.content.Context
import android.util.Base64
import dev.asik.devicebridge.collectors.CameraCollector
import dev.asik.devicebridge.collectors.CapabilityScanner
import dev.asik.devicebridge.collectors.UsbCollector
import dev.asik.devicebridge.hub.HubEvent
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.ApiError
import dev.asik.devicebridge.model.ApiErrorBody
import dev.asik.devicebridge.model.ClientControlMessage
import dev.asik.devicebridge.model.ConfigResponse
import dev.asik.devicebridge.model.DeviceSnapshot
import dev.asik.devicebridge.model.HealthResponse
import dev.asik.devicebridge.model.SimpleStatus
import dev.asik.devicebridge.model.StreamEnvelope
import dev.asik.devicebridge.util.DiagnosticsBuilder
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.PermissionHelper
import dev.asik.devicebridge.util.TimeUtil
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class BridgeServer(
    private val context: Context,
    private val hub: StreamHub,
    private val capabilityScanner: CapabilityScanner,
    private val cameraCollector: CameraCollector,
    private val usbCollector: UsbCollector,
    private val bindHost: String,
    private val port: Int,
    private val version: String,
    private val startedAtMsProvider: () -> Long,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val engineRef = AtomicReference<ApplicationEngine?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val engine = embeddedServer(CIO, port = port, host = bindHost) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(20)
                timeout = Duration.ofSeconds(30)
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorBody(
                            ApiError(
                                code = "internal_error",
                                message = cause.message ?: cause::class.java.simpleName,
                            ),
                        ),
                    )
                }
            }

            routing {
                get("/") {
                    call.respondText(
                        """
                        Device Bridge $version
                        See GET /v1/health, /v1/capabilities, /v1/snapshot
                        Base: http://$bindHost:$port
                        """.trimIndent(),
                        ContentType.Text.Plain,
                    )
                }

                get("/v1/health") {
                    val started = startedAtMsProvider()
                    val uptime = if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0
                    val diag = runCatching { DiagnosticsBuilder.build(context) }.getOrNull()
                    call.respond(
                        HealthResponse(
                            ok = diag?.ok ?: true,
                            version = version,
                            uptime_sec = uptime,
                            server_time = TimeUtil.nowIso(),
                            running = true,
                            degraded = diag?.degraded ?: false,
                        ),
                    )
                }

                get("/v1/diagnostics") {
                    call.respond(DiagnosticsBuilder.build(context))
                }

                get("/v1/debug/log") {
                    val n = call.request.queryParameters["n"]?.toIntOrNull() ?: 50
                    call.respond(ErrorLog.recent(n))
                }

                post("/v1/debug/log/clear") {
                    ErrorLog.clear()
                    call.respond(SimpleStatus(true, "log cleared"))
                }

                get("/v1/config") {
                    call.respond(
                        ConfigResponse(
                            bind = bindHost,
                            port = port,
                            base_url = "http://$bindHost:$port",
                            auth_enabled = false,
                            version = version,
                        ),
                    )
                }

                get("/v1/capabilities") {
                    call.respond(capabilityScanner.scan())
                }

                get("/v1/snapshot") {
                    call.respond(buildSnapshot())
                }

                get("/v1/location") {
                    val loc = DiagnosticsBuilder.enrichLocation(hub.location.value)
                    if (loc == null) {
                        if (!PermissionHelper.hasLocation(context)) {
                            call.respondPermissionDenied("ACCESS_FINE_LOCATION")
                        } else {
                            call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ApiErrorBody(
                                    ApiError(
                                        code = "no_fix",
                                        message = "No location fix yet (and no cached fix). Enable Location, go outdoors, keep bridge running.",
                                    ),
                                ),
                            )
                        }
                    } else {
                        // Always return last known; clients check `stale` / `age_sec`
                        call.respond(loc)
                    }
                }

                get("/v1/battery") {
                    val b = hub.battery.value
                    if (b == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorBody(ApiError("no_data", "Battery reading not available yet")),
                        )
                    } else {
                        call.respond(b)
                    }
                }

                get("/v1/network") {
                    val n = hub.network.value
                    if (n == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorBody(ApiError("no_data", "Network reading not available yet")),
                        )
                    } else {
                        call.respond(n)
                    }
                }

                get("/v1/telephony") {
                    val t = hub.telephony.value
                    if (t == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorBody(ApiError("no_data", "Telephony reading not available yet")),
                        )
                    } else {
                        call.respond(t)
                    }
                }

                get("/v1/sensors") {
                    call.respond(hub.sensorSnapshot())
                }

                get("/v1/sensors/{type}") {
                    val key = call.parameters["type"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing type")),
                    )
                    val snap = hub.sensorSnapshot()
                    val byName = snap[key]
                    val byType = snap.entries.firstOrNull {
                        it.value.type?.toString() == key || it.key.endsWith(key)
                    }?.value
                    val reading = byName ?: byType
                    if (reading == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorBody(ApiError("not_found", "sensor not found or no reading yet: $key")),
                        )
                    } else {
                        call.respond(reading)
                    }
                }

                get("/v1/cameras") {
                    call.respond(capabilityScanner.scan().cameras)
                }

                post("/v1/camera/{id}/capture") {
                    if (!PermissionHelper.hasCamera(context)) {
                        return@post call.respondPermissionDenied("CAMERA")
                    }
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing camera id")),
                    )
                    val includeBase64 = call.request.queryParameters["base64"] == "1" ||
                        call.request.queryParameters["base64"] == "true"
                    try {
                        val result = cameraCollector.capture(id, includeBase64)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiErrorBody(ApiError("capture_failed", e.message ?: "capture failed")),
                        )
                    }
                }

                get("/v1/camera/last.jpg") {
                    val file = cameraCollector.lastJpegFile()
                    if (file == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorBody(ApiError("not_found", "no capture yet; POST /v1/camera/{id}/capture first")),
                        )
                    } else {
                        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
                    }
                }

                post("/v1/control/start") {
                    call.respond(SimpleStatus(true, "collectors active"))
                }

                post("/v1/control/stop") {
                    call.respond(SimpleStatus(true, "use the app notification or UI to stop the bridge service"))
                }

                // --- USB host: devices, storage volumes, serial ---
                get("/v1/usb") {
                    call.respond(usbCollector.overview())
                }

                post("/v1/usb/rescan") {
                    call.respond(usbCollector.rescan())
                }

                get("/v1/usb/devices") {
                    call.respond(usbCollector.overview().devices)
                }

                get("/v1/usb/devices/{id}") {
                    val id = call.parameters["id"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing device id")),
                    )
                    val device = usbCollector.overview().devices.firstOrNull { it.device_id == id }
                    if (device == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorBody(ApiError("not_found", "USB device $id not connected")),
                        )
                    } else {
                        call.respond(device)
                    }
                }

                get("/v1/usb/storage") {
                    call.respond(usbCollector.overview().storage_volumes)
                }

                post("/v1/usb/devices/{id}/permission") {
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing device id")),
                    )
                    if (usbCollector.findDevice(id) == null) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            ApiErrorBody(ApiError("not_found", "USB device $id not connected")),
                        )
                    }
                    if (usbCollector.hasPermission(id)) {
                        call.respond(SimpleStatus(true, "already granted"))
                    } else {
                        usbCollector.requestPermission(id)
                        call.respond(
                            SimpleStatus(
                                true,
                                "permission dialog requested — accept on the phone, then retry serial open",
                            ),
                        )
                    }
                }

                post("/v1/usb/devices/{id}/serial/open") {
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing device id")),
                    )
                    val baud = call.request.queryParameters["baud"]?.toIntOrNull() ?: 9600
                    val result = usbCollector.openSerial(id, baud)
                    call.respond(
                        if (result.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                        result,
                    )
                }

                post("/v1/usb/devices/{id}/serial/close") {
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing device id")),
                    )
                    usbCollector.closeSerial(id)
                    call.respond(SimpleStatus(true, "serial closed for $id"))
                }

                post("/v1/usb/devices/{id}/serial/write") {
                    val id = call.parameters["id"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorBody(ApiError("bad_request", "missing device id")),
                    )
                    if (!usbCollector.isSerialOpen(id)) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiErrorBody(ApiError("not_open", "serial not open; POST .../serial/open first")),
                        )
                    }
                    val body = call.receiveText()
                    val bytes = when {
                        call.request.queryParameters["encoding"] == "base64" ->
                            Base64.decode(body.trim(), Base64.DEFAULT)
                        else -> body.toByteArray(Charsets.UTF_8)
                    }
                    try {
                        val n = usbCollector.writeSerial(id, bytes)
                        call.respond(SimpleStatus(n >= 0, "wrote $n bytes"))
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiErrorBody(ApiError("write_failed", e.message ?: "write failed")),
                        )
                    }
                }

                webSocket("/v1/usb/serial/{id}") {
                    val id = call.parameters["id"]
                    if (id == null) {
                        return@webSocket
                    }
                    sendJson(
                        StreamEnvelope(
                            topic = "hello",
                            ts = TimeUtil.nowIso(),
                            data = buildJsonObject {
                                put("device_id", id)
                                put("hint", "open serial first: POST /v1/usb/devices/$id/serial/open")
                            },
                        ),
                    )
                    hub.events.filter { it is HubEvent.UsbSerial && it.chunk.deviceId == id }.collect { event ->
                        val chunk = (event as HubEvent.UsbSerial).chunk
                        sendJson(
                            StreamEnvelope(
                                topic = "usb_serial",
                                ts = TimeUtil.nowIso(),
                                data = buildJsonObject {
                                    put("device_id", chunk.deviceId)
                                    put("bytes", chunk.bytes)
                                    put("base64", chunk.base64)
                                    chunk.text?.let { put("text", it) }
                                },
                            ),
                        )
                    }
                }

                webSocket("/v1/stream/usb") {
                    hub.usb.value?.let {
                        sendJson(StreamEnvelope("usb", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    hub.events.collect { event ->
                        when (event) {
                            is HubEvent.Usb -> sendJson(
                                StreamEnvelope("usb", TimeUtil.nowIso(), json.encodeToJsonElement(event.overview)),
                            )
                            is HubEvent.UsbEventMsg -> sendJson(
                                StreamEnvelope("usb_event", TimeUtil.nowIso(), json.encodeToJsonElement(event.event)),
                            )
                            else -> Unit
                        }
                    }
                }

                webSocket("/v1/stream") {
                    val initial = call.request.queryParameters["topics"]
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toMutableSet()
                        ?: mutableSetOf("location", "battery", "sensors", "network", "usb")

                    val topics = initial

                    sendJson(
                        StreamEnvelope(
                            topic = "hello",
                            ts = TimeUtil.nowIso(),
                            data = buildJsonObject {
                                put("protocol", 2)
                                put("server", "device-bridge")
                                put("topics", JsonPrimitive(topics.joinToString(",")))
                            },
                        ),
                    )

                    // Immediate last values
                    if ("location" in topics) hub.location.value?.let {
                        sendJson(StreamEnvelope("location", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    if ("battery" in topics) hub.battery.value?.let {
                        sendJson(StreamEnvelope("battery", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    if ("network" in topics) hub.network.value?.let {
                        sendJson(StreamEnvelope("network", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    if ("usb" in topics) hub.usb.value?.let {
                        sendJson(StreamEnvelope("usb", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    if ("sensors" in topics) {
                        val snap = hub.sensorSnapshot()
                        if (snap.isNotEmpty()) {
                            sendJson(
                                StreamEnvelope(
                                    "sensors",
                                    TimeUtil.nowIso(),
                                    json.encodeToJsonElement(snap),
                                ),
                            )
                        }
                    }

                    val collector = launch {
                        hub.events.collect { event ->
                            when (event) {
                                is HubEvent.Location -> if ("location" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "location",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.reading),
                                        ),
                                    )
                                }
                                is HubEvent.Battery -> if ("battery" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "battery",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.reading),
                                        ),
                                    )
                                }
                                is HubEvent.Network -> if ("network" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "network",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.reading),
                                        ),
                                    )
                                }
                                is HubEvent.Telephony -> if ("telephony" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "telephony",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.reading),
                                        ),
                                    )
                                }
                                is HubEvent.Sensor -> if ("sensors" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "sensors",
                                            TimeUtil.nowIso(),
                                            buildJsonObject {
                                                put(event.typeName, json.encodeToJsonElement(event.reading))
                                            },
                                        ),
                                    )
                                }
                                is HubEvent.Camera -> if ("camera" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "camera",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.meta),
                                        ),
                                    )
                                }
                                is HubEvent.Usb -> if ("usb" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "usb",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.overview),
                                        ),
                                    )
                                }
                                is HubEvent.UsbEventMsg -> if ("usb" in topics || "usb_event" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "usb_event",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.event),
                                        ),
                                    )
                                }
                                is HubEvent.UsbSerial -> if ("usb_serial" in topics) {
                                    val chunk = event.chunk
                                    sendJson(
                                        StreamEnvelope(
                                            "usb_serial",
                                            TimeUtil.nowIso(),
                                            buildJsonObject {
                                                put("device_id", chunk.deviceId)
                                                put("bytes", chunk.bytes)
                                                put("base64", chunk.base64)
                                                chunk.text?.let { put("text", it) }
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                runCatching {
                                    val msg = json.decodeFromString<ClientControlMessage>(text)
                                    when (msg.op) {
                                        "subscribe" -> msg.topics?.let { topics.addAll(it) }
                                        "unsubscribe" -> msg.topics?.let { topics.removeAll(it.toSet()) }
                                        "ping" -> sendJson(
                                            StreamEnvelope(
                                                "pong",
                                                TimeUtil.nowIso(),
                                                JsonObject(emptyMap()),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    } finally {
                        collector.cancel()
                    }
                }

                webSocket("/v1/stream/location") {
                    hub.location.value?.let {
                        sendJson(StreamEnvelope("location", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    hub.events.filter { it is HubEvent.Location }.collect { event ->
                        val reading = (event as HubEvent.Location).reading
                        sendJson(StreamEnvelope("location", TimeUtil.nowIso(), json.encodeToJsonElement(reading)))
                    }
                }

                webSocket("/v1/stream/sensors") {
                    hub.events.filter { it is HubEvent.Sensor }.collect { event ->
                        val e = event as HubEvent.Sensor
                        sendJson(
                            StreamEnvelope(
                                "sensors",
                                TimeUtil.nowIso(),
                                buildJsonObject {
                                    put(e.typeName, json.encodeToJsonElement(e.reading))
                                },
                            ),
                        )
                    }
                }
            }
        }

        engine.start(wait = false)
        engineRef.set(engine)
    }

    suspend fun stop() {
        engineRef.getAndSet(null)?.stop(1000, 2000)
        scope.cancel()
    }

    private fun buildSnapshot(): DeviceSnapshot {
        val errors = mutableListOf<String>()
        if (!PermissionHelper.hasLocation(context) && hub.location.value == null) {
            errors += "location: permission denied or no fix"
        }
        if (!PermissionHelper.hasCamera(context)) {
            errors += "camera: permission denied"
        }
        val loc = DiagnosticsBuilder.enrichLocation(hub.location.value)
        if (loc?.stale == true) {
            errors += "location: stale (${loc.age_sec}s old) — still returning last known"
        }
        return DeviceSnapshot(
            timestamp = TimeUtil.nowIso(),
            location = loc,
            battery = hub.battery.value,
            network = hub.network.value,
            telephony = hub.telephony.value,
            sensors = hub.sensorSnapshot(),
            camera_meta = hub.cameraMeta.value,
            usb = hub.usb.value ?: usbCollector.overview(),
            errors = errors,
        )
    }

    private suspend fun ApplicationCall.respondPermissionDenied(permission: String) {
        respond(
            HttpStatusCode.Forbidden,
            ApiErrorBody(
                ApiError(
                    code = "permission_denied",
                    message = "$permission not granted",
                    permission = "android.permission.$permission",
                ),
            ),
        )
    }

    private suspend fun io.ktor.websocket.DefaultWebSocketServerSession.sendJson(envelope: StreamEnvelope) {
        send(Frame.Text(json.encodeToString(envelope)))
    }
}
