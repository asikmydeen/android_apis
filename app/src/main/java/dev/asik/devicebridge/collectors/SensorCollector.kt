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
    private var initialStepCount: Float? = null

    /** Default set: motion + environment sensors commonly useful for automation */
    private val preferredTypes = intArrayOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
    )

    fun start(delayUs: Int = SensorManager.SENSOR_DELAY_UI) {
        if (running) return
        running = true
        initialStepCount = null
        val registered = mutableSetOf<Int>()

        // Always register ALL available light sensors (important for foldables with cover + inner light sensors)
        val lightSensors = sensorManager.getSensorList(Sensor.TYPE_LIGHT)
        for (light in lightSensors) {
            sensorManager.registerListener(this, light, delayUs)
        }

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
        initialStepCount = null
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val baseTypeName = CapabilityScanner.sensorTypeName(event.sensor.type, event.sensor.stringType)
        
        val valuesList = if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
            val rawBootSteps = event.values[0]
            if (initialStepCount == null) {
                initialStepCount = rawBootSteps
            }
            val sessionSteps = (rawBootSteps - (initialStepCount ?: rawBootSteps)).coerceAtLeast(0f)
            // Return: [totalStepsSinceBoot, sessionStepsSinceStart]
            listOf(rawBootSteps, sessionSteps)
        } else {
            event.values.toList()
        }

        // Handle multiple sensors of the same type (e.g. multi-screen light sensors)
        val typeName = if (event.sensor.name.isNotBlank() && event.sensor.type == Sensor.TYPE_LIGHT) {
            "$baseTypeName.${event.sensor.name.lowercase().replace(' ', '_')}"
        } else {
            baseTypeName
        }

        hub.publishSensor(
            typeName,
            SensorReading(
                values = valuesList,
                accuracy = event.accuracy,
                timestamp_ns = event.timestamp,
                type = event.sensor.type,
                type_name = typeName,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
