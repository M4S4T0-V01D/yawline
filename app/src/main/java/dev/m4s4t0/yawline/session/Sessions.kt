package dev.m4s4t0.yawline.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

data class SessionMeta(
    val id: String,
    val name: String,
    val startMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val maxSpeed: Double,
    val maxSlip: Double,
    val maxLatG: Double,
    val maxRpm: Double,
    val corners: Int,
    val car: String,
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("startMs", startMs).put("durationMs", durationMs)
        .put("distanceM", distanceM).put("maxSpeed", nz(maxSpeed)).put("maxSlip", nz(maxSlip)).put("maxLatG", nz(maxLatG))
        .put("maxRpm", nz(maxRpm)).put("corners", corners).put("car", car)

    companion object {
        private fun nz(v: Double) = if (v.isNaN()) 0.0 else v
        fun from(o: JSONObject) = SessionMeta(
            o.getString("id"), o.optString("name"), o.optLong("startMs"), o.optLong("durationMs"), o.optDouble("distanceM", 0.0),
            o.optDouble("maxSpeed", 0.0), o.optDouble("maxSlip", 0.0), o.optDouble("maxLatG", 0.0), o.optDouble("maxRpm", 0.0),
            o.optInt("corners"), o.optString("car"),
        )
    }
}

/** Recorded sessions on disk: files/sessions/<id>/{meta.json, samples.csv}. */
object Sessions {
    private lateinit var root: File
    private val _all = MutableStateFlow<List<SessionMeta>>(emptyList())
    val all: StateFlow<List<SessionMeta>> = _all

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "sessions").apply { mkdirs() }
        reload()
    }

    fun reload() {
        _all.value = (root.listFiles() ?: emptyArray())
            .mapNotNull { d -> runCatching { SessionMeta.from(JSONObject(File(d, "meta.json").readText())) }.getOrNull() }
            .sortedByDescending { it.startMs }
    }

    fun dir(id: String) = File(root, id)
    fun samplesFile(id: String) = File(dir(id), "samples.csv")

    fun save(meta: SessionMeta) {
        dir(meta.id).mkdirs()
        File(dir(meta.id), "meta.json").writeText(meta.json().toString(2))
        reload()
    }

    fun rename(id: String, name: String) {
        _all.value.firstOrNull { it.id == id }?.let { save(it.copy(name = name)) }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        reload()
    }

    fun load(id: String): List<Sample> {
        val f = samplesFile(id)
        if (!f.exists()) return emptyList()
        return f.useLines { lines -> lines.drop(1).mapNotNull { Sample.parse(it) }.toList() }
    }
}
