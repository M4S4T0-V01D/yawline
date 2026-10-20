package dev.m4s4t0.yawline.sensors

import dev.m4s4t0.yawline.YawApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pretend rally car lapping a gravel loop near Jyväskylä, so every screen can be tried without a car:
 * the simulated adapter answers OBD requests from it, and the demo GPS/IMU source reads its position,
 * yaw and slides. The physics is just enough to look right: corner speed from grip, a gearbox for RPM,
 * a turbo, warming coolant and a tail that steps out in proportion to cornering load.
 */
object SimDrive {
    data class State(
        val tMs: Long = 0,
        val x: Double = 0.0, val y: Double = 0.0,   // metres east / north of the origin
        val speed: Double = 0.0,                     // m/s
        val course: Double = 0.0,                    // direction of travel, degrees clockwise from north
        val heading: Double = 0.0,                   // direction the nose points
        val yawRateCcw: Double = 0.0,                // deg/s, positive turning left
        val latG: Double = 0.0,                      // positive to the left
        val lonG: Double = 0.0,
        val rpm: Double = 900.0,
        val throttle: Double = 0.0,
        val mapKpa: Double = 35.0,
        val coolant: Double = 40.0,
        val oil: Double = 35.0,
        val iat: Double = 22.0,
        val gear: Int = 1,
    ) {
        val lat get() = ORIGIN_LAT + y / 111_320.0
        val lon get() = ORIGIN_LON + x / (111_320.0 * cos(ORIGIN_LAT * PI / 180))
    }

    const val ORIGIN_LAT = 62.2426
    const val ORIGIN_LON = 25.7473

    /** The loop as (length m, curvature 1/m, positive = left) pieces. */
    private val COURSE = listOf(
        180.0 to 0.0, 60.0 to 1 / 45.0, 120.0 to 0.0, 50.0 to -1 / 30.0, 40.0 to 0.0, 70.0 to 1 / 60.0,
        200.0 to 0.0, 35.0 to -1 / 18.0, 90.0 to 0.0, 80.0 to -1 / 90.0, 60.0 to 1 / 25.0, 150.0 to 0.0,
        90.0 to 1 / 35.0, 100.0 to 0.0, 45.0 to -1 / 22.0, 70.0 to 0.0, 120.0 to 1 / 70.0, 60.0 to 0.0,
    )

    @Volatile var state = State(); private set
    private var users = 0
    private var job: Job? = null
    private var s = 0.0           // distance along the loop
    private var x = 0.0; private var y = 0.0; private var pathHeading = 0.0
    private var slip = 0.0
    private var lastHeading = 0.0

    private val total = COURSE.sumOf { it.first }
    private val curvAt: (Double) -> Double = { d ->
        var rem = ((d % total) + total) % total
        var k = 0.0
        for ((len, c) in COURSE) { if (rem < len) { k = c; break }; rem -= len }
        k
    }

    @Synchronized fun acquire() {
        if (users++ == 0) job = YawApp.scope.launch { loop() }
    }

    @Synchronized fun release() {
        if (--users <= 0) { users = 0; job?.cancel(); job = null }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop() {
        val dt = 0.02
        var t = 0L
        var v = 0.0
        var st = State()
        while (isActive) {
            // Brake for the tightest corner in the next 80 m; grip ~0.9 g on gravel.
            var vMax = 48.0
            var look = 0.0
            while (look < 80) {
                val k = abs(curvAt(s + look))
                if (k > 0) vMax = min(vMax, sqrt(0.9 * 9.81 / k) + look * 0.12)
                look += 5
            }
            val accel = if (v < vMax) min(5.5, (vMax - v) * 2) else max(-9.0, (vMax - v) * 3)
            v = max(0.0, v + accel * dt)
            val k = curvAt(s)
            val yawRate = v * k                                       // rad/s, + left
            pathHeading += yawRate * dt
            val ds = v * dt
            s += ds
            x += ds * sin(PI / 2 - pathHeading)
            y += ds * cos(PI / 2 - pathHeading)
            val latG = v * v * k / 9.81
            // The tail steps out with cornering load: nose rotates further into the corner than the path.
            val targetSlip = -sign(k) * min(35.0, abs(latG) * 26.0) * (if (v > 8) 1.0 else 0.0)
            slip += (targetSlip - slip) * min(1.0, dt * 2.5)
            val course = ((90 - pathHeading * 180 / PI) % 360 + 360) % 360
            val heading = ((course + slip) % 360 + 360) % 360
            var dh = heading - lastHeading
            if (dh > 180) dh -= 360; if (dh < -180) dh += 360
            lastHeading = heading
            val gear = when { v < 12 -> 1; v < 20 -> 2; v < 28 -> 3; v < 36 -> 4; v < 44 -> 5; else -> 6 }
            val ratio = doubleArrayOf(0.0, 3.6, 2.3, 1.7, 1.35, 1.1, 0.92)[gear]
            val rpm = max(1000.0, v * ratio * 4.1 * 60 / (2 * PI * 0.31) + if (abs(slip) > 8) 600 else 0)
            val throttle = if (accel > 0.5) min(100.0, 40 + accel * 11) else if (accel < -1) 0.0 else 18.0
            st = st.copy(
                tMs = t, x = x, y = y, speed = v, course = course, heading = heading,
                yawRateCcw = -dh / dt, latG = latG, lonG = accel / 9.81,
                rpm = rpm, throttle = throttle,
                mapKpa = st.mapKpa + ((if (throttle > 60) 100 + throttle * 0.9 * min(1.0, rpm / 4000) else 35 + throttle) - st.mapKpa) * 0.08,
                coolant = min(92.0, st.coolant + dt * 0.4), oil = min(108.0, st.oil + dt * 0.25),
                iat = 25 + abs(st.mapKpa - 100) * 0.12, gear = gear,
            )
            state = st
            t += (dt * 1000).toLong()
            delay((dt * 1000).toLong())
        }
    }

    /** For the sim GPS: the course in degrees from velocity, wrapped 0..360. */
    fun bearing(dx: Double, dy: Double) = ((atan2(dx, dy) * 180 / PI) + 360) % 360
}
