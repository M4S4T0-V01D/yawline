package dev.m4s4t0.yawline.session

import dev.m4s4t0.yawline.notes.Grade
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One corner found in a session. Direction is +1 left, -1 right. Speeds m/s, distances m. */
data class Corner(
    val index: Int,
    val start: Int, val apex: Int, val end: Int,
    val direction: Int,
    val turnDeg: Double,
    val entrySpeed: Double, val minSpeed: Double, val exitSpeed: Double,
    val maxSlip: Double, val maxLatG: Double,
    val radiusM: Double,
    val atM: Double,
    val durationMs: Long,
) {
    val grade get() = Grade.fromRadius(radiusM, turnDeg)
}

data class Summary(
    val durationMs: Long, val distanceM: Double,
    val maxSpeed: Double, val avgSpeed: Double,
    val maxSlip: Double, val maxLatG: Double, val maxBrakeG: Double, val maxAccelG: Double,
    val maxRpm: Double, val maxCoolant: Double, val maxBoostKpa: Double,
    val corners: List<Corner>,
    /** Cumulative distance at each sample. */
    val dist: DoubleArray,
)

/** Turns a recorded session into totals and a corner-by-corner breakdown. */
object Analysis {
    fun analyze(s: List<Sample>): Summary {
        val dist = DoubleArray(s.size)
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val d = if (a.lat.isNaN() || b.lat.isNaN() || b.speed < 0.5) 0.0 else Telemetry.haversine(a.lat, a.lon, b.lat, b.lon)
            dist[i] = dist[i - 1] + d
        }
        val dur = if (s.size > 1) s.last().tMs - s.first().tMs else 0L
        val moving = s.filter { it.speed > 1 }
        fun maxOf(f: (Sample) -> Double) = s.map(f).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        return Summary(
            durationMs = dur,
            distanceM = dist.lastOrNull() ?: 0.0,
            maxSpeed = maxOf { it.speed },
            avgSpeed = if (moving.isEmpty()) 0.0 else moving.sumOf { it.speed } / moving.size,
            maxSlip = maxOf { abs(it.slip) },
            maxLatG = maxOf { abs(it.latG) },
            maxBrakeG = maxOf { -it.lonG },
            maxAccelG = maxOf { it.lonG },
            maxRpm = maxOf { it.rpm },
            maxCoolant = maxOf { it.coolant },
            maxBoostKpa = maxOf { it.boostKpa },
            corners = corners(s, dist),
            dist = dist,
        )
    }

    /**
     * Corners are stretches of sustained yaw (above 8°/s, smoothed over half a second) at speed, joined
     * across short pauses, that turn the car at least 20°.
     */
    fun corners(s: List<Sample>, dist: DoubleArray): List<Corner> {
        if (s.size < 10) return emptyList()
        val yaw = DoubleArray(s.size) { i ->
            var sum = 0.0; var n = 0
            for (k in max(0, i - 2)..min(s.size - 1, i + 2)) { val v = s[k].yawRate; if (!v.isNaN()) { sum += v; n++ } }
            if (n == 0) 0.0 else sum / n
        }
        val inCorner = BooleanArray(s.size) { abs(yaw[it]) > 8 && s[it].speed > 4 }
        // Runs of turning one way, split where the direction flips...
        val raw = ArrayList<IntRange>()
        var i = 0
        while (i < s.size) {
            if (!inCorner[i]) { i++; continue }
            val dir = yaw[i] > 0
            var j = i
            while (j + 1 < s.size && inCorner[j + 1] && (yaw[j + 1] > 0) == dir) j++
            raw.add(i..j)
            i = j + 1
        }
        // ...then joined across pauses under a second when they keep turning the same way.
        val spans = ArrayList<IntRange>()
        for (r in raw) {
            val prev = spans.lastOrNull()
            if (prev != null && r.first - prev.last <= 10 && (yaw[prev.first] > 0) == (yaw[r.first] > 0)) spans[spans.size - 1] = prev.first..r.last
            else spans.add(r)
        }
        val out = ArrayList<Corner>()
        for (r in spans) {
            val first = s[r.first]; val last = s[r.last]
            val dur = last.tMs - first.tMs
            var turn = 0.0
            for (k in r.first + 1..r.last) turn += yaw[k] * (s[k].tMs - s[k - 1].tMs) / 1000.0
            if (abs(turn) < 20 || dur < 800) continue
            var apex = r.first
            for (k in r) if (s[k].speed < s[apex].speed) apex = k
            var peakYaw = 0.0
            for (k in r) peakYaw = max(peakYaw, abs(yaw[k]))
            // Tightest radius: speed over yaw rate where the car is turning hardest.
            var radius = Double.MAX_VALUE
            for (k in r) { val w = Math.toRadians(abs(yaw[k])); if (w > 0.05) radius = min(radius, s[k].speed / w) }
            out.add(
                Corner(
                    index = out.size + 1, start = r.first, apex = apex, end = r.last,
                    direction = if (turn > 0) 1 else -1, turnDeg = abs(turn),
                    entrySpeed = first.speed, minSpeed = s[apex].speed, exitSpeed = last.speed,
                    maxSlip = r.maxOf { abs(s[it].slip) }, maxLatG = r.maxOf { abs(s[it].latG) },
                    radiusM = if (radius == Double.MAX_VALUE) 999.0 else radius,
                    atM = dist[r.first], durationMs = dur,
                ),
            )
        }
        return out
    }
}
