package dev.m4s4t0.yawline.track

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.DriveService
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lays down a track as you drive: a point every 5 m from fixes good to 25 m or better, ignoring the
 * GPS wandering while parked. Runs under [DriveService] so it carries on with the screen off.
 */
object TrackRecorder {
    var recording by mutableStateOf(false); private set
    var startedAt by mutableLongStateOf(0L); private set
    var distanceM by mutableDoubleStateOf(0.0); private set
    private val _pts = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val pts: StateFlow<List<Pair<Double, Double>>> = _pts

    private var job: Job? = null

    fun start(ctx: Context) {
        if (recording) return
        Telemetry.acquire(ctx)
        _pts.value = emptyList()
        distanceM = 0.0
        startedAt = SystemClock.elapsedRealtime()
        recording = true
        job = YawApp.scope.launch {
            while (isActive) {
                val l = Telemetry.live.value
                if (l.hasFix && !(l.accM > 25)) {
                    val last = _pts.value.lastOrNull()
                    if (last == null) _pts.value = listOf(l.lat to l.lon)
                    else if (l.speed > 0.8) {
                        val step = Geo.dist(last.first, last.second, l.lat, l.lon)
                        if (step >= 5) { distanceM += step; _pts.value = _pts.value + (l.lat to l.lon) }
                    }
                }
                delay(200)
            }
        }
        DriveService.start(ctx)
    }

    /** Stops and saves; returns the track, or null if there was nothing to keep. */
    fun stop(name: String): Track? {
        val pts = finish()
        return if (pts.size < 2) null else Tracks.create(name, TrackKind.GPS, pts)
    }

    fun cancel() { finish() }

    private fun finish(): List<Pair<Double, Double>> {
        if (!recording) return emptyList()
        job?.cancel(); job = null
        recording = false
        Telemetry.release()
        return _pts.value
    }

    val elapsedMs get() = if (recording) SystemClock.elapsedRealtime() - startedAt else 0L
}
