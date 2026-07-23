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
import dev.asik.devicebridge.model.AudioReading
import dev.asik.devicebridge.model.ClientControlMessage
import dev.asik.devicebridge.model.ConfigResponse
import dev.asik.devicebridge.model.DeviceSnapshot
import dev.asik.devicebridge.model.HealthResponse
import dev.asik.devicebridge.model.SimpleStatus
import dev.asik.devicebridge.model.StreamEnvelope
import dev.asik.devicebridge.util.DiagnosticsBuilder
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.NetworkAddresses
import dev.asik.devicebridge.util.PermissionHelper
import dev.asik.devicebridge.util.TimeUtil
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
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
    private val authToken: String?,
    private val authEnabled: Boolean,
    private val networkMode: String,
    private val publicUrlProvider: () -> String,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val engineRef = AtomicReference<ApplicationEngine?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start() {
        val engine = embeddedServer(CIO, port = port, host = bindHost) {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets) {
                // Close half-open mobile sockets (Wi-Fi drop / network switch) so the
                // per-session collector coroutine is reclaimed instead of leaking.
                // Use the *Millis members (plain Long fields on WebSocketOptions) rather
                // than the pingPeriod/timeout java.time.Duration extension properties,
                // which would need extra imports and are Duration-flavor-sensitive.
                pingPeriodMillis = 20_000
                timeoutMillis = 15_000
                // Cap inbound frames so a hostile client on a public tunnel can't OOM us.
                maxFrameSize = 1L * 1024 * 1024
            }
            // Allow browser-origin clients (dashboards, Swagger try-it, agent web UIs).
            // Bearer auth is the real gate, so anyHost is acceptable here.
            install(CORS) {
                anyHost()
                allowHeaders { true }
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Options)
                allowNonSimpleContentTypes = true
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    // Record the real cause internally; return a generic body so we
                    // don't leak stack/internals to remote callers.
                    ErrorLog.error(
                        "http_500",
                        "${call.request.path()} — ${cause::class.java.simpleName}: ${cause.message}",
                    )
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorBody(
                            ApiError(
                                code = "internal_error",
                                message = "Internal server error",
                            ),
                        ),
                    )
                }
            }

            // Bearer auth when enabled (always for LAN / Tailscale / Cloudflare modes).
            intercept(ApplicationCallPipeline.Call) {
                val path = call.request.path()
                val method = call.request.httpMethod

                // CORS preflight must pass without checks so browsers can reach us.
                if (method == HttpMethod.Options) return@intercept

                // DNS-rebind defense: reject requests whose Host header isn't one of
                // ours. A malicious page rebinding its domain to 127.0.0.1 still sends
                // its own domain as Host, so an allowlist blocks it while every real
                // client (loopback / our IPs / configured public host) passes.
                if (!hostAllowed(call)) {
                    ErrorLog.warn("host_denied", "403 host=${call.request.header("Host")} $path")
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiErrorBody(ApiError("forbidden", "Host not allowed")),
                    )
                    finish()
                    return@intercept
                }

                // Fail closed: if auth is enabled but the token is blank/missing,
                // refuse everything rather than silently serving the API open.
                if (authEnabled && authToken.isNullOrBlank()) {
                    ErrorLog.error("auth_misconfig", "authEnabled but token blank — refusing all requests")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiErrorBody(ApiError("unauthorized", "Server auth misconfigured")),
                    )
                    finish()
                    return@intercept
                }

                // Mutating endpoints actuate hardware (camera/USB) — always require a
                // token, even in LOCAL mode where reads may be unauthenticated. This
                // stops a DNS-rebind page from driving the device without the secret.
                val isMutating = method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Delete
                if (isMutating && !authToken.isNullOrBlank() && !authorized(call)) {
                    ErrorLog.warn("auth_denied", "401 ${method.value} $path (mutating requires token)")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiErrorBody(ApiError("unauthorized", "This action requires a token")),
                    )
                    finish()
                    return@intercept
                }

                if (!authEnabled) return@intercept
                // Public, no-token surfaces: landing page, the API contract, and the
                // Swagger docs UI. These expose endpoint SHAPES, not data or secrets —
                // every actual API call Swagger makes still needs the token.
                if (path == "/" || path.isEmpty()) return@intercept
                if (path == "/docs" || path == "/v1/openapi.json") return@intercept
                if (!authorized(call)) {
                    ErrorLog.warn("auth_denied", "401 ${call.request.httpMethod.value} $path")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiErrorBody(
                            ApiError(
                                code = "unauthorized",
                                message = "Missing or invalid token. Use Authorization: Bearer <token> or ?token=",
                            ),
                        ),
                    )
                    finish()
                }
            }

            routing {
                get("/") {
                    // Public, unauthenticated: identify the service only. Network
                    // topology (LAN/Tailscale IPs, public URL, bind host) is available
                    // authenticated at /v1/config — never leak it to anonymous callers.
                    call.respondText(
                        buildString {
                            appendLine("Device Bridge $version")
                            if (authEnabled) {
                                appendLine("Auth required: Authorization: Bearer <token>  (or ?token=)")
                            }
                            appendLine("GET /v1/health   /v1/config   /v1/capabilities")
                        },
                        ContentType.Text.Plain,
                    )
                }

                get("/v1/health") {
                    val started = startedAtMsProvider()
                    val uptime = if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0
                    val diag = runCatching { DiagnosticsBuilder.build(this@BridgeServer.context) }.getOrNull()
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

                get("/v1/openapi.json") {
                    call.respondText(openApiJson(), ContentType.Application.Json)
                }

                get("/docs") {
                    call.respondText(swaggerHtml(), ContentType.Text.Html)
                }

                get("/v1/diagnostics") {
                    call.respond(DiagnosticsBuilder.build(this@BridgeServer.context))
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
                    val pub = publicUrlProvider().ifBlank { null }
                    val lan = NetworkAddresses.lanIps().map { "http://$it:$port" }
                    val ts = NetworkAddresses.tailscaleIps().map { "http://$it:$port" }
                    val all = buildList {
                        add("http://127.0.0.1:$port")
                        addAll(lan)
                        addAll(ts)
                        if (pub != null) add(0, pub)
                    }.distinct()
                    call.respond(
                        ConfigResponse(
                            bind = bindHost,
                            port = port,
                            base_url = "http://127.0.0.1:$port",
                            auth_enabled = authEnabled,
                            auth_required_hint = if (authEnabled) {
                                "Authorization: Bearer <token>  or  ?token="
                            } else {
                                null
                            },
                            version = version,
                            network_mode = networkMode,
                            public_url = pub,
                            lan_urls = lan,
                            tailscale_urls = ts,
                            all_urls = all,
                            cloudflared_command = "cloudflared tunnel --url http://127.0.0.1:$port",
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
                        if (!PermissionHelper.hasLocation(this@BridgeServer.context)) {
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

                get("/v1/audio") {
                    val a = hub.audio.value
                    if (a == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorBody(ApiError("no_data", "Audio reading not available yet. Enable it in Settings.")),
                        )
                    } else {
                        call.respond(a)
                    }
                }

                get("/v1/touch") {
                    val t = hub.touch.value
                    if (t == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorBody(ApiError("no_data", "No touch event captured yet. Touch screen to generate event.")),
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
                    if (!PermissionHelper.hasCamera(this@BridgeServer.context)) {
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
                    val collector = launch {
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
                    drainUntilClose(collector)
                }

                webSocket("/v1/stream/usb") {
                    hub.usb.value?.let {
                        sendJson(StreamEnvelope("usb", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    val collector = launch {
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
                    drainUntilClose(collector)
                }

                webSocket("/v1/stream") {
                    val initial = call.request.queryParameters["topics"]
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toMutableSet()
                        ?: mutableSetOf("location", "battery", "sensors", "network", "usb", "audio", "touch")

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
                    if ("audio" in topics) hub.audio.value?.let {
                        sendJson(StreamEnvelope("audio", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    if ("touch" in topics) hub.touch.value?.let {
                        sendJson(StreamEnvelope("touch", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
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
                                is HubEvent.Audio -> if ("audio" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "audio",
                                            TimeUtil.nowIso(),
                                            json.encodeToJsonElement(event.reading),
                                        ),
                                    )
                                }
                                is HubEvent.Touch -> if ("touch" in topics) {
                                    sendJson(
                                        StreamEnvelope(
                                            "touch",
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
                    val collector = launch {
                        hub.events.filter { it is HubEvent.Location }.collect { event ->
                            val reading = (event as HubEvent.Location).reading
                            sendJson(StreamEnvelope("location", TimeUtil.nowIso(), json.encodeToJsonElement(reading)))
                        }
                    }
                    drainUntilClose(collector)
                }

                webSocket("/v1/stream/sensors") {
                    val collector = launch {
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
                    drainUntilClose(collector)
                }

                webSocket("/v1/stream/touch") {
                    hub.touch.value?.let {
                        sendJson(StreamEnvelope("touch", TimeUtil.nowIso(), json.encodeToJsonElement(it)))
                    }
                    val collector = launch {
                        hub.events.filter { it is HubEvent.Touch }.collect { event ->
                            val reading = (event as HubEvent.Touch).reading
                            sendJson(StreamEnvelope("touch", TimeUtil.nowIso(), json.encodeToJsonElement(reading)))
                        }
                    }
                    drainUntilClose(collector)
                }
            }
        }

        engine.start(wait = false)
        engineRef.set(engine)

        // CIO binds on a background coroutine, so a BindException (port already in
        // use by Termux/cloudflared or a leftover engine) fires AFTER start() returns
        // and would otherwise leave us reporting "running" with nothing bound. Probe
        // the socket to confirm it actually accepts before we declare success.
        if (!awaitBound()) {
            runCatching { engineRef.getAndSet(null)?.stop(0, 0) }
            scope.cancel()
            throw java.io.IOException("Server did not bind on $bindHost:$port (port in use?)")
        }
    }

    /** Poll-connect until the listener accepts, or time out. */
    private suspend fun awaitBound(timeoutMs: Long = 3000, stepMs: Long = 100): Boolean {
        // Loopback reaches a 0.0.0.0 or 127.0.0.1 bind; for a specific-IP bind probe that IP.
        val probeHost = if (bindHost == "0.0.0.0" || bindHost.isBlank()) "127.0.0.1" else bindHost
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(probeHost, port), stepMs.toInt())
                    }
                    true
                }.getOrDefault(false)
            }
            if (ok) return true
            delay(stepMs)
        }
        return false
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
            audio = hub.audio.value,
            touch = hub.touch.value,
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

    private suspend fun DefaultWebSocketServerSession.sendJson(envelope: StreamEnvelope) {
        send(Frame.Text(json.encodeToString(envelope)))
    }

    /**
     * Block on the incoming frame channel until the client closes the socket, then
     * cancel the [collector] job that was pushing events to it. Single-topic stream
     * handlers previously collected hub.events directly with no incoming loop, so a
     * dead client on a sparse stream (no GPS fix / no serial traffic) was never
     * noticed and its collector coroutine lingered forever. Draining `incoming`
     * makes client-close end the handler and the finally reclaim the collector.
     */
    private suspend fun DefaultWebSocketServerSession.drainUntilClose(collector: kotlinx.coroutines.Job) {
        try {
            for (frame in incoming) {
                // Ignore payloads; single-topic streams have no client protocol.
                // The loop exists purely to observe close/failure of the socket.
            }
        } finally {
            collector.cancel()
        }
    }

    /**
     * DNS-rebind guard. Accepts requests whose Host is loopback, one of this device's
     * own IPs, or the configured public URL host. A rebinding attacker's page sends
     * its own domain as Host, so it fails this check. Missing Host (raw curl, some WS
     * clients) is allowed — a browser always sends one, so its absence isn't the
     * rebind threat model.
     */
    private fun hostAllowed(call: ApplicationCall): Boolean {
        val host = call.request.header("Host")?.substringBefore(":")?.trim()?.lowercase()
            ?: return true
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
        // Our own addresses (LAN + Tailscale) — the legitimate remote surfaces.
        if (NetworkAddresses.allIpv4().any { it.ip == host }) return true
        // Configured Cloudflare/public hostname.
        val pub = runCatching {
            publicUrlProvider().takeIf { it.isNotBlank() }
                ?.let { java.net.URI(it).host?.lowercase() }
        }.getOrNull()
        if (pub != null && host == pub) return true
        return false
    }

    // (method, path, summary, tag, mutating) — mutating routes need a token even in LOCAL.
    private data class Route(
        val method: String,
        val path: String,
        val summary: String,
        val tag: String,
        val mutating: Boolean = false,
    )

    private val apiRoutes = listOf(
        Route("get", "/v1/health", "Liveness, version, uptime", "meta"),
        Route("get", "/v1/openapi.json", "This OpenAPI descriptor", "meta"),
        Route("get", "/v1/diagnostics", "Collector health + degraded hints", "meta"),
        Route("get", "/v1/config", "Server mode, endpoints, auth requirement", "meta"),
        Route("get", "/v1/capabilities", "Static device capability inventory", "meta"),
        Route("get", "/v1/debug/log", "Recent server log entries (?n=)", "meta"),
        Route("post", "/v1/debug/log/clear", "Clear the server log", "meta", mutating = true),
        Route("get", "/v1/snapshot", "One-shot of all live signals", "telemetry"),
        Route("get", "/v1/location", "Last known location fix", "telemetry"),
        Route("get", "/v1/battery", "Battery level and status", "telemetry"),
        Route("get", "/v1/network", "Network transport + connectivity", "telemetry"),
        Route("get", "/v1/telephony", "Telephony/cell state", "telemetry"),
        Route("get", "/v1/audio", "Microphone RMS/peak dB", "telemetry"),
        Route("get", "/v1/touch", "Latest touch event", "telemetry"),
        Route("get", "/v1/sensors", "Snapshot of all sensor readings", "telemetry"),
        Route("get", "/v1/sensors/{type}", "One sensor by type name", "telemetry"),
        Route("get", "/v1/cameras", "Available cameras", "camera"),
        Route("post", "/v1/camera/{id}/capture", "Capture a still image", "camera", mutating = true),
        Route("get", "/v1/camera/last.jpg", "Last captured JPEG bytes", "camera"),
        Route("post", "/v1/control/start", "Start the bridge service", "control", mutating = true),
        Route("post", "/v1/control/stop", "Stop the bridge service", "control", mutating = true),
        Route("get", "/v1/usb", "USB host overview", "usb"),
        Route("post", "/v1/usb/rescan", "Rescan attached USB devices", "usb", mutating = true),
        Route("get", "/v1/usb/devices", "Attached USB devices", "usb"),
        Route("get", "/v1/usb/devices/{id}", "One USB device by id", "usb"),
        Route("get", "/v1/usb/storage", "USB mass-storage volumes", "usb"),
        Route("post", "/v1/usb/devices/{id}/permission", "Request USB permission", "usb", mutating = true),
        Route("post", "/v1/usb/devices/{id}/serial/open", "Open a serial connection", "usb", mutating = true),
        Route("post", "/v1/usb/devices/{id}/serial/close", "Close a serial connection", "usb", mutating = true),
        Route("post", "/v1/usb/devices/{id}/serial/write", "Write bytes to serial", "usb", mutating = true),
    )

    // WebSocket endpoints documented as x-websocket (OpenAPI has no native WS verb).
    private val wsRoutes = listOf(
        "/v1/stream" to "Multiplexed stream; ?topics=location,battery,sensors,network,usb,audio,touch",
        "/v1/stream/location" to "Location events only",
        "/v1/stream/sensors" to "Sensor events only",
        "/v1/stream/touch" to "Touch events only",
        "/v1/stream/usb" to "USB overview + attach/detach events",
        "/v1/usb/serial/{id}" to "Serial read stream for a device (open serial first)",
    )

    private fun openApiJson(): String {
        val doc = buildJsonObject {
            put("openapi", "3.0.3")
            put("info", buildJsonObject {
                put("title", "Device Bridge API")
                put("version", version)
                put("description", "Local-first device sensor/USB/camera bridge. Bearer token required off-device; mutating endpoints always require it.")
            })
            put("components", buildJsonObject {
                put("securitySchemes", buildJsonObject {
                    put("bearerAuth", buildJsonObject {
                        put("type", "http")
                        put("scheme", "bearer")
                    })
                })
            })
            put("paths", buildJsonObject {
                apiRoutes.forEach { r ->
                    // Extract {name} path params from the route so Swagger renders inputs.
                    val pathParams = Regex("\\{([^}]+)\\}").findAll(r.path).map { it.groupValues[1] }.toList()
                    val queryParams = knownQueryParams(r.path)
                    put(r.path, buildJsonObject {
                        put(r.method, buildJsonObject {
                            put("summary", r.summary)
                            put("tags", buildJsonArray { add(JsonPrimitive(r.tag)) })
                            if (pathParams.isNotEmpty() || queryParams.isNotEmpty()) {
                                put("parameters", buildJsonArray {
                                    pathParams.forEach { p ->
                                        add(buildJsonObject {
                                            put("name", p)
                                            put("in", "path")
                                            put("required", true)
                                            put("schema", buildJsonObject { put("type", "string") })
                                        })
                                    }
                                    queryParams.forEach { (name, desc) ->
                                        add(buildJsonObject {
                                            put("name", name)
                                            put("in", "query")
                                            put("required", false)
                                            put("description", desc)
                                            put("schema", buildJsonObject { put("type", "string") })
                                        })
                                    }
                                })
                            }
                            if (r.mutating || authEnabled) {
                                put("security", buildJsonArray {
                                    add(buildJsonObject {
                                        put("bearerAuth", buildJsonArray {})
                                    })
                                })
                            }
                            put("responses", buildJsonObject {
                                put("200", buildJsonObject { put("description", "OK") })
                                if (r.mutating || authEnabled) {
                                    put("401", buildJsonObject { put("description", "Missing or invalid token") })
                                }
                            })
                        })
                    })
                }
            })
            put("x-websocket", buildJsonObject {
                wsRoutes.forEach { (path, desc) -> put(path, JsonPrimitive(desc)) }
            })
        }
        return json.encodeToString(JsonObject.serializer(), doc)
    }

    /** Documented query params per route, so the spec is buildable, not just listable. */
    private fun knownQueryParams(path: String): List<Pair<String, String>> = when (path) {
        "/v1/debug/log" -> listOf("n" to "Number of recent entries to return (default 50)")
        else -> emptyList()
    }

    /**
     * Swagger UI shell. Loads the viewer assets from the public CDN (keeps the APK
     * lean) and points them at our own /v1/openapi.json. The viewing device needs
     * internet for the CDN; the spec + API themselves work fully offline. The
     * "Authorize" button lets a user paste the bearer token to try endpoints live.
     */
    private fun swaggerHtml(): String {
        // Swagger UI renders only REST paths — it ignores WebSockets entirely. Build a
        // custom section from wsRoutes so the streaming surface (incl. audio) is visible.
        val wsRows = wsRoutes.joinToString("\n") { (path, desc) ->
            """<tr><td><code>ws://&lt;host&gt;:$port$path</code></td><td>$desc</td></tr>"""
        }
        val authHint = if (authEnabled) {
            "All streams require the token: append <code>?token=&lt;token&gt;</code> to the URL (WebSockets can't send an Authorization header from a browser)."
        } else {
            "Auth is off in this mode — no token needed."
        }
        // NOTE: use \$ for any literal dollar; ${'$'}{...} stays as JS at runtime.
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1"/>
          <title>Device Bridge API</title>
          <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css"/>
          <style>
            .ws { font-family: sans-serif; max-width: 1200px; margin: 24px auto; padding: 0 20px; color: #3b4151; }
            .ws h2 { font-size: 22px; }
            .ws table { border-collapse: collapse; width: 100%; margin: 12px 0; }
            .ws td { border: 1px solid #e3e3e3; padding: 8px 10px; font-size: 14px; vertical-align: top; }
            .ws code { background: #f0f0f0; padding: 2px 5px; border-radius: 3px; }
            .ws pre { background: #1b1b2f; color: #e6e6f0; padding: 14px; border-radius: 6px; overflow-x: auto; }
          </style>
        </head>
        <body>
          <div id="swagger-ui"></div>
          <div class="ws">
            <h2>Streaming API (WebSocket)</h2>
            <p>Live push streams. Not shown above — Swagger UI only renders REST. $authHint</p>
            <table>
              <tr><td><b>Endpoint</b></td><td><b>Streams</b></td></tr>
              $wsRows
            </table>
            <p>Each message is a JSON envelope: <code>{ "topic": "...", "ts": "ISO-8601", "data": { ... } }</code>.
               On <code>/v1/stream</code> you can also send <code>{"op":"subscribe","topics":["audio"]}</code> /
               <code>{"op":"unsubscribe",...}</code> / <code>{"op":"ping"}</code>.</p>
            <p><b>Audio note:</b> the <code>audio</code> topic streams loudness (<code>rms_db</code>, <code>peak_db</code>) about 7×/sec — NOT raw PCM samples.</p>
            <pre># wscat example (all topics)
wscat -c "ws://&lt;host&gt;:$port/v1/stream?token=&lt;token&gt;"

# JavaScript (audio levels only)
const ws = new WebSocket("ws://&lt;host&gt;:$port/v1/stream?topics=audio&amp;token=&lt;token&gt;");
ws.onmessage = (e) => console.log(JSON.parse(e.data));</pre>
          </div>
          <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
          <script>
            window.onload = function () {
              window.ui = SwaggerUIBundle({
                url: "/v1/openapi.json",
                dom_id: "#swagger-ui",
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis],
              });
            };
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val expected = authToken ?: return true
        val header = call.request.header("Authorization")
        if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            if (constantTimeEquals(header.substring(7).trim(), expected)) return true
        }
        // WebSocket clients often pass the token as a query param.
        val q = call.request.queryParameters["token"]
        if (q != null && constantTimeEquals(q, expected)) return true
        return false
    }

    /** Length-independent, byte-wise constant-time comparison to avoid a timing side channel. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
}
