package dev.m4s4t0.yawline

import dev.m4s4t0.yawline.session.Analysis
import dev.m4s4t0.yawline.session.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AnalysisTest {
    /** 10 Hz at 15 m/s: straight, a 90° left at 15°/s, straight, a 60° right at 20°/s, straight. */
    private fun drive(): List<Sample> {
        val out = ArrayList<Sample>()
        var x = 0.0; var y = 0.0; var course = 0.0; var t = 0L
        val nan = Double.NaN
        for ((n, yaw) in listOf(50 to 0.0, 60 to 15.0, 50 to 0.0, 30 to -20.0, 50 to 0.0)) repeat(n) {
            course -= yaw * 0.1 // CCW-positive yaw lowers the compass course
            x += 1.5 * sin(Math.toRadians(course)); y += 1.5 * cos(Math.toRadians(course))
            out += Sample(
                t, 62.0 + y / 111_320.0, 25.0 + x / (111_320.0 * cos(Math.toRadians(62.0))), nan,
                15.0, course, course, if (yaw != 0.0) 12.0 else 0.0, Math.toRadians(yaw) * 15 / 9.81, 0.0, yaw, 3.0,
                nan, nan, nan, nan, nan, nan, nan, nan, nan, nan, nan,
            )
            t += 100
        }
        return out
    }

    @Test fun findsCorners() {
        val s = Analysis.analyze(drive())
        assertEquals(2, s.corners.size)
        val (a, b) = s.corners
        assertEquals(1, a.direction); assertEquals(90.0, a.turnDeg, 8.0)
        assertEquals(-1, b.direction); assertEquals(60.0, b.turnDeg, 8.0)
        assertEquals(57.0, a.radiusM, 6.0) // 15 m/s ÷ 0.262 rad/s
        assertTrue(a.atM in 60.0..90.0)
        assertEquals(12.0, a.maxSlip, 1e-9)
        assertEquals(15.0 * 240 * 0.1, s.distanceM, 10.0)
    }

    @Test fun csvRoundTrip() {
        val x = drive()[70]
        val back = Sample.parse(x.csv())!!
        assertEquals(x.lat, back.lat, 1e-7); assertEquals(x.lon, back.lon, 1e-7)
        assertEquals(x.yawRate, back.yawRate, 1e-4)
        assertTrue(back.rpm.isNaN())
    }
}
