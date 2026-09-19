package com.example.alibi.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log

/**
 * Controller for proximity sensing during active phone calls.
 * Acquires [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] when object/ear is near,
 * and releases it when far or when [stop] is called.
 */
class ProximityController(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    @Volatile private var isRegistered = false

    private val wakeLock: PowerManager.WakeLock? by lazy {
        try {
            if (powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "Alibi:CallProximity"
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create proximity wake lock", e)
            null
        }
    }

    private val sensor: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensorObj = sensor ?: return
            val near = event.values[0] < sensorObj.maximumRange
            Log.d(TAG, "onSensorChanged: near=$near, value=${event.values[0]}, maxRange=${sensorObj.maximumRange}")
            
            wakeLock?.let { lock ->
                if (near && !lock.isHeld) {
                    try {
                        @Suppress("DEPRECATION")
                        lock.acquire(30 * 60 * 1000L /* 30 min max safety */)
                        Log.d(TAG, "Proximity WakeLock acquired")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring proximity wake lock", e)
                    }
                } else if (!near && lock.isHeld) {
                    try {
                        lock.release()
                        Log.d(TAG, "Proximity WakeLock released")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing proximity wake lock", e)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Synchronized
    fun start() {
        if (isRegistered) return
        val s = sensor
        if (s != null && sensorManager != null) {
            val registered = sensorManager.registerListener(
                listener,
                s,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isRegistered = registered
            Log.d(TAG, "Proximity listener registered: $registered")
        } else {
            Log.w(TAG, "Proximity sensor not available on this device")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRegistered) return
        sensorManager?.unregisterListener(listener)
        isRegistered = false
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                try {
                    lock.release()
                    Log.d(TAG, "Proximity WakeLock released on stop")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing proximity wake lock on stop", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProximityController"
    }
}
