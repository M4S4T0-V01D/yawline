package dev.m4s4t0.yawline.track

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.DriveService
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.Units
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.notes.Voice
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Guides you along a saved track: where you are on it, the next turn and how far, and spoken calls
 * ("right in 200", "right now", "off route"). Until you reach the line it points you to it.
 */
object Navigator {
    var track by mutableStateOf<Track?>(null); private set
    var guide by mutableStateOf<Guide?>(null); private set
    var running by mutableStateOf(false); private set
    /** Metres along the track, and off it. */
    var alongM by mutableDoubleStateOf(0.0); private set
    var offM by mutableDoubleStateOf(Double.NaN); private set
    /** The nearest point on the line, where to head while off it. */
    var target by mutableStateOf<Pair<Double, Double>?>(null); private set
    var next by mutableStateOf<Turn?>(null); private set
    /** Reached the line at least once. */
    var joined by mutableStateOf(false); private set
    var offRoute by mutableStateOf(false); private set
    var arrived by mutableStateOf(false); private set

    val toGoM get() = (guide?.lengthM ?: 0.0) - alongM
    val toTurnM get() = next?.let { it.atM - alongM } ?: Double.NaN

    private var job: Job? = null
    private var hint = Double.NaN
    private var joinedAt = 0.0
    private var farSince = 0L
    private var calledEarly = Double.NaN
    private var calledNow = Double.NaN

    fun start(ctx: Context, t: Track, reverse: Boolean = false) {
        stop()
        Voice.init(ctx)
        Telemetry.acquire(ctx)
        val tr = if (reverse) t.reversed() else t
        val g = Guide(tr.pts)
        track = tr; guide = g
        alongM = 0.0; offM = Double.NaN; target = null; next = g.turns.firstOrNull()
        hint = Double.NaN; farSince = 0L; calledEarly = Double.NaN; calledNow = Double.NaN
        joined = false; offRoute = false; arrived = false
        running = true
        DriveService.start(ctx)
        Voice.say("${tr.name}. ${spoken(g.lengthM)}. ${g.turns.size} turns.")
        job = YawApp.scope.launch {
            while (isActive) {
                tick()
                delay(250)
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

    private fun tick() {
        val g = guide ?: return
        val l = Telemetry.live.value
        if (!l.hasFix) return
        val on = g.locate(l.lat, l.lon, hint)
        alongM = on.alongM; offM = on.offM; target = on.lat to on.lon
        val now = SystemClock.elapsedRealtime()

        if (!joined) {
            if (on.offM < 30) { joined = true; joinedAt = on.alongM; Voice.say("On the line.") } else return
        }
        hint = on.alongM

        // Off route after 3 s more than 50 m away; back on inside 25 m.
        if (on.offM > 50) {
            if (farSince == 0L) farSince = now
            if (!offRoute && now - farSince > 3000) { offRoute = true; Voice.say("Off route.", urgent = true) }
        } else farSince = 0L
        if (offRoute && on.offM < 25) { offRoute = false; Voice.say("Back on route.") }

        val turn = g.nextTurn(alongM + 5)
        next = turn
        if (turn != null && !offRoute) {
            val dist = turn.atM - alongM
            val soon = max(30.0, l.speed * 2.5)
            val early = max(150.0, l.speed * 10)
            if (dist <= soon && calledNow != turn.atM) {
                calledNow = turn.atM; calledEarly = turn.atM
                val then = g.turns.firstOrNull { it.atM > turn.atM }?.takeIf { it.atM - turn.atM < 80 }
                Voice.say(turn.call.cap() + " now" + (then?.let { ", then ${it.call}" } ?: "") + ".", urgent = true)
            } else if (dist <= early && dist > soon + 40 && calledEarly != turn.atM) {
                calledEarly = turn.atM
                Voice.say("${turn.call.cap()} in ${spoken(dist)}.")
            }
        }

        val travelled = alongM - joinedAt > minOf(100.0, g.lengthM / 2)
        if (!arrived && travelled && g.lengthM - alongM < 25 && on.offM < 40) { arrived = true; Voice.say("Arrived. End of the line.") }
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    /** A distance as the voice says it: "200 metres", "1.4 kilometres", "300 yards", "2.1 miles". */
    fun spoken(m: Double, u: Units = Prefs.units): String {
        val big = if (u == Units.METRIC) 1000.0 else 1609.344
        if (m >= big) return String.format(Locale.US, "%.1f %s", m / big, if (u == Units.METRIC) "kilometres" else "miles")
        val v = u.shortDist(m)
        val r = if (v < 100) ((v / 10).roundToInt() * 10).coerceAtLeast(10) else (v / 50).roundToInt() * 50
        return "$r ${if (u == Units.METRIC) "metres" else "yards"}"
    }
}
