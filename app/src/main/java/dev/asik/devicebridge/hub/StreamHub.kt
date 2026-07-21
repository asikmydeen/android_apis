package dev.asik.devicebridge.hub

import dev.asik.devicebridge.collectors.UsbCollector
import dev.asik.devicebridge.model.AudioReading
import dev.asik.devicebridge.model.BatteryReading
import dev.asik.devicebridge.model.CameraMeta
import dev.asik.devicebridge.model.LocationReading
import dev.asik.devicebridge.model.NetworkReading
import dev.asik.devicebridge.model.SensorReading
import dev.asik.devicebridge.model.TelephonyReading
import dev.asik.devicebridge.model.TouchReading
import dev.asik.devicebridge.model.UsbEvent
import dev.asik.devicebridge.model.UsbOverview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory last-value cache + broadcast bus for REST snapshots and WebSockets.
 */
class StreamHub {
    private val _location = MutableStateFlow<LocationReading?>(null)
    val location: StateFlow<LocationReading?> = _location.asStateFlow()

    private val _battery = MutableStateFlow<BatteryReading?>(null)
    val battery: StateFlow<BatteryReading?> = _battery.asStateFlow()

    private val _network = MutableStateFlow<NetworkReading?>(null)
    val network: StateFlow<NetworkReading?> = _network.asStateFlow()

    private val _telephony = MutableStateFlow<TelephonyReading?>(null)
    val telephony: StateFlow<TelephonyReading?> = _telephony.asStateFlow()

    private val _audio = MutableStateFlow<AudioReading?>(null)
    val audio: StateFlow<AudioReading?> = _audio.asStateFlow()

    private val _touch = MutableStateFlow<TouchReading?>(null)
    val touch: StateFlow<TouchReading?> = _touch.asStateFlow()

    private val sensorsMap = ConcurrentHashMap<String, SensorReading>()
    private val _sensors = MutableStateFlow<Map<String, SensorReading>>(emptyMap())
    val sensors: StateFlow<Map<String, SensorReading>> = _sensors.asStateFlow()

    private val _cameraMeta = MutableStateFlow(CameraMeta())
    val cameraMeta: StateFlow<CameraMeta> = _cameraMeta.asStateFlow()

    private val _usb = MutableStateFlow<UsbOverview?>(null)
    val usb: StateFlow<UsbOverview?> = _usb.asStateFlow()

    private val _events = MutableSharedFlow<HubEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    fun publishLocation(value: LocationReading) {
        _location.value = value
        _events.tryEmit(HubEvent.Location(value))
    }

    fun publishBattery(value: BatteryReading) {
        _battery.value = value
        _events.tryEmit(HubEvent.Battery(value))
    }

    fun publishNetwork(value: NetworkReading) {
        _network.value = value
        _events.tryEmit(HubEvent.Network(value))
    }

    fun publishTelephony(value: TelephonyReading) {
        _telephony.value = value
        _events.tryEmit(HubEvent.Telephony(value))
    }

    fun publishAudio(value: AudioReading) {
        _audio.value = value
        _events.tryEmit(HubEvent.Audio(value))
    }

    fun publishTouch(value: TouchReading) {
        _touch.value = value
        _events.tryEmit(HubEvent.Touch(value))
    }

    fun publishSensor(typeName: String, value: SensorReading) {
        sensorsMap[typeName] = value
        _sensors.value = sensorsMap.toMap()
        _events.tryEmit(HubEvent.Sensor(typeName, value))
    }

    fun publishCameraMeta(value: CameraMeta) {
        _cameraMeta.value = value
        _events.tryEmit(HubEvent.Camera(value))
    }

    fun publishUsbOverview(value: UsbOverview) {
        _usb.value = value
        _events.tryEmit(HubEvent.Usb(value))
    }

    fun publishUsbEvent(value: UsbEvent) {
        _events.tryEmit(HubEvent.UsbEventMsg(value))
    }

    fun publishUsbSerialChunk(value: UsbCollector.SerialChunk) {
        _events.tryEmit(HubEvent.UsbSerial(value))
    }

    fun sensorSnapshot(): Map<String, SensorReading> = sensorsMap.toMap()
}

sealed class HubEvent {
    data class Location(val reading: LocationReading) : HubEvent()
    data class Battery(val reading: BatteryReading) : HubEvent()
    data class Network(val reading: NetworkReading) : HubEvent()
    data class Telephony(val reading: TelephonyReading) : HubEvent()
    data class Audio(val reading: AudioReading) : HubEvent()
    data class Touch(val reading: TouchReading) : HubEvent()
    data class Sensor(val typeName: String, val reading: SensorReading) : HubEvent()
    data class Camera(val meta: CameraMeta) : HubEvent()
    data class Usb(val overview: UsbOverview) : HubEvent()
    data class UsbEventMsg(val event: UsbEvent) : HubEvent()
    data class UsbSerial(val chunk: UsbCollector.SerialChunk) : HubEvent()
}
