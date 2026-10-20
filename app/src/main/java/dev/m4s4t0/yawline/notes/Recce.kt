package dev.m4s4t0.yawline.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.m4s4t0.yawline.DriveService
import dev.m4s4t0.yawline.YawApp
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Making notes on a recce: drive the stage slowly, and each call (tapped on the note pad or spoken) is
 * pinned to where the car is. The line driven is kept with the stage, so on the day the co-driver knows
 * where every call is from GPS.
 */
object Recce {
    var active by mutableStateOf(false); private set
    var name by mutableStateOf(""); private set
    val notes = mutableStateListOf<PaceNote>()
    private val pts = ArrayList<Pair<Double, Double>>()
    private var job: Job? = null

    fun start(ctx: Context, stageName: String) {
        if (active) return
        Telemetry.acquire(ctx)
        name = stageName
        notes.clear()
        pts.clear()
        active = true
        DriveService.start(ctx)
        job = YawApp.scope.launch {
            while (isActive) {
                val l = Telemetry.live.value
                if (l.hasFix) synchronized(pts) { pts.add(l.lat to l.lon) }
                delay(250)
            }
        }
    }

    /** Pins [text] at the car's position now. */
    fun add(text: String) {
        val l = Telemetry.live.value
        if (text.isBlank()) return
        notes.add(PaceNote(text.trim(), null, if (l.hasFix) l.lat else Double.NaN, if (l.hasFix) l.lon else Double.NaN))
    }

    /** Adds a word to the last call ("tgt", "dc"...) rather than making a new one. */
    fun amend(word: String) {
        if (notes.isEmpty()) return add(word)
        val last = notes.last()
        notes[notes.size - 1] = last.copy(text = last.text + " " + word)
    }

    fun undo() { if (notes.isNotEmpty()) notes.removeAt(notes.size - 1) }

    /** Saves the recce as a stage, with written distances worked out between the pins. */
    fun finish(): Stage? {
        if (!active) return null
        job?.cancel(); job = null
        active = false
        Telemetry.release()
        val track = Stages.simplify(synchronized(pts) { pts.toList() })
        if (notes.isEmpty()) return null
        var stage = Stage(Stages.newId(), name.ifBlank { "Recce" }, notes.toList(), track = track)
        val pos = Stages.positions(stage)
        val withDist = stage.notes.mapIndexed { i, n ->
            if (n.linked || i == stage.notes.size - 1) n
            else n.copy(distAfter = Stages.roundDist((pos[i + 1] - pos[i]).toInt().coerceAtLeast(0)))
        }
        stage = stage.copy(notes = withDist, startGap = pos.firstOrNull()?.toInt()?.let { Stages.roundDist(it) } ?: 0)
        Stages.save(stage)
        notes.clear()
        return stage
    }

    fun cancel() {
        if (!active) return
        job?.cancel(); job = null
        active = false
        notes.clear()
        Telemetry.release()
    }
}
