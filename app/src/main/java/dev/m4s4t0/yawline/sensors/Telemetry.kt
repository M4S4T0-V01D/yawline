package dev.m4s4t0.yawline.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.obd.BleLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class GpsSource(val label: String) { PHONE("Phone GPS"), RACEBOX("RaceBox"), DEMO("Demo drive") }

/** Everything the phone knows about the car's motion right now. Angles in compass degrees. */
data class Live(
    val tMs: Long = 0,
    val hasFix: Boolean = false,
    val lat: Double = Double.NaN, val lon: Double = Double.NaN, val altM: Double = Double.NaN,
    val speed: Double = 0.0,
    val course: Double = Double.NaN,
    val heading: Double = Double.NaN,
    /** Signed drift angle, positive when the nose points right of the path. */
    val slip: Double = 0.0,
    /** Positive turning left. */
    val yawRate: Double = 0.0,
    /** Positive to the left. */
    val latG: Double = 0.0,
    val lonG: Double = 0.0,
    val accM: Double = Double.NaN,
    val gpsHz: Float = 0f,
    val sats: Int = 0,
    val source: GpsSource = GpsSource.PHONE,
    /** Metres driven since the sensors started (or the last [Telemetry.resetDistance]). */
    val distanceM: Double = 0.0,
)

/**
 * Runs the motion sensors while anyone needs them (the screens, the recorder, the co-driver) and
 * publishes [live] about 25 times a second. Reference counted: [acquire] / [release].
 */
object Telemetry {
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live
    private val _trail = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    /** The last few minutes of positions, for the mini-map. */
    val trail: StateFlow<List<Pair<Double, Double>>> = _trail

    var demo by mutableStateOf(false); private set
    var raceBoxStatus by mutableStateOf(""); private set

    private var users = 0
    private var ctx: Context? = null
    private var motion: Motion? = null
    private var lm: LocationManager? = null
    private var job: Job? = null
    private var raceBoxLink: BleLink? = null
    private val drift = DriftEstimator()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var cur = Live()
    private var fixTimes = ArrayDeque<Long>()
    private var lastFixLat = Double.NaN
    private var lastFixLon = Double.NaN
    private var distance = 0.0
    private var lastTrailAt = 0L
    private val trailBuf = ArrayDeque<Pair<Double, Double>>()

    @Synchronized fun acquire(c: Context) {
        ctx = c.applicationContext
        if (users++ == 0) startSources()
    }

    @Synchronized fun release() {
        if (--users <= 0) { users = 0; stopSources() }
    }

    /** Switches to (or from) the simulated car; restarts the sources if running. */
    @Synchronized fun useDemo(on: Boolean) {
        if (on == demo) return
        demo = on
        if (users > 0) { stopSources(); startSources() }
    }

    /** Restarts the sources, e.g. after a RaceBox was chosen or the location permission was granted. */
    @Synchronized fun restart() {
        if (users > 0) { stopSources(); startSources() }
    }

    fun resetDistance() { distance = 0.0 }

    val motionForCalibration get() = motion

    private fun startSources() {
        val c = ctx ?: return
        reset()
        if (demo) {
            SimDrive.acquire()
            job = YawApp.scope.launch {
                while (isActive) {
                    val s = SimDrive.state
                    onFix(s.lat, s.lon, 120.0, 0.0 + s.speed, s.course, 1.5, 12, GpsSource.DEMO, SystemClock.elapsedRealtime())
                    cur = cur.copy(heading = s.heading, slip = DriftEstimator.signed(s.heading - s.course), yawRate = s.yawRateCcw,
                        latG = s.latG, lonG = s.lonG)
                    _live.value = cur
                    delay(40)
                }
            }
            return
        }
        val sm = c.getSystemService(SensorManager::class.java)
        motion = Motion(sm) { dt, yaw, lat, lon ->
            drift.onImu(dt, yaw, lat * 9.81)
            cur = cur.copy(yawRate = yaw - drift.bias, latG = lat, lonG = lon, heading = drift.heading, slip = drift.slip)
        }.also { it.start() }
        if (Prefs.raceBox.isNotEmpty()) startRaceBox(c) else startPhoneGps(c)
        // Publish at a steady 25 Hz whatever the sensors' own rates are.
        job = YawApp.scope.launch {
            while (isActive) { _live.value = cur; delay(40) }
        }
    }

    private fun stopSources() {
        job?.cancel(); job = null
        motion?.stop(); motion = null
        lm?.let { it.removeUpdates(locListener); it.unregisterGnssStatusCallback(gnss) }
        lm = null
        raceBoxLink?.close(); raceBoxLink = null
        if (demo) SimDrive.release()
    }

    private fun reset() {
        cur = Live(source = if (demo) GpsSource.DEMO else if (Prefs.raceBox.isNotEmpty()) GpsSource.RACEBOX else GpsSource.PHONE)
        fixTimes.clear()
        lastFixLat = Double.NaN
        trailBuf.clear()
        _trail.value = emptyList()
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            onFix(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, if (l.hasSpeed()) l.speed.toDouble() else 0.0,
                if (l.hasBearing() && l.hasSpeed() && l.speed > 2) l.bearing.toDouble() else null,
                if (l.hasAccuracy()) l.accuracy.toDouble() else Double.NaN, cur.sats, GpsSource.PHONE, l.elapsedRealtimeNanos / 1_000_000)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { cur = cur.copy(hasFix = false) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            cur = cur.copy(sats = used)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneGps(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val m = c.getSystemService(LocationManager::class.java)
        lm = m
        // 0 ms asks for the receiver's top rate: 1 Hz on most phones, 5-10 Hz on some.
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locListener, Looper.getMainLooper())
        m.registerGnssStatusCallback(gnss, handler)
    }

    @SuppressLint("MissingPermission")
    private fun startRaceBox(c: Context) {
        val bt = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val dev = runCatching { bt.getRemoteDevice(Prefs.raceBox) }.getOrNull() ?: return
        val link = BleLink(c, dev)
        raceBoxLink = link
        raceBoxStatus = "Connecting"
        val parser = RaceBoxParser()
        YawApp.scope.launch {
            try {
                link.open()
                raceBoxStatus = "Connected"
                for (chunk in link.input) {
                    for (f in parser.feed(chunk)) {
                        if (f.fixType < 2) { cur = cur.copy(hasFix = false, sats = f.sats); continue }
                        onFix(f.lat, f.lon, f.altM, f.speedMps, if (f.speedMps > 2) f.headingDeg else null, f.hAccM, f.sats,
                            GpsSource.RACEBOX, SystemClock.elapsedRealtime())
                    }
                }
                raceBoxStatus = "Disconnected"
            } catch (t: Throwable) {
                raceBoxStatus = "Failed: ${t.message}"
            }
            // Lost the RaceBox mid-session: carry on with the phone's own GPS.
            if (raceBoxLink === link) startPhoneGps(c)
        }
    }

    @Synchronized private fun onFix(lat: Double, lon: Double, alt: Double, speed: Double, course: Double?, acc: Double, sats: Int, src: GpsSource, t: Long) {
        fixTimes.addLast(t)
        while (fixTimes.size > 1 && t - fixTimes.first() > 2000) fixTimes.removeFirst()
        val hz = if (fixTimes.size > 1) (fixTimes.size - 1) * 1000f / (fixTimes.last() - fixTimes.first()).coerceAtLeast(1) else 0f
        if (!lastFixLat.isNaN() && speed > 1.0) distance += haversine(lastFixLat, lastFixLon, lat, lon)
        lastFixLat = lat; lastFixLon = lon
        if (src != GpsSource.DEMO) drift.onGps(course, speed)
        cur = cur.copy(tMs = t, hasFix = true, lat = lat, lon = lon, altM = alt, speed = speed, course = course ?: cur.course,
            accM = acc, gpsHz = hz, sats = sats, source = src, distanceM = distance)
        if (t - lastTrailAt >= 500) {
            lastTrailAt = t
            trailBuf.addLast(lat to lon)
            if (trailBuf.size > 600) trailBuf.removeFirst()
            _trail.value = trailBuf.toList()
        }
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
