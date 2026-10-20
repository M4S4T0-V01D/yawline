package dev.m4s4t0.yawline

import java.util.Locale
import kotlin.math.abs

/** Display units. Everything is stored metric (m/s, °C, kPa, m) and converted only for showing. */
enum class Units(val label: String, val speedLabel: String, val distLabel: String, val tempLabel: String, val pressLabel: String) {
    METRIC("Metric", "km/h", "km", "°C", "bar"),
    IMPERIAL("Imperial", "mph", "mi", "°F", "psi");

    fun speed(mps: Double) = if (this == METRIC) mps * 3.6 else mps * 2.236936
    fun kmh(kmh: Double) = if (this == METRIC) kmh else kmh * 0.621371
    fun temp(c: Double) = if (this == METRIC) c else c * 9.0 / 5.0 + 32.0
    /** Gauge pressure (boost) from kPa. */
    fun press(kpa: Double) = if (this == METRIC) kpa / 100.0 else kpa * 0.1450377
    fun dist(m: Double) = if (this == METRIC) m / 1000.0 else m / 1609.344
    fun shortDist(m: Double) = if (this == METRIC) m else m * 1.093613
    val shortDistLabel get() = if (this == METRIC) "m" else "yd"
}

object Fmt {
    fun n(v: Double, decimals: Int): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    fun signed(v: Double, decimals: Int): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%+.${decimals}f", v)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun lapTime(ms: Long): String = String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms % 1000 / 100)

    fun angle(deg: Double): String = if (deg.isNaN()) "--°" else "${abs(deg).toInt()}°"
}
