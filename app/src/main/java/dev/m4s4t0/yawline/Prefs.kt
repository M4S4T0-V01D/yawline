package dev.m4s4t0.yawline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The user's settings, as Compose state (so screens update the moment a setting changes) backed by
 * SharedPreferences.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    var theme by mutableStateOf("carbon"); private set
    var units by mutableStateOf(Units.METRIC); private set
    /** Shift light comes on here; the RPM bar tops out a little above it. */
    var shiftRpm by mutableStateOf(7000); private set
    var keepScreenOn by mutableStateOf(true); private set
    /** Seconds ahead of a note that the co-driver reads it, at the current speed. */
    var callLeadSec by mutableStateOf(4.0f); private set
    var voiceRate by mutableStateOf(1.15f); private set
    /** Rotation (degrees) applied to the phone's guess of "forward", from the forward calibration. */
    var mountYawOffset by mutableStateOf(0f); private set
    /** Dash tile PIDs (see Pids), in order. */
    val DEFAULT_TILES = listOf(0x0C, 0x0D, 0x05, dev.m4s4t0.yawline.obd.Pids.BOOST, 0x11, 0x0F,
        dev.m4s4t0.yawline.obd.Pids.AFR, 0x42, 0x5C, 0x0E, 0x04, 0x2F)
    var dashTiles by mutableStateOf(DEFAULT_TILES); private set
    /** Last OBD adapter, so the Garage can reconnect with one tap: "bt:<mac>", "ble:<mac>", "usb", "sim". */
    var lastAdapter by mutableStateOf(""); private set
    var lastAdapterName by mutableStateOf(""); private set
    /** BLE address of a RaceBox used as the GPS instead of the phone's; empty uses the phone. */
    var raceBox by mutableStateOf(""); private set
    var coolantWarnC by mutableStateOf(105); private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("yawline", Context.MODE_PRIVATE)
        theme = sp.getString("theme", "carbon")!!
        units = runCatching { Units.valueOf(sp.getString("units", "METRIC")!!) }.getOrDefault(Units.METRIC)
        shiftRpm = sp.getInt("shiftRpm", 7000)
        keepScreenOn = sp.getBoolean("keepScreenOn", true)
        callLeadSec = sp.getFloat("callLeadSec", 4.0f)
        voiceRate = sp.getFloat("voiceRate", 1.15f)
        mountYawOffset = sp.getFloat("mountYawOffset", 0f)
        dashTiles = sp.getString("dashTiles", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_TILES
        lastAdapter = sp.getString("lastAdapter", "")!!
        lastAdapterName = sp.getString("lastAdapterName", "")!!
        raceBox = sp.getString("raceBox", "")!!
        coolantWarnC = sp.getInt("coolantWarnC", 105)
    }

    fun saveTheme(id: String) { theme = id; sp.edit().putString("theme", id).apply() }
    fun saveUnits(u: Units) { units = u; sp.edit().putString("units", u.name).apply() }
    fun saveShiftRpm(v: Int) { shiftRpm = v; sp.edit().putInt("shiftRpm", v).apply() }
    fun saveKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("keepScreenOn", v).apply() }
    fun saveCallLeadSec(v: Float) { callLeadSec = v; sp.edit().putFloat("callLeadSec", v).apply() }
    fun saveVoiceRate(v: Float) { voiceRate = v; sp.edit().putFloat("voiceRate", v).apply() }
    fun saveMountYawOffset(v: Float) { mountYawOffset = v; sp.edit().putFloat("mountYawOffset", v).apply() }
    fun saveDashTiles(v: List<Int>) { dashTiles = v; sp.edit().putString("dashTiles", v.joinToString(",")).apply() }
    fun saveLastAdapter(id: String, name: String) {
        lastAdapter = id; lastAdapterName = name
        sp.edit().putString("lastAdapter", id).putString("lastAdapterName", name).apply()
    }
    fun saveRaceBox(addr: String) { raceBox = addr; sp.edit().putString("raceBox", addr).apply() }
    fun saveCoolantWarnC(v: Int) { coolantWarnC = v; sp.edit().putInt("coolantWarnC", v).apply() }

    /** RPM, speed, coolant, boost, throttle, intake, AFR, voltage, oil, timing, load, fuel. */
}
