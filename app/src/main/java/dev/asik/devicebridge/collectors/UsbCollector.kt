package dev.asik.devicebridge.collectors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Base64
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.UsbDeviceInfo
import dev.asik.devicebridge.model.UsbEvent
import dev.asik.devicebridge.model.UsbInterfaceInfo
import dev.asik.devicebridge.model.UsbOverview
import dev.asik.devicebridge.model.UsbSerialOpenResponse
import dev.asik.devicebridge.model.UsbStorageVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * USB host enumeration, mass-storage volume discovery, permission, and simple bulk serial I/O.
 *
 * Files from USB sticks: prefer Android-mounted paths under /storage (see storage_volumes),
 * bind-mounted into proot. Live gadget data: open serial and stream via [serialLines] / WebSocket.
 */
class UsbCollector(
    private val context: Context,
    private val hub: StreamHub,
    private val scope: CoroutineScope,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var receiver: BroadcastReceiver? = null

    private val serialSessions = ConcurrentHashMap<String, SerialSession>()

    private val _serialLines = MutableSharedFlow<SerialChunk>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val serialLines: SharedFlow<SerialChunk> = _serialLines.asSharedFlow()

    fun start() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = deviceFromIntent(intent)
                        val info = device?.let { toInfo(it) }
                        hub.publishUsbEvent(UsbEvent(action = "attached", device = info))
                        hub.publishUsbOverview(overview())
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = deviceFromIntent(intent)
                        val id = device?.deviceId?.toString()
                        if (id != null) closeSerial(id)
                        val info = device?.let { toInfo(it) }
                        hub.publishUsbEvent(UsbEvent(action = "detached", device = info))
                        hub.publishUsbOverview(overview())
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = deviceFromIntent(intent)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val info = device?.let { toInfo(it) }
                        hub.publishUsbEvent(
                            UsbEvent(
                                action = if (granted) "permission_granted" else "permission_denied",
                                device = info,
                                message = if (granted) "USB permission granted" else "USB permission denied",
                            ),
                        )
                        hub.publishUsbOverview(overview())
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        hub.publishUsbOverview(overview())
    }

    fun stop() {
        serialSessions.keys.toList().forEach { closeSerial(it) }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun overview(): UsbOverview {
        val devices = usbManager.deviceList.values.map { toInfo(it) }
        val volumes = listStorageVolumes()
        val notes = buildList {
            add("USB mass-storage files appear under Android /storage paths when the OS mounts them.")
            add("Bind those paths into proot Ubuntu (see docs/USB_LINUX.md) so Grok can use normal file I/O.")
            add("Non-storage gadgets: grant permission, then POST /v1/usb/devices/{id}/serial/open and WS stream.")
            if (!context.packageManager.hasSystemFeature("android.hardware.usb.host")) {
                add("This device may not advertise USB host feature; OTG support still varies by OEM.")
            }
        }
        return UsbOverview(
            host_supported = context.packageManager.hasSystemFeature("android.hardware.usb.host"),
            device_count = devices.size,
            devices = devices,
            storage_volumes = volumes,
            notes = notes,
        )
    }

    fun findDevice(deviceId: String): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.deviceId.toString() == deviceId }
    }

    fun requestPermission(deviceId: String): Boolean {
        val device = findDevice(deviceId) ?: return false
        if (usbManager.hasPermission(device)) return true
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, pi)
        return false
    }

    fun hasPermission(deviceId: String): Boolean {
        val device = findDevice(deviceId) ?: return false
        return usbManager.hasPermission(device)
    }

    fun openSerial(deviceId: String, baudRate: Int = 9600): UsbSerialOpenResponse {
        val device = findDevice(deviceId)
            ?: return UsbSerialOpenResponse(false, deviceId, baudRate, "device not found", "")
        if (!usbManager.hasPermission(device)) {
            requestPermission(deviceId)
            return UsbSerialOpenResponse(
                false,
                deviceId,
                baudRate,
                "USB permission required — accept the system dialog, then retry",
                "/v1/usb/serial/$deviceId",
            )
        }
        closeSerial(deviceId)
        val session = SerialSession(device, baudRate)
        try {
            session.open()
        } catch (e: Exception) {
            return UsbSerialOpenResponse(
                false,
                deviceId,
                baudRate,
                e.message ?: "failed to open serial",
                "/v1/usb/serial/$deviceId",
            )
        }
        serialSessions[deviceId] = session
        session.startReadLoop(scope) { chunk ->
            _serialLines.tryEmit(chunk)
            hub.publishUsbSerialChunk(chunk)
        }
        return UsbSerialOpenResponse(
            ok = true,
            device_id = deviceId,
            baud_rate = baudRate,
            message = "serial open; stream at WS /v1/usb/serial/$deviceId or topic usb_serial",
            stream_ws = "/v1/usb/serial/$deviceId",
        )
    }

    fun closeSerial(deviceId: String) {
        serialSessions.remove(deviceId)?.close()
    }

    fun writeSerial(deviceId: String, data: ByteArray): Int {
        val session = serialSessions[deviceId] ?: error("serial not open for $deviceId")
        return session.write(data)
    }

    fun isSerialOpen(deviceId: String): Boolean = serialSessions.containsKey(deviceId)

    private fun listStorageVolumes(): List<UsbStorageVolume> {
        val result = mutableListOf<UsbStorageVolume>()
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sm != null) {
                for (vol in sm.storageVolumes) {
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vol.directory?.absolutePath
                    } else {
                        @Suppress("DEPRECATION")
                        vol.javaClass.methods
                            .firstOrNull { it.name == "getPathFile" }
                            ?.invoke(vol) as? File
                            ?: vol.javaClass.methods
                                .firstOrNull { it.name == "getPath" }
                                ?.invoke(vol) as? String
                                ?.let { File(it) }
                    }?.absolutePath

                    val removable = vol.isRemovable
                    val primary = vol.isPrimary
                    // Prefer non-primary removable volumes as likely USB/OTG
                    val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) vol.getDescription(context) else null
                    val state = vol.state
                    val id = path ?: label ?: "vol-${result.size}"
                    val prootHint = path?.let { "Bind in Termux proot: --bind $it:/mnt/usb" }
                    result += UsbStorageVolume(
                        id = id,
                        label = label,
                        path = path,
                        state = state,
                        is_removable = removable,
                        is_primary = primary,
                        description = buildString {
                            append(if (removable) "removable" else "fixed")
                            if (primary) append(", primary")
                        },
                        proot_hint = prootHint,
                    )
                }
            }
        } catch (_: Exception) {
            // fall through to path scan
        }

        // Heuristic scan of /storage for UUID-like OTG mounts
        runCatching {
            val storage = File("/storage")
            storage.listFiles()?.forEach { f ->
                if (!f.isDirectory) return@forEach
                val name = f.name
                if (name == "emulated" || name == "self" || name.startsWith(".")) return@forEach
                if (result.any { it.path == f.absolutePath }) return@forEach
                result += UsbStorageVolume(
                    id = f.absolutePath,
                    label = name,
                    path = f.absolutePath,
                    state = if (f.canRead()) Environment.MEDIA_MOUNTED else "unknown",
                    is_removable = true,
                    is_primary = false,
                    description = "discovered under /storage",
                    proot_hint = "Bind in Termux proot: --bind ${f.absolutePath}:/mnt/usb",
                )
            }
        }
        return result
    }

    private fun toInfo(device: UsbDevice): UsbDeviceInfo {
        val ifaces = (0 until device.interfaceCount).map { i ->
            val iface = device.getInterface(i)
            UsbInterfaceInfo(
                id = iface.id,
                iface_class = iface.interfaceClass,
                iface_subclass = iface.interfaceSubclass,
                iface_protocol = iface.interfaceProtocol,
                endpoint_count = iface.endpointCount,
                name = iface.name,
            )
        }
        val mass = ifaces.any { it.iface_class == UsbConstants.USB_CLASS_MASS_STORAGE } ||
            device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        val serial = ifaces.any {
            it.iface_class == UsbConstants.USB_CLASS_CDC_DATA ||
                it.iface_class == UsbConstants.USB_CLASS_COMM ||
                it.iface_class == UsbConstants.USB_CLASS_VENDOR_SPEC
        }
        val hasPerm = usbManager.hasPermission(device)
        val manufacturer = if (hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { device.manufacturerName }.getOrNull()
        } else null
        val product = if (hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { device.productName }.getOrNull()
        } else null
        val serialNum = if (hasPerm) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.serialNumber else null
            }.getOrNull()
        } else null

        return UsbDeviceInfo(
            device_id = device.deviceId.toString(),
            device_name = device.deviceName,
            vendor_id = device.vendorId,
            product_id = device.productId,
            vendor_id_hex = "0x%04x".format(device.vendorId),
            product_id_hex = "0x%04x".format(device.productId),
            device_class = device.deviceClass,
            device_subclass = device.deviceSubclass,
            device_protocol = device.deviceProtocol,
            manufacturer = manufacturer,
            product = product,
            serial = serialNum,
            has_permission = hasPerm,
            interface_count = device.interfaceCount,
            interfaces = ifaces,
            likely_mass_storage = mass,
            likely_serial = serial || !mass,
        )
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    data class SerialChunk(
        val deviceId: String,
        val base64: String,
        val text: String?,
        val bytes: Int,
    )

    private inner class SerialSession(
        private val device: UsbDevice,
        private val baudRate: Int,
    ) {
        private var connection: UsbDeviceConnection? = null
        private var usbInterface: UsbInterface? = null
        private var epIn: UsbEndpoint? = null
        private var epOut: UsbEndpoint? = null
        private var readJob: Job? = null

        fun open() {
            var chosenIface: UsbInterface? = null
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var localIn: UsbEndpoint? = null
                var localOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) localIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) localOut = ep
                }
                if (localIn != null) {
                    chosenIface = iface
                    inEp = localIn
                    outEp = localOut
                    // Prefer CDC data class
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                        iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
                    ) {
                        break
                    }
                }
            }

            if (chosenIface == null || inEp == null) {
                error("No bulk IN endpoint found — device may need a special driver (FTDI/CP210x). Mass-storage sticks use file mounts instead.")
            }

            val conn = usbManager.openDevice(device)
                ?: error("openDevice failed")
            if (!conn.claimInterface(chosenIface, true)) {
                conn.close()
                error("claimInterface failed")
            }

            // Best-effort CDC line coding (ignored by many chips)
            runCatching {
                val coding = ByteArray(7)
                coding[0] = (baudRate and 0xff).toByte()
                coding[1] = ((baudRate shr 8) and 0xff).toByte()
                coding[2] = ((baudRate shr 16) and 0xff).toByte()
                coding[3] = ((baudRate shr 24) and 0xff).toByte()
                coding[4] = 0 // 1 stop bit
                coding[5] = 0 // no parity
                coding[6] = 8 // 8 data bits
                conn.controlTransfer(0x21, 0x20, 0, 0, coding, coding.size, 1000)
                conn.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000) // control line state
            }

            connection = conn
            usbInterface = chosenIface
            epIn = inEp
            epOut = outEp
        }

        fun startReadLoop(scope: CoroutineScope, onChunk: (SerialChunk) -> Unit) {
            val conn = connection ?: return
            val ep = epIn ?: return
            readJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
                while (isActive) {
                    val n = conn.bulkTransfer(ep, buf, buf.size, 500)
                    if (n == null || n <= 0) continue
                    val data = buf.copyOf(n)
                    val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull()
                        ?.takeIf { s -> s.all { ch -> ch.code == 9 || ch.code == 10 || ch.code == 13 || (ch.code in 32..126) } }
                    onChunk(
                        SerialChunk(
                            deviceId = device.deviceId.toString(),
                            base64 = Base64.encodeToString(data, Base64.NO_WRAP),
                            text = text,
                            bytes = n,
                        ),
                    )
                }
            }
        }

        fun write(data: ByteArray): Int {
            val conn = connection ?: error("not open")
            val ep = epOut ?: error("no bulk OUT endpoint")
            return conn.bulkTransfer(ep, data, data.size, 2000)
        }

        fun close() {
            readJob?.cancel()
            readJob = null
            val iface = usbInterface
            val conn = connection
            if (conn != null && iface != null) {
                runCatching { conn.releaseInterface(iface) }
            }
            runCatching { conn?.close() }
            connection = null
            usbInterface = null
            epIn = null
            epOut = null
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.asik.devicebridge.USB_PERMISSION"
    }
}
