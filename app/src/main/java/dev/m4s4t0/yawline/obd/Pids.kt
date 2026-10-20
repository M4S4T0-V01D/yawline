package dev.m4s4t0.yawline.obd

import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Units

/** How a value is shown, which decides the unit conversion. */
enum class Kind { RPM, SPEED, TEMP, PERCENT, KPA, BOOST, VOLT, LAMBDA, AFR, DEG, GS, LPH, NM, SEC, KM, PLAIN }

/** How often the poller asks for a PID: every cycle, every 4th, or every 20th. */
enum class Tier(val every: Int) { FAST(1), MED(4), SLOW(20) }

/**
 * One OBD-II Mode 01 value. [bytes] is how many data bytes the ECU answers with, which is what lets
 * several PIDs be split out of one multi-PID reply. Codes at or above 0x1000 are derived values the app
 * computes from real PIDs (boost from MAP and baro, and so on) and are never sent to the car.
 */
class Pid(
    val code: Int,
    val name: String,
    val short: String,
    val kind: Kind,
    val bytes: Int,
    val min: Double,
    val max: Double,
    val tier: Tier,
    val decode: (IntArray) -> Double,
) {
    val derived get() = code >= 0x1000
    val hex get() = "%02X".format(code)
}

object Pids {
    const val BOOST = 0x1000
    const val AFR = 0x1001
    const val LAMBDA = 0x1002

    private fun ab(d: IntArray) = d[0] * 256.0 + d[1]

    val ALL: List<Pid> = listOf(
        Pid(0x04, "Engine load", "LOAD", Kind.PERCENT, 1, 0.0, 100.0, Tier.MED) { it[0] * 100.0 / 255 },
        Pid(0x05, "Coolant temp", "COOLANT", Kind.TEMP, 1, -40.0, 130.0, Tier.SLOW) { it[0] - 40.0 },
        Pid(0x06, "Short fuel trim B1", "STFT1", Kind.PERCENT, 1, -25.0, 25.0, Tier.MED) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x07, "Long fuel trim B1", "LTFT1", Kind.PERCENT, 1, -25.0, 25.0, Tier.SLOW) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x08, "Short fuel trim B2", "STFT2", Kind.PERCENT, 1, -25.0, 25.0, Tier.MED) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x09, "Long fuel trim B2", "LTFT2", Kind.PERCENT, 1, -25.0, 25.0, Tier.SLOW) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x0A, "Fuel pressure", "FUEL P", Kind.KPA, 1, 0.0, 765.0, Tier.SLOW) { it[0] * 3.0 },
        Pid(0x0B, "Manifold pressure", "MAP", Kind.KPA, 1, 0.0, 255.0, Tier.FAST) { it[0].toDouble() },
        Pid(0x0C, "Engine speed", "RPM", Kind.RPM, 2, 0.0, 9000.0, Tier.FAST) { ab(it) / 4 },
        Pid(0x0D, "Vehicle speed", "SPEED", Kind.SPEED, 1, 0.0, 250.0, Tier.FAST) { it[0].toDouble() },
        Pid(0x0E, "Timing advance", "TIMING", Kind.DEG, 1, -20.0, 50.0, Tier.MED) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Intake air temp", "INTAKE", Kind.TEMP, 1, -40.0, 90.0, Tier.SLOW) { it[0] - 40.0 },
        Pid(0x10, "Mass air flow", "MAF", Kind.GS, 2, 0.0, 400.0, Tier.MED) { ab(it) / 100 },
        Pid(0x11, "Throttle position", "THROTTLE", Kind.PERCENT, 1, 0.0, 100.0, Tier.FAST) { it[0] * 100.0 / 255 },
        Pid(0x14, "O2 sensor B1S1", "O2 B1S1", Kind.VOLT, 2, 0.0, 1.275, Tier.MED) { it[0] / 200.0 },
        Pid(0x15, "O2 sensor B1S2", "O2 B1S2", Kind.VOLT, 2, 0.0, 1.275, Tier.SLOW) { it[0] / 200.0 },
        Pid(0x1F, "Run time", "RUN", Kind.SEC, 2, 0.0, 65535.0, Tier.SLOW) { ab(it) },
        Pid(0x21, "Distance with MIL on", "MIL KM", Kind.KM, 2, 0.0, 65535.0, Tier.SLOW) { ab(it) },
        Pid(0x22, "Fuel rail pressure (rel.)", "RAIL", Kind.KPA, 2, 0.0, 5177.0, Tier.SLOW) { ab(it) * 0.079 },
        Pid(0x23, "Fuel rail pressure", "RAIL", Kind.KPA, 2, 0.0, 655350.0, Tier.MED) { ab(it) * 10 },
        Pid(0x24, "Wideband lambda B1S1", "λ B1S1", Kind.LAMBDA, 4, 0.5, 1.5, Tier.MED) { ab(it) * 2 / 65536 },
        Pid(0x2C, "Commanded EGR", "EGR", Kind.PERCENT, 1, 0.0, 100.0, Tier.SLOW) { it[0] * 100.0 / 255 },
        Pid(0x2F, "Fuel level", "FUEL", Kind.PERCENT, 1, 0.0, 100.0, Tier.SLOW) { it[0] * 100.0 / 255 },
        Pid(0x31, "Distance since codes cleared", "CLR KM", Kind.KM, 2, 0.0, 65535.0, Tier.SLOW) { ab(it) },
        Pid(0x33, "Barometric pressure", "BARO", Kind.KPA, 1, 0.0, 255.0, Tier.SLOW) { it[0].toDouble() },
        Pid(0x34, "Wideband lambda B1S1 (current)", "λ B1S1", Kind.LAMBDA, 4, 0.5, 1.5, Tier.MED) { ab(it) * 2 / 65536 },
        Pid(0x3C, "Catalyst temp B1S1", "CAT", Kind.TEMP, 2, -40.0, 1000.0, Tier.SLOW) { ab(it) / 10 - 40 },
        Pid(0x3D, "Catalyst temp B2S1", "CAT B2", Kind.TEMP, 2, -40.0, 1000.0, Tier.SLOW) { ab(it) / 10 - 40 },
        Pid(0x42, "Control module voltage", "VOLTS", Kind.VOLT, 2, 10.0, 16.0, Tier.SLOW) { ab(it) / 1000 },
        Pid(0x43, "Absolute load", "ABS LOAD", Kind.PERCENT, 2, 0.0, 200.0, Tier.MED) { ab(it) * 100 / 255 },
        Pid(0x44, "Commanded lambda", "CMD λ", Kind.LAMBDA, 2, 0.5, 1.5, Tier.MED) { ab(it) * 2 / 65536 },
        Pid(0x45, "Relative throttle", "REL TPS", Kind.PERCENT, 1, 0.0, 100.0, Tier.MED) { it[0] * 100.0 / 255 },
        Pid(0x46, "Ambient air temp", "AMBIENT", Kind.TEMP, 1, -40.0, 60.0, Tier.SLOW) { it[0] - 40.0 },
        Pid(0x49, "Accelerator pedal D", "PEDAL", Kind.PERCENT, 1, 0.0, 100.0, Tier.FAST) { it[0] * 100.0 / 255 },
        Pid(0x4C, "Commanded throttle", "CMD TPS", Kind.PERCENT, 1, 0.0, 100.0, Tier.MED) { it[0] * 100.0 / 255 },
        Pid(0x52, "Ethanol content", "ETHANOL", Kind.PERCENT, 1, 0.0, 100.0, Tier.SLOW) { it[0] * 100.0 / 255 },
        Pid(0x5A, "Relative pedal", "REL PEDAL", Kind.PERCENT, 1, 0.0, 100.0, Tier.MED) { it[0] * 100.0 / 255 },
        Pid(0x5C, "Engine oil temp", "OIL", Kind.TEMP, 1, -40.0, 150.0, Tier.SLOW) { it[0] - 40.0 },
        Pid(0x5D, "Injection timing", "INJ", Kind.DEG, 2, -210.0, 302.0, Tier.MED) { ab(it) / 128 - 210 },
        Pid(0x5E, "Fuel rate", "FUEL RATE", Kind.LPH, 2, 0.0, 100.0, Tier.MED) { ab(it) / 20 },
        Pid(0x61, "Driver demand torque", "DEMAND", Kind.PERCENT, 1, -125.0, 130.0, Tier.MED) { it[0] - 125.0 },
        Pid(0x62, "Actual torque", "TORQUE", Kind.PERCENT, 1, -125.0, 130.0, Tier.MED) { it[0] - 125.0 },
        Pid(0x63, "Reference torque", "REF NM", Kind.NM, 2, 0.0, 1000.0, Tier.SLOW) { ab(it) },
        Pid(0xA6, "Odometer", "ODO", Kind.KM, 4, 0.0, 999999.0, Tier.SLOW) { (it[0].toLong() shl 24 or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0 },
        // Derived values (never sent to the car).
        Pid(BOOST, "Boost (MAP − baro)", "BOOST", Kind.BOOST, 0, -100.0, 250.0, Tier.FAST) { 0.0 },
        Pid(AFR, "Air-fuel ratio", "AFR", Kind.AFR, 0, 10.0, 20.0, Tier.MED) { 0.0 },
        Pid(LAMBDA, "Lambda", "λ", Kind.LAMBDA, 0, 0.6, 1.4, Tier.MED) { 0.0 },
    )

    val byCode: Map<Int, Pid> = ALL.associateBy { it.code }

    operator fun get(code: Int): Pid? = byCode[code]

    /** Values the app works out from real PIDs, given everything read so far. */
    fun derive(v: Map<Int, Double>): Map<Int, Double> {
        val out = HashMap<Int, Double>(3)
        val map = v[0x0B]
        if (map != null) out[BOOST] = map - (v[0x33] ?: 101.3)
        val lambda = v[0x24] ?: v[0x34] ?: v[0x44]
        if (lambda != null && lambda > 0.1) {
            out[LAMBDA] = lambda
            out[AFR] = lambda * 14.7
        }
        return out
    }

    /** The value in the user's units, without the unit label. */
    fun format(p: Pid, v: Double?, u: Units): String {
        if (v == null || v.isNaN()) return "--"
        return when (p.kind) {
            Kind.RPM -> Fmt.n(v, 0)
            Kind.SPEED -> Fmt.n(u.kmh(v), 0)
            Kind.TEMP -> Fmt.n(u.temp(v), 0)
            Kind.PERCENT -> Fmt.n(v, 0)
            Kind.KPA -> Fmt.n(v, 0)
            Kind.BOOST -> Fmt.n(u.press(v), if (u == Units.METRIC) 2 else 1)
            Kind.VOLT -> Fmt.n(v, if (v < 2) 3 else 1)
            Kind.LAMBDA -> Fmt.n(v, 2)
            Kind.AFR -> Fmt.n(v, 1)
            Kind.DEG -> Fmt.n(v, 1)
            Kind.GS -> Fmt.n(v, 1)
            Kind.LPH -> Fmt.n(v, 1)
            Kind.NM -> Fmt.n(v, 0)
            Kind.SEC -> Fmt.duration((v * 1000).toLong())
            Kind.KM -> Fmt.n(u.kmh(v), 0)
            Kind.PLAIN -> Fmt.n(v, 1)
        }
    }

    fun unit(p: Pid, u: Units): String = when (p.kind) {
        Kind.RPM -> "rpm"
        Kind.SPEED -> u.speedLabel
        Kind.TEMP -> u.tempLabel
        Kind.PERCENT -> "%"
        Kind.KPA -> "kPa"
        Kind.BOOST -> u.pressLabel
        Kind.VOLT -> "V"
        Kind.LAMBDA -> "λ"
        Kind.AFR -> ":1"
        Kind.DEG -> "°"
        Kind.GS -> "g/s"
        Kind.LPH -> "L/h"
        Kind.NM -> "Nm"
        Kind.SEC -> ""
        Kind.KM -> u.distLabel
        Kind.PLAIN -> ""
    }

    /** Where [v] sits between the PID's min and max, 0..1, in metric (the gauge scale doesn't care). */
    fun fraction(p: Pid, v: Double?): Float {
        if (v == null || v.isNaN()) return 0f
        return ((v - p.min) / (p.max - p.min)).toFloat().coerceIn(0f, 1f)
    }
}
