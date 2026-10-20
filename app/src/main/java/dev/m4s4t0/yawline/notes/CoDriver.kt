package dev.m4s4t0.yawline.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.DriveService
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Reads the notes out as you drive, like a co-driver: each call comes a few seconds before you reach it
 * ([Prefs.callLeadSec] at the current speed, never less than 40 m), and calls that follow closely are
 * read together ("left four into right three").
 *
 * Where you are on the stage comes from the recce line if the stage has one (it follows your GPS
 * position, so it corrects itself), otherwise from distance driven since the start. Either way the
 * buttons can nudge it: Sync says "I'm at the next call now".
 */
object CoDriver {
    var stage by mutableStateOf<Stage?>(null); private set
    var next by mutableIntStateOf(0); private set
    var position by mutableDoubleStateOf(0.0); private set
    var lastCall by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set
    var offTrack by mutableStateOf(false); private set
    var positions = DoubleArray(0); private set

    private var job: Job? = null
    private var odoOrigin = 0.0
    private var adjust = 0.0
    private var trackIdx = 0
    private var lastOdo = 0.0

    fun start(ctx: Context, s: Stage) {
        stop()
        Voice.init(ctx)
        Telemetry.acquire(ctx)
        stage = s
        positions = Stages.positions(s)
        next = 0
        adjust = 0.0
        trackIdx = 0
        odoOrigin = Telemetry.live.value.distanceM
        lastOdo = odoOrigin
        position = 0.0
        lastCall = ""
        running = true
        DriveService.start(ctx)
        Voice.say("${s.name}. ${s.notes.size} notes. Ready.")
        job = YawApp.scope.launch {
            while (isActive) {
                tick()
                delay(100)
            }
        }
    }

    fun stop() {
        if (!running) return
        job?.cancel(); job = null
        running = false
        Voice.stop()
        Telemetry.release()
    }

    private suspend fun tick() {
        val s = stage ?: return
        val l = Telemetry.live.value
        val odo = l.distanceM
        if (s.track.size > 1 && l.hasFix) {
            // Look for ourselves on the recce line a little behind and well ahead of the last match.
            val (idx, d) = Stages.nearest(s.track, l.lat, l.lon, trackIdx - 10, trackIdx + 400)
            val off = Stages.gap(s.track[idx], l.lat, l.lon)
            offTrack = off > 60
            if (!offTrack) {
                trackIdx = idx
                position = d + adjust
            } else {
                position += odo - lastOdo
            }
        } else {
            position = odo - odoOrigin + adjust
        }
        lastOdo = odo
        if (next >= s.notes.size) return
        val lead = max(40.0, l.speed * Prefs.callLeadSec)
        if (positions[next] - position <= lead) {
            // Chain the calls that come up before this one would be finished.
            val say = ArrayList<String>()
            var i = next
            while (i < s.notes.size && say.size < 3 && (i == next || positions[i] - positions[next] < lead * 0.6)) {
                say += NoteParser.speak(s.notes[i])
                i++
            }
            next = i
            lastCall = say.joinToString("  ·  ")
            withContext(Dispatchers.Main) { Voice.say(say.joinToString(", ")) }
        }
    }

    /** "I'm at the next call now": snaps the position to it and reads it. */
    fun sync() {
        val s = stage ?: return
        if (next >= s.notes.size) return
        adjust += positions[next] - position
        position = positions[next]
    }

    fun nudge(metres: Double) { adjust += metres; position += metres }

    /** Reads the last call again. */
    fun repeat() { if (lastCall.isNotEmpty()) Voice.say(lastCall.replace("  ·  ", ", "), urgent = true) }

    /** Skips back one call so it is read again when due. */
    fun back() { if (next > 0) next-- }

    /** Reads the next call now, without waiting for it. */
    fun skip() {
        val s = stage ?: return
        if (next >= s.notes.size) return
        val t = NoteParser.speak(s.notes[next])
        lastCall = t
        next++
        Voice.say(t, urgent = true)
    }

    val distanceToNext: Double get() = if (next < positions.size) positions[next] - position else Double.NaN
}
