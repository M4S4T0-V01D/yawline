package dev.m4s4t0.yawline.obd

/**
 * Plain-English meanings of the common generic (SAE) trouble codes. Manufacturer codes (P1xxx, and the
 * second digit 1 or 3 in general) differ per make, so those get a family description instead.
 */
object DtcInfo {
    private val KNOWN = mapOf(
        "P0010" to "Camshaft position actuator circuit (bank 1)",
        "P0011" to "Camshaft timing over-advanced (bank 1)",
        "P0016" to "Crankshaft/camshaft position correlation",
        "P0087" to "Fuel rail pressure too low",
        "P0088" to "Fuel rail pressure too high",
        "P0100" to "Mass air flow circuit",
        "P0101" to "Mass air flow range/performance",
        "P0102" to "Mass air flow circuit low",
        "P0106" to "Manifold pressure sensor range/performance",
        "P0107" to "Manifold pressure sensor low",
        "P0108" to "Manifold pressure sensor high",
        "P0110" to "Intake air temperature circuit",
        "P0113" to "Intake air temperature sensor high",
        "P0115" to "Coolant temperature circuit",
        "P0116" to "Coolant temperature range/performance",
        "P0117" to "Coolant temperature sensor low",
        "P0118" to "Coolant temperature sensor high",
        "P0120" to "Throttle position sensor circuit",
        "P0121" to "Throttle position range/performance",
        "P0122" to "Throttle position sensor low",
        "P0125" to "Coolant too cold for closed-loop fuel",
        "P0128" to "Coolant below thermostat temperature",
        "P0130" to "O2 sensor circuit (B1S1)",
        "P0131" to "O2 sensor low voltage (B1S1)",
        "P0133" to "O2 sensor slow response (B1S1)",
        "P0135" to "O2 sensor heater circuit (B1S1)",
        "P0141" to "O2 sensor heater circuit (B1S2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0174" to "System too lean (bank 2)",
        "P0175" to "System too rich (bank 2)",
        "P0191" to "Fuel rail pressure sensor range/performance",
        "P0201" to "Injector circuit, cylinder 1",
        "P0202" to "Injector circuit, cylinder 2",
        "P0203" to "Injector circuit, cylinder 3",
        "P0204" to "Injector circuit, cylinder 4",
        "P0217" to "Engine over-temperature",
        "P0219" to "Engine over-speed",
        "P0234" to "Turbo overboost",
        "P0236" to "Turbo boost sensor range/performance",
        "P0299" to "Turbo underboost",
        "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0305" to "Cylinder 5 misfire",
        "P0306" to "Cylinder 6 misfire",
        "P0325" to "Knock sensor circuit (bank 1)",
        "P0327" to "Knock sensor low (bank 1)",
        "P0335" to "Crankshaft position sensor circuit",
        "P0340" to "Camshaft position sensor circuit",
        "P0351" to "Ignition coil A circuit",
        "P0352" to "Ignition coil B circuit",
        "P0353" to "Ignition coil C circuit",
        "P0354" to "Ignition coil D circuit",
        "P0400" to "EGR flow",
        "P0401" to "EGR flow insufficient",
        "P0420" to "Catalyst efficiency below threshold (bank 1)",
        "P0430" to "Catalyst efficiency below threshold (bank 2)",
        "P0440" to "EVAP system",
        "P0442" to "EVAP small leak",
        "P0455" to "EVAP large leak",
        "P0456" to "EVAP very small leak",
        "P0480" to "Cooling fan 1 control circuit",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle control system",
        "P0506" to "Idle speed lower than expected",
        "P0507" to "Idle speed higher than expected",
        "P0520" to "Oil pressure sensor circuit",
        "P0521" to "Oil pressure sensor range/performance",
        "P0524" to "Oil pressure too low",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0571" to "Brake switch circuit",
        "P0600" to "ECU serial communication link",
        "P0601" to "ECU memory checksum",
        "P0606" to "ECU processor fault",
        "P0700" to "Transmission control system (see TCU codes)",
        "P0715" to "Transmission input speed sensor",
        "P0720" to "Transmission output speed sensor",
        "P0730" to "Incorrect gear ratio",
        "P0740" to "Torque converter clutch circuit",
        "P2135" to "Throttle/pedal sensor correlation",
        "P2138" to "Pedal position sensor correlation",
        "P2187" to "System too lean at idle (bank 1)",
        "P2263" to "Turbo boost system performance",
        "C0035" to "Left front wheel speed sensor circuit",
        "C0040" to "Right front wheel speed sensor circuit",
        "C0045" to "Left rear wheel speed sensor circuit",
        "C0050" to "Right rear wheel speed sensor circuit",
        "C0242" to "ECU-detected traction control fault",
        "U0100" to "Lost communication with engine ECU",
        "U0101" to "Lost communication with transmission ECU",
        "U0121" to "Lost communication with ABS module",
        "U0155" to "Lost communication with instrument cluster",
    )

    private val SYSTEMS = mapOf(
        "P01" to "Fuel and air metering", "P02" to "Fuel and air metering (injectors)", "P03" to "Ignition or misfire",
        "P04" to "Emission controls", "P05" to "Speed, idle control and inputs", "P06" to "Computer and outputs",
        "P07" to "Transmission", "P08" to "Transmission", "P0A" to "Hybrid propulsion",
    )

    fun describe(code: String): String {
        KNOWN[code]?.let { return it }
        val makeSpecific = code.length == 5 && (code[1] == '1' || code[1] == '3')
        val family = when (code.first()) { 'P' -> "Powertrain"; 'C' -> "Chassis"; 'B' -> "Body"; else -> "Network" }
        val system = SYSTEMS[code.take(3)]
        return when {
            makeSpecific -> "$family — manufacturer-specific code"
            system != null -> "$family — $system"
            else -> "$family code"
        }
    }

    /** Misfires, overheating, oil pressure and over-boost: worth stopping for. */
    fun severe(code: String) = code.startsWith("P030") || code in setOf("P0217", "P0219", "P0234", "P0524", "P0520")
}
