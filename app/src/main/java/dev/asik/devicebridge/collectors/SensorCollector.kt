package dev.asik.devicebridge.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.SensorReading

class SensorCollector(
    context: Context,
    private val hub: StreamHub,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var running = false

    /** Default set: motion + environment sensors commonly useful for automation */
    private val preferredTypes = intArrayOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
    )

    fun start(delayUs: Int = SensorManager.SENSOR_DELAY_UI) {
        if (running) return
        running = true
        val registered = mutableSetOf<Int>()
        for (type in preferredTypes) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            if (registered.add(sensor.type)) {
                sensorManager.registerListener(this, sensor, delayUs)
            }
        }
        // Also register any remaining sensors at a slower rate if few preferred matched
        if (registered.size < 3) {
            for (sensor in sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                if (registered.add(sensor.type)) {
                    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val typeName = CapabilityScanner.sensorTypeName(event.sensor.type, event.sensor.stringType)
        hub.publishSensor(
            typeName,
            SensorReading(
                values = event.values.toList(),
                accuracy = event.accuracy,
                timestamp_ns = event.timestamp,
                type = event.sensor.type,
                type_name = typeName,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
