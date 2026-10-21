package dev.m4s4t0.yawline.session

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shares a session as GPX (maps, Strava, RaceRender) or the raw CSV (spreadsheets, telemetry tools). */
object Export {
    fun gpx(meta: SessionMeta, samples: List<Sample>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="YAWLINE" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<trk><name>").append(escape(meta.name)).append("</name><trkseg>\n")
        var lastT = -1000L
        for (s in samples) {
            if (s.lat.isNaN() || s.tMs - lastT < 200) continue
            lastT = s.tMs
            sb.append("""<trkpt lat="%.7f" lon="%.7f">""".format(Locale.US, s.lat, s.lon))
            if (!s.altM.isNaN()) sb.append("<ele>%.1f</ele>".format(Locale.US, s.altM))
            sb.append("<time>").append(iso.format(Date(meta.startMs + s.tMs))).append("</time>")
            sb.append("<extensions><speed>%.2f</speed><slip>%.1f</slip><latg>%.2f</latg></extensions>".format(Locale.US, s.speed, s.slip, s.latG))
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun safeName(name: String, id: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { id }
    private fun safeName(meta: SessionMeta) = safeName(meta.name, meta.id)

    fun shareGpx(ctx: Context, meta: SessionMeta) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, safeName(meta) + ".gpx")
        f.writeText(gpx(meta, Sessions.load(meta.id)))
        share(ctx, f, "application/gpx+xml")
    }

    fun shareCsv(ctx: Context, meta: SessionMeta) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, safeName(meta) + ".csv")
        Sessions.samplesFile(meta.id).copyTo(f, overwrite = true)
        share(ctx, f, "text/csv")
    }
}
