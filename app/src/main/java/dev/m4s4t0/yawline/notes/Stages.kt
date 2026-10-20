package dev.m4s4t0.yawline.notes

import android.content.Context
import android.net.Uri
import dev.m4s4t0.yawline.sensors.Telemetry
import dev.m4s4t0.yawline.session.Analysis
import dev.m4s4t0.yawline.session.Sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos

/** A point on a stage's recorded line, with the distance from the start. */
data class TrackPt(val lat: Double, val lon: Double, val d: Double)

/**
 * A stage: its notes, and optionally the line driven on the recce (which lets the co-driver follow
 * your real position instead of counting distance) and photos of the paper notes or roadbook.
 */
data class Stage(
    val id: String,
    val name: String,
    val notes: List<PaceNote>,
    val startGap: Int = 0,
    val track: List<TrackPt> = emptyList(),
    val photos: List<String> = emptyList(),
    val createdMs: Long = System.currentTimeMillis(),
) {
    val lengthM get() = track.lastOrNull()?.d ?: (startGap + notes.sumOf { it.distAfter ?: 0 }).toDouble()

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("startGap", startGap).put("createdMs", createdMs)
        .put("notes", JSONArray().apply {
            notes.forEach { n ->
                put(JSONObject().put("t", n.text).apply {
                    n.distAfter?.let { put("d", it) }
                    if (n.pinned) put("lat", n.lat).put("lon", n.lon)
                })
            }
        })
        .put("track", JSONArray().apply { track.forEach { put(JSONArray().put(it.lat).put(it.lon).put(it.d)) } })
        .put("photos", JSONArray(photos))

    companion object {
        fun from(o: JSONObject): Stage {
            val notes = o.optJSONArray("notes") ?: JSONArray()
            val track = o.optJSONArray("track") ?: JSONArray()
            val photos = o.optJSONArray("photos") ?: JSONArray()
            return Stage(
                id = o.getString("id"), name = o.optString("name"), startGap = o.optInt("startGap"), createdMs = o.optLong("createdMs"),
                notes = (0 until notes.length()).map { i ->
                    val n = notes.getJSONObject(i)
                    PaceNote(n.getString("t"), if (n.has("d")) n.getInt("d") else null, n.optDouble("lat", Double.NaN), n.optDouble("lon", Double.NaN))
                },
                track = (0 until track.length()).map { i -> track.getJSONArray(i).let { TrackPt(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } },
                photos = (0 until photos.length()).map { photos.getString(it) },
            )
        }
    }
}

/** Stages on disk: files/stages/<id>/stage.json plus copied photos. */
object Stages {
    private lateinit var root: File
    private val _all = MutableStateFlow<List<Stage>>(emptyList())
    val all: StateFlow<List<Stage>> = _all

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "stages").apply { mkdirs() }
        reload()
    }

    fun reload() {
        _all.value = (root.listFiles() ?: emptyArray())
            .mapNotNull { d -> runCatching { Stage.from(JSONObject(File(d, "stage.json").readText())) }.getOrNull() }
            .sortedByDescending { it.createdMs }
    }

    fun get(id: String) = _all.value.firstOrNull { it.id == id }
    fun dir(id: String) = File(root, id)

    fun save(s: Stage) {
        dir(s.id).mkdirs()
        File(dir(s.id), "stage.json").writeText(s.json().toString())
        reload()
    }

    fun delete(id: String) { dir(id).deleteRecursively(); reload() }

    fun newId() = "s" + System.currentTimeMillis().toString(36)

    /** Copies picked images into the stage folder (picker grants are temporary). */
    fun addPhotos(ctx: Context, s: Stage, uris: List<Uri>): Stage {
        val names = ArrayList(s.photos)
        dir(s.id).mkdirs()
        for (u in uris) {
            val name = "photo_${System.currentTimeMillis()}_${names.size}.jpg"
            runCatching {
                ctx.contentResolver.openInputStream(u)?.use { input -> File(dir(s.id), name).outputStream().use { input.copyTo(it) } }
                names.add(name)
            }
        }
        val out = s.copy(photos = names)
        save(out)
        return out
    }

    fun photoFile(s: Stage, name: String) = File(dir(s.id), name)

    /**
     * Drafts notes from a recorded run: each detected corner becomes a call at its entry, graded from its
     * radius, with the distance to the next corner. A starting point to correct, not a finished set.
     */
    fun fromSession(name: String, samples: List<Sample>): Stage {
        val sum = Analysis.analyze(samples)
        val track = simplify(samples.filter { !it.lat.isNaN() }.map { it.lat to it.lon })
        val notes = sum.corners.mapIndexed { i, c ->
            val dir = if (c.direction > 0) "L" else "R"
            val g = c.grade
            val call = "$dir$g"
            val mods = buildList { if (c.turnDeg > 110 && g != "HP") add("long") }
            val next = sum.corners.getOrNull(i + 1)
            val gap = next?.let { (it.atM - sum.dist[c.end]).toInt().coerceAtLeast(0) }
            val linked = gap != null && gap < 25
            val s = samples[c.start]
            PaceNote(
                (listOf(call) + mods + if (linked) listOf("into") else emptyList()).joinToString(" "),
                if (linked) null else gap?.let { roundDist(it) },
                s.lat, s.lon,
            )
        }
        return Stage(Stages.newId(), name, notes, startGap = sum.corners.firstOrNull()?.atM?.toInt()?.let { roundDist(it) } ?: 0, track = track)
    }

    /** Distances as written in notes: nearest 10 m under 100, nearest 50 m above. */
    fun roundDist(m: Int) = if (m < 100) ((m + 5) / 10 * 10).coerceAtLeast(10) else (m + 25) / 50 * 50

    /** Keeps a point every 5 m, with the running distance. */
    fun simplify(pts: List<Pair<Double, Double>>): List<TrackPt> {
        val out = ArrayList<TrackPt>()
        var d = 0.0
        var last: Pair<Double, Double>? = null
        for (p in pts) {
            if (last == null) { out.add(TrackPt(p.first, p.second, 0.0)); last = p; continue }
            val step = Telemetry.haversine(last.first, last.second, p.first, p.second)
            if (step < 5) continue
            d += step
            out.add(TrackPt(p.first, p.second, d))
            last = p
        }
        return out
    }

    /**
     * Where along the stage each note sits, in metres. Pinned notes are projected onto the recce line;
     * the rest are placed by adding up the written distances (a linked "into" call counts 15 m).
     */
    fun positions(s: Stage): DoubleArray {
        val out = DoubleArray(s.notes.size)
        var run = s.startGap.toDouble()
        var hint = 0
        for ((i, n) in s.notes.withIndex()) {
            if (n.pinned && s.track.size > 1) {
                val (idx, d) = nearest(s.track, n.lat, n.lon, hint, s.track.size)
                hint = idx
                run = d
            }
            out[i] = run
            run += (n.distAfter ?: if (n.linked) 15 else 40).toDouble()
        }
        return out
    }

    /** Nearest track point to a position, searching [from, to); returns its index and distance-along. */
    fun nearest(track: List<TrackPt>, lat: Double, lon: Double, from: Int, to: Int): Pair<Int, Double> {
        val k = cos(Math.toRadians(lat))
        var best = from.coerceIn(0, track.size - 1)
        var bestD = Double.MAX_VALUE
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(track.size)) {
            val dy = track[i].lat - lat
            val dx = (track[i].lon - lon) * k
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best to track[best].d
    }

    /** Metres between a position and a track point. */
    fun gap(t: TrackPt, lat: Double, lon: Double) = Telemetry.haversine(t.lat, t.lon, lat, lon)
}
