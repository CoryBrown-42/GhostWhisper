package com.fieldkit.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Wraps the phone's magnetic field sensor. Delivers the field magnitude in
 * microtesla (orientation-independent in principle) on the main thread.
 */
class MagnetometerSource(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val available: Boolean get() = sensor != null
    val sensorName: String get() = sensor?.name ?: "none"

    private var onSample: ((timeMs: Long, magnitudeUt: Float) -> Unit)? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            onSample?.invoke(System.currentTimeMillis(), sqrt(x * x + y * y + z * z))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(callback: (timeMs: Long, magnitudeUt: Float) -> Unit): Boolean {
        val s = sensor ?: return false
        onSample = callback
        return manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager.unregisterListener(listener)
        onSample = null
    }
}
