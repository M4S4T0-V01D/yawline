package dev.m4s4t0.yawline.track

import dev.m4s4t0.yawline.notes.TrackPt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances and bearings on the Earth's surface. Compass degrees, metres. */
object Geo {
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val b = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
        return (b + 360) % 360
    }

    /** Signed change of direction from one bearing to another, -180..180, positive turning right. */
    fun turn(from: Double, to: Double): Double = ((to - from + 540) % 360) - 180

    /** The points with the running distance from the first. */
    fun measure(pts: List<Pair<Double, Double>>): List<TrackPt> {
        var d = 0.0
        return pts.mapIndexed { i, p ->
            if (i > 0) d += dist(pts[i - 1].first, pts[i - 1].second, p.first, p.second)
            TrackPt(p.first, p.second, d)
        }
    }

    /** Drops points closer than [minM] to the last one kept; always keeps the final point. */
    fun thin(pts: List<Pair<Double, Double>>, minM: Double): List<Pair<Double, Double>> {
        if (pts.size < 3) return pts
        val out = arrayListOf(pts.first())
        for (i in 1 until pts.size - 1) {
            val l = out.last()
            if (dist(l.first, l.second, pts[i].first, pts[i].second) >= minM) out.add(pts[i])
        }
        out.add(pts.last())
        return out
    }

    /** The position [d] metres along a measured line. */
    fun at(pts: List<TrackPt>, d: Double): Pair<Double, Double> {
        if (d <= 0 || pts.size < 2) return pts.first().let { it.lat to it.lon }
        var lo = 0; var hi = pts.size - 1
        if (d >= pts[hi].d) return pts[hi].let { it.lat to it.lon }
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (pts[mid].d <= d) lo = mid else hi = mid }
        val a = pts[lo]; val b = pts[hi]
        val t = if (b.d > a.d) (d - a.d) / (b.d - a.d) else 0.0
        return (a.lat + (b.lat - a.lat) * t) to (a.lon + (b.lon - a.lon) * t)
    }
}

/** A change of direction on a line, [deg] signed like [Geo.turn] (negative = left). */
data class Turn(val atM: Double, val deg: Double) {
    val left get() = deg < 0
    /** How the navigator says it: "keep left", "right", "hairpin left". */
    val call: String get() {
        val side = if (left) "left" else "right"
        val a = abs(deg)
        return when { a >= 135 -> "hairpin $side"; a >= 60 -> side; else -> "keep $side" }
    }
}

/** Where a position sits against a line: metres along it, metres off it, and the nearest point on it. */
data class OnLine(val alongM: Double, val offM: Double, val lat: Double, val lon: Double)

/** Follows a position along a line and knows the turns on it. */
class Guide(val pts: List<TrackPt>) {
    val lengthM = pts.lastOrNull()?.d ?: 0.0
    val turns: List<Turn> = findTurns(pts)

    /**
     * Projects a position onto the line. With [hintM] (where we were last), a match a little behind or
     * ahead of it wins over a closer one elsewhere, so loops and out-and-back lines don't jump. Without
     * one, of the matches about as close as the closest, the earliest wins (a loop starts at its start).
     */
    fun locate(lat: Double, lon: Double, hintM: Double = Double.NaN): OnLine {
        if (pts.isEmpty()) return OnLine(0.0, Double.NaN, lat, lon)
        if (pts.size == 1) return OnLine(0.0, Geo.dist(lat, lon, pts[0].lat, pts[0].lon), pts[0].lat, pts[0].lon)
        val my = 111_320.0
        val mx = my * cos(Math.toRadians(lat))
        var best: OnLine? = null
        var near: OnLine? = null
        val hits = ArrayList<OnLine>(pts.size)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val ax = (a.lon - lon) * mx; val ay = (a.lat - lat) * my
            val dx = (b.lon - a.lon) * mx; val dy = (b.lat - a.lat) * my
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-6) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val off = hypot(ax + t * dx, ay + t * dy)
            val along = a.d + t * (b.d - a.d)
            val hit = OnLine(along, off, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            hits.add(hit)
            if (best == null || off < best.offM) best = hit
            // Near the hint, being close along the line counts as well as being close to it.
            if (!hintM.isNaN() && along in (hintM - 150)..(hintM + 1500) &&
                (near == null || off + 0.05 * abs(along - hintM) < near.offM + 0.05 * abs(near.alongM - hintM))) near = hit
        }
        if (near != null && near.offM < 60) return near
        return hits.first { it.offM <= best!!.offM + 15 }
    }

    /** The first turn at or beyond [alongM]. */
    fun nextTurn(alongM: Double): Turn? = turns.firstOrNull { it.atM >= alongM }

    companion object {
        private const val STEP = 10.0
        private const val WINDOW = 3 // samples each side: 30 m

        /**
         * Resamples the line every 10 m and compares the direction over the 30 m before and after each
         * sample. A run of samples bending the same way by 30° or more is one turn, placed at its sharpest
         * point and sized by the whole run.
         */
        fun findTurns(pts: List<TrackPt>): List<Turn> {
            val len = pts.lastOrNull()?.d ?: return emptyList()
            val n = (len / STEP).toInt() + 1
            if (n < 2 * WINDOW + 1) return emptyList()
            val s = List(n) { Geo.at(pts, it * STEP) }
            fun brg(i: Int, j: Int) = Geo.bearing(s[i].first, s[i].second, s[j].first, s[j].second)
            val delta = DoubleArray(n)
            for (i in WINDOW until n - WINDOW) delta[i] = Geo.turn(brg(i - WINDOW, i), brg(i, i + WINDOW))
            val out = ArrayList<Turn>()
            var i = WINDOW
            while (i < n - WINDOW) {
                if (abs(delta[i]) < 30) { i++; continue }
                val sign = delta[i] > 0
                var j = i
                var peak = i
                while (j + 1 < n - WINDOW && abs(delta[j + 1]) >= 30 && (delta[j + 1] > 0) == sign) {
                    j++
                    if (abs(delta[j]) > abs(delta[peak])) peak = j
                }
                val total = Geo.turn(brg(i - WINDOW, i), brg(j, j + WINDOW))
                if (abs(total) >= 30 && (total > 0) == sign) out.add(Turn(peak * STEP, total))
                i = j + 1
            }
            return out
        }
    }
}
