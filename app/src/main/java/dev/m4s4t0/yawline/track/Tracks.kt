package dev.m4s4t0.yawline.track

import android.content.Context
import android.net.Uri
import dev.m4s4t0.yawline.notes.TrackPt
import dev.m4s4t0.yawline.session.Export
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class TrackKind(val label: String) { GPS("Recorded"), DRAWN("Drawn"), IMPORTED("Imported") }

/** A saved line: recorded by driving it, drawn on the map, or imported from GPX. */
data class Track(
    val id: String,
    val name: String,
    val kind: TrackKind,
    val pts: List<TrackPt>,
    val createdMs: Long = System.currentTimeMillis(),
) {
    val lengthM get() = pts.lastOrNull()?.d ?: 0.0
    val latLon get() = pts.map { it.lat to it.lon }

    /** The same line driven the other way. */
    fun reversed() = copy(pts = Geo.measure(latLon.reversed()))

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("createdMs", createdMs)
        .put("pts", JSONArray().apply { pts.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })

    companion object {
        fun from(o: JSONObject): Track {
            val a = o.optJSONArray("pts") ?: JSONArray()
            return Track(
                id = o.getString("id"), name = o.optString("name"), createdMs = o.optLong("createdMs"),
                kind = runCatching { TrackKind.valueOf(o.optString("kind")) }.getOrDefault(TrackKind.DRAWN),
                pts = Geo.measure((0 until a.length()).map { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }),
            )
        }
    }
}

/** Tracks on disk: files/tracks/<id>.json. */
object Tracks {
    private lateinit var root: File
    private val _all = MutableStateFlow<List<Track>>(emptyList())
    val all: StateFlow<List<Track>> = _all

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "tracks").apply { mkdirs() }
        reload()
    }

    fun reload() {
        _all.value = (root.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { Track.from(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.createdMs }
    }

    fun get(id: String) = _all.value.firstOrNull { it.id == id }

    fun save(t: Track) {
        File(root, "${t.id}.json").writeText(t.json().toString())
        reload()
    }

    fun rename(id: String, name: String) { get(id)?.let { save(it.copy(name = name)) } }

    fun delete(id: String) { File(root, "$id.json").delete(); reload() }

    fun newId() = "t" + System.currentTimeMillis().toString(36)

    fun create(name: String, kind: TrackKind, pts: List<Pair<Double, Double>>): Track =
        Track(newId(), name, kind, Geo.measure(pts)).also { save(it) }

    fun gpx(t: Track): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="YAWLINE" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<trk><name>").append(Export.escape(t.name)).append("</name><trkseg>\n")
        for (p in t.pts) sb.append("""<trkpt lat="%.7f" lon="%.7f"/>""".format(Locale.US, p.lat, p.lon)).append('\n')
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }

    fun shareGpx(ctx: Context, t: Track) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, Export.safeName(t.name, t.id) + ".gpx")
        f.writeText(gpx(t))
        Export.share(ctx, f, "application/gpx+xml")
    }

    /** Reads a GPX file picked by the user; returns the new track, or null if it held no points. */
    fun import(ctx: Context, uri: Uri): Track? {
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() } }.getOrNull() ?: return null
        val (name, pts) = parseGpx(text)
        if (pts.size < 2) return null
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported line"
        return create(name ?: fallback, TrackKind.IMPORTED, Geo.thin(pts, 3.0))
    }

    private val pointTag = Regex("""<(trkpt|rtept|wpt)\b([^>]*)>""")
    private val latAttr = Regex("""\blat\s*=\s*["']([-+0-9.eE]+)["']""")
    private val lonAttr = Regex("""\blon\s*=\s*["']([-+0-9.eE]+)["']""")
    private val nameTag = Regex("""<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The name and points of a GPX file. Track points win over route points, which win over loose
     * waypoints, so a file with a track and a few marked spots gives just the track.
     */
    fun parseGpx(text: String): Pair<String?, List<Pair<Double, Double>>> {
        val byTag = HashMap<String, ArrayList<Pair<Double, Double>>>()
        for (m in pointTag.findAll(text)) {
            val attrs = m.groupValues[2]
            val lat = latAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = lonAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            byTag.getOrPut(m.groupValues[1]) { ArrayList() }.add(lat to lon)
        }
        val pts = byTag["trkpt"] ?: byTag["rtept"] ?: byTag["wpt"] ?: emptyList()
        val name = nameTag.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
        return name to pts
    }
}
