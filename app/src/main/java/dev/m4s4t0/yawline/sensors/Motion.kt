package dev.m4s4t0.yawline.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.m4s4t0.yawline.Prefs
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's gyro and accelerometers turned into car terms: yaw rate, lateral and longitudinal G.
 *
 * "Up" comes from the gravity sensor, so yaw rate is right however the phone is mounted. "Forward" is
 * guessed from the mount: a phone standing on the dash faces the driver, so forward is out of its back;
 * a phone lying flat points its top forward. [Prefs.mountYawOffset] (from the forward calibration)
 * corrects a mount that is turned.
 */
class Motion(private val sm: SensorManager, private val onSample: (dt: Double, yawCcwDps: Double, latG: Double, lonG: Double) -> Unit) : SensorEventListener {
    private val gravity = doubleArrayOf(0.0, 0.0, 9.81)
    private val lin = DoubleArray(3)
    private var lastGyroNs = 0L
    private var latF = 0.0
    private var lonF = 0.0

    // Forward calibration: horizontal acceleration directions seen while the car speeds up in a line.
    private var calibrating = false
    private var calX = 0.0
    private var calY = 0.0
    private var calN = 0

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> for (i in 0..2) gravity[i] = e.values[i].toDouble()
            Sensor.TYPE_LINEAR_ACCELERATION -> for (i in 0..2) lin[i] = e.values[i].toDouble()
            Sensor.TYPE_GYROSCOPE -> {
                val dt = if (lastGyroNs == 0L) 0.0 else (e.timestamp - lastGyroNs) / 1e9
                lastGyroNs = e.timestamp
                val up = norm(gravity)
                val gyro = doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                val yaw = Math.toDegrees(dot(gyro, up))
                val f = forward(up)
                val left = cross(up, f)
                val lon = dot(lin, f)
                val lat = dot(lin, left)
                latF += (lat - latF) * 0.3
                lonF += (lon - lonF) * 0.3
                if (calibrating) {
                    // Horizontal acceleration in the phone frame, kept as an angle around "up" from f.
                    val h = sub(lin, scale(up, dot(lin, up)))
                    if (sqrt(dot(h, h)) > 1.5 && abs(yaw) < 5) {
                        calX += dot(h, f); calY += dot(h, left); calN++
                    }
                }
                onSample(dt, yaw, latF / 9.81, lonF / 9.81)
            }
        }
    }

    /** Begins collecting for the forward calibration; call while accelerating hard in a straight line. */
    fun beginCalibration() { calibrating = true; calX = 0.0; calY = 0.0; calN = 0 }

    /** Ends it; returns the new offset in degrees or null if there was not enough clean acceleration. */
    fun endCalibration(): Float? {
        calibrating = false
        if (calN < 25) return null
        // The average acceleration direction is "forward"; how far left of our guess it sits is the error.
        val errLeft = Math.toDegrees(atan2(calY, calX))
        return DriftEstimator.signed(Prefs.mountYawOffset + errLeft).toFloat()
    }

    private fun forward(up: DoubleArray): DoubleArray {
        val guess = if (abs(up[2]) > 0.8) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(0.0, 0.0, -1.0)
        val f = norm(sub(guess, scale(up, dot(guess, up))))
        // Turn by the calibration offset around "up" (positive turns toward the left).
        val a = Math.toRadians(Prefs.mountYawOffset.toDouble())
        val l = cross(up, f)
        return norm(doubleArrayOf(f[0] * cos(a) + l[0] * sin(a), f[1] * cos(a) + l[1] * sin(a), f[2] * cos(a) + l[2] * sin(a)))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scale(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: DoubleArray): DoubleArray {
        val n = sqrt(dot(a, a)).takeIf { it > 1e-6 } ?: return doubleArrayOf(0.0, 0.0, 1.0)
        return scale(a, 1 / n)
    }
}
