package dev.m4s4t0.yawline.session

import java.util.Locale

/**
 * One row of a recorded session, 10 per second. Car values are NaN when the adapter did not supply
 * them. Stored as CSV so it opens in any spreadsheet or telemetry tool.
 */
data class Sample(
    val tMs: Long,
    val lat: Double, val lon: Double, val altM: Double,
    val speed: Double,          // m/s (GPS)
    val course: Double, val heading: Double, val slip: Double,
    val latG: Double, val lonG: Double, val yawRate: Double,
    val accM: Double,
    val rpm: Double, val throttle: Double, val load: Double,
    val coolant: Double, val intake: Double, val oil: Double,
    val boostKpa: Double, val lambda: Double, val timing: Double, val volts: Double,
    val obdSpeedKmh: Double,
) {
    fun csv(): String {
        val sb = StringBuilder(160)
        sb.append(tMs)
        for ((i, v) in doubles().withIndex()) {
            sb.append(',')
            // Positions need fixed decimals (1e-7° is about a centimetre); the rest are fine at 6 digits.
            if (!v.isNaN()) sb.append(String.format(Locale.US, if (i < 2) "%.7f" else "%.6g", v))
        }
        return sb.toString()
    }

    private fun doubles() = doubleArrayOf(lat, lon, altM, speed, course, heading, slip, latG, lonG, yawRate, accM,
        rpm, throttle, load, coolant, intake, oil, boostKpa, lambda, timing, volts, obdSpeedKmh)

    companion object {
        const val HEADER = "t_ms,lat,lon,alt_m,speed_mps,course_deg,heading_deg,slip_deg,lat_g,lon_g,yaw_dps,gps_acc_m," +
            "rpm,throttle_pct,load_pct,coolant_c,intake_c,oil_c,boost_kpa,lambda,timing_deg,volts,obd_speed_kmh"

        fun parse(line: String): Sample? {
            val p = line.split(',')
            if (p.size < 23) return null
            val t = p[0].toLongOrNull() ?: return null
            fun d(i: Int) = p[i].toDoubleOrNull() ?: Double.NaN
            return Sample(t, d(1), d(2), d(3), d(4), d(5), d(6), d(7), d(8), d(9), d(10), d(11), d(12), d(13), d(14),
                d(15), d(16), d(17), d(18), d(19), d(20), d(21), d(22))
        }
    }
}
