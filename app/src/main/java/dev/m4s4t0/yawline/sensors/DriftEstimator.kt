package dev.m4s4t0.yawline.sensors

import kotlin.math.abs
import kotlin.math.min

/**
 * Works out the drift (slip) angle: the angle between where the car points and where it is going.
 *
 *  - Where it points (heading) comes from integrating the gyro's yaw rate.
 *  - Where it goes (course) comes from GPS, which measures the velocity direction directly. Between GPS
 *    fixes the course is carried forward with the lateral acceleration (a turn bends the path at a / v).
 *  - The gyro heading drifts, so whenever the car is plainly driving straight (little yaw, little side
 *    load, decent speed) it is pulled back onto the course: a car going straight is not sliding.
 *
 * Angles are compass degrees (clockwise from north); yaw rate and lateral acceleration are positive to
 * the left, like the vehicle-dynamics convention. A positive slip means the nose points right of the path.
 */
class DriftEstimator {
    var heading = Double.NaN; private set
    var course = Double.NaN; private set
    var speed = 0.0; private set
    private var calmMs = 0.0
    private var gyroBias = 0.0
    private var stillMs = 0.0

    /** A gyro/accelerometer sample: [dt] seconds since the last one. */
    fun onImu(dt: Double, yawRateCcwDps: Double, latAccLeft: Double) {
        if (dt <= 0 || dt > 0.5) return
        if (speed < 0.3) {
            // Standing still: whatever the gyro says is its own bias.
            stillMs += dt * 1000
            if (stillMs > 500) gyroBias += (yawRateCcwDps - gyroBias) * min(1.0, dt * 0.5)
            return
        }
        stillMs = 0.0
        val yaw = yawRateCcwDps - gyroBias
        if (heading.isNaN()) return
        heading = wrap(heading - yaw * dt)
        if (speed > 3) course = wrap(course - Math.toDegrees(latAccLeft / speed) * dt)
        calmMs = if (abs(yaw) < 3.0 && abs(latAccLeft) < 1.0) calmMs + dt * 1000 else 0.0
    }

    /** A GPS fix. [courseDeg] is null when the receiver gave no bearing (too slow to tell). */
    fun onGps(courseDeg: Double?, speedMps: Double) {
        speed = speedMps
        if (courseDeg == null || speedMps < 4) return
        if (course.isNaN() || heading.isNaN()) {
            course = courseDeg
            heading = courseDeg
            return
        }
        course = blend(course, courseDeg, 0.8)
        if (calmMs > 1500 && speedMps > 8) heading = blend(heading, course, 0.35)
    }

    /** Signed slip angle in degrees, 0 when too slow to mean anything. */
    val slip: Double
        get() = if (speed < 5 || heading.isNaN() || course.isNaN()) 0.0 else signed(heading - course)

    val bias get() = gyroBias

    companion object {
        fun wrap(d: Double) = ((d % 360) + 360) % 360
        fun signed(d: Double): Double { val w = wrap(d); return if (w > 180) w - 360 else w }
        /** Moves angle [a] a fraction [f] of the short way toward [b]. */
        fun blend(a: Double, b: Double, f: Double) = wrap(a + signed(b - a) * f)
    }
}
