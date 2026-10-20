package dev.m4s4t0.yawline.session

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.DriveService
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.obd.Obd
import dev.m4s4t0.yawline.obd.ObdState
import dev.m4s4t0.yawline.obd.Pids
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Records GPS, motion and every car value 10 times a second to a session file, and keeps the session's
 * peaks for the live screens. Runs under [DriveService] so it carries on with the screen off.
 */
object Recorder {
    var recording by mutableStateOf(false); private set
    var startedAt by mutableLongStateOf(0L); private set
    var peakSpeed by mutableDoubleStateOf(0.0); private set
    var peakSlip by mutableDoubleStateOf(0.0); private set
    var peakLatG by mutableDoubleStateOf(0.0); private set
    var lastSaved by mutableStateOf<SessionMeta?>(null); private set

    private var job: Job? = null
    private var writer: BufferedWriter? = null
    private var id = ""
    private var startWall = 0L

    fun start(ctx: Context) {
        if (recording) return
        Telemetry.acquire(ctx)
        startWall = System.currentTimeMillis()
        id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startWall))
        Sessions.dir(id).mkdirs()
        val w = Sessions.samplesFile(id).bufferedWriter()
        w.write(Sample.HEADER); w.newLine()
        writer = w
        peakSpeed = 0.0; peakSlip = 0.0; peakLatG = 0.0
        startedAt = SystemClock.elapsedRealtime()
        recording = true
        val t0 = startedAt
        job = YawApp.scope.launch {
            while (isActive) {
                val l = Telemetry.live.value
                val v = Obd.values.value
                fun o(code: Int) = v[code] ?: Double.NaN
                val s = Sample(
                    tMs = SystemClock.elapsedRealtime() - t0,
                    lat = if (l.hasFix) l.lat else Double.NaN, lon = if (l.hasFix) l.lon else Double.NaN, altM = l.altM,
                    speed = l.speed, course = l.course, heading = l.heading, slip = l.slip,
                    latG = l.latG, lonG = l.lonG, yawRate = l.yawRate, accM = l.accM,
                    rpm = o(0x0C), throttle = v[0x11] ?: o(0x49), load = o(0x04),
                    coolant = o(0x05), intake = o(0x0F), oil = o(0x5C),
                    boostKpa = o(Pids.BOOST), lambda = o(Pids.LAMBDA), timing = o(0x0E), volts = o(0x42),
                    obdSpeedKmh = o(0x0D),
                )
                w.write(s.csv()); w.newLine()
                peakSpeed = max(peakSpeed, l.speed)
                if (l.speed > 5) peakSlip = max(peakSlip, abs(l.slip))
                peakLatG = max(peakLatG, abs(l.latG))
                delay(100)
            }
        }
        DriveService.start(ctx)
    }

    /** Stops and saves the session (with its analysis); returns it, or null if it was too short to keep. */
    fun stop(): SessionMeta? {
        if (!recording) return null
        job?.cancel(); job = null
        runCatching { writer?.close() }
        writer = null
        recording = false
        Telemetry.release()
        val samples = Sessions.load(id)
        if (samples.size < 20) {
            Sessions.delete(id)
            return null
        }
        val sum = Analysis.analyze(samples)
        val car = (Obd.state.value as? ObdState.Live)?.let { it.vin ?: it.adapter } ?: ""
        val meta = SessionMeta(
            id = id,
            name = "Session " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(startWall)),
            startMs = startWall, durationMs = sum.durationMs, distanceM = sum.distanceM,
            maxSpeed = sum.maxSpeed, maxSlip = sum.maxSlip, maxLatG = sum.maxLatG, maxRpm = sum.maxRpm,
            corners = sum.corners.size, car = car,
        )
        Sessions.save(meta)
        lastSaved = meta
        return meta
    }

    val elapsedMs get() = if (recording) SystemClock.elapsedRealtime() - startedAt else 0L
}
