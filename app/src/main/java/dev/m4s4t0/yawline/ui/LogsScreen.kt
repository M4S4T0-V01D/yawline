package dev.m4s4t0.yawline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.notes.Stages
import dev.m4s4t0.yawline.session.Analysis
import dev.m4s4t0.yawline.session.Export
import dev.m4s4t0.yawline.session.Sample
import dev.m4s4t0.yawline.session.Sessions
import dev.m4s4t0.yawline.session.Summary
import dev.m4s4t0.yawline.track.Geo
import dev.m4s4t0.yawline.track.TrackKind
import dev.m4s4t0.yawline.track.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun LogsScreen(onOpen: (String) -> Unit) {
    val all by Sessions.all.collectAsState()
    val u = Prefs.units
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No sessions yet", style = TextStyle(color = Yaw.text, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(8.dp))
            Hint("Tap REC at the top of any screen to record a run: the line, speeds, drift angles, G-forces and every engine value, 10 times a second.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Sessions") }
        items(all, key = { it.id }) { m ->
            Panel(Modifier.fillMaxWidth(), onClick = { onOpen(m.id) }) {
                Text(m.name, style = TextStyle(color = Yaw.text, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.startMs)) + if (m.car.isNotEmpty()) " · ${m.car}" else "", style = LabelStyle)
                Spacer(Modifier.height(10.dp))
                Row {
                    Readout("Time", Fmt.duration(m.durationMs), "", Modifier.weight(1f), size = 20.sp)
                    Readout("Dist", Fmt.n(u.dist(m.distanceM), 2), u.distLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Top", Fmt.n(u.speed(m.maxSpeed), 0), u.speedLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Drift", Fmt.angle(m.maxSlip), "", Modifier.weight(1f), size = 20.sp, color = Yaw.accent)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private enum class Metric(val label: String) { SPEED("Speed"), DRIFT("Drift"), LATG("Lat G"), THROTTLE("Throttle"), RPM("RPM") }

@Composable
fun ReplayScreen(id: String, onBack: () -> Unit, onTrack: (String) -> Unit, onStage: (String) -> Unit) {
    val ctx = LocalContext.current
    val meta = Sessions.all.collectAsState().value.firstOrNull { it.id == id }
    var samples by remember { mutableStateOf<List<Sample>?>(null) }
    var summary by remember { mutableStateOf<Summary?>(null) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val s = Sessions.load(id)
            summary = Analysis.analyze(s)
            samples = s
        }
    }
    var cursor by remember { mutableIntStateOf(0) }
    var metric by remember { mutableStateOf(Metric.SPEED) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val u = Prefs.units

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Yaw.text) }
            Text(meta?.name ?: "Session", style = TextStyle(color = Yaw.text, fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            IconButton({ renaming = true }) { Icon(Icons.Filled.Edit, "Rename", tint = Yaw.dim) }
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, "Delete", tint = Yaw.dim) }
        }
        val s = samples
        val sum = summary
        if (s == null || sum == null || meta == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint("Loading…") }
            return
        }
        val pts = remember(s) { s.indices.filter { !s[it].lat.isNaN() } }
        val route = remember(pts) { pts.map { s[it].lat to s[it].lon } }
        val colors = remember(pts, metric, Yaw.palette) {
            val vals = pts.map { i ->
                val x = s[i]
                when (metric) {
                    Metric.SPEED -> x.speed / maxOf(sum.maxSpeed, 1.0)
                    Metric.DRIFT -> abs(x.slip) / 40.0
                    Metric.LATG -> abs(x.latG) / 1.2
                    Metric.THROTTLE -> if (x.throttle.isNaN()) 0.0 else x.throttle / 100
                    Metric.RPM -> if (x.rpm.isNaN()) 0.0 else x.rpm / Prefs.shiftRpm
                }
            }
            vals.map { v -> if (v.isNaN()) Yaw.dim else Yaw.heat(v.toFloat().coerceIn(0f, 1f)) }
        }
        val cur = s.getOrNull(cursor) ?: s.first()
        val cursorPt = pts.indexOfFirst { it >= cursor }.let { if (it < 0) pts.size - 1 else it }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Panel(Modifier.fillMaxWidth(), padding = 8.dp) {
                ChipRow(Metric.entries.map { it.label }, metric.ordinal, Modifier.horizontalScroll(rememberScrollState())) { metric = Metric.entries[it] }
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    if (route.size > 1) RouteView(route, Modifier.fillMaxSize(), colors, cursorPt, sum.corners.map { c -> pts.indexOfFirst { it >= c.start } })
                    else Hint("No GPS in this session.", Modifier.align(Alignment.Center))
                }
            }
            Slider(cursor.toFloat(), { cursor = it.toInt() }, valueRange = 0f..(s.size - 1).coerceAtLeast(1).toFloat())
            Panel(Modifier.fillMaxWidth()) {
                Row {
                    Readout("T", Fmt.lapTime(cur.tMs), "", Modifier.weight(1.2f), size = 20.sp)
                    Readout("Speed", Fmt.n(u.speed(cur.speed), 0), u.speedLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Drift", Fmt.angle(cur.slip), "", Modifier.weight(1f), size = 20.sp, color = Yaw.accent)
                    Readout("Lat", Fmt.n(abs(cur.latG), 2), "g", Modifier.weight(1f), size = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Readout("RPM", Fmt.n(cur.rpm, 0), "", Modifier.weight(1.2f), size = 20.sp)
                    Readout("Throttle", Fmt.n(cur.throttle, 0), "%", Modifier.weight(1f), size = 20.sp)
                    Readout("Boost", Fmt.n(u.press(cur.boostKpa), 2), u.pressLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Water", Fmt.n(u.temp(cur.coolant), 0), u.tempLabel, Modifier.weight(1f), size = 20.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row {
                    Text("SPEED", style = LabelStyle.copy(color = Yaw.accent))
                    Spacer(Modifier.width(10.dp))
                    Text("DRIFT", style = LabelStyle.copy(color = Yaw.accent2))
                }
                val step = maxOf(1, s.size / 600)
                val sp = remember(s) { s.filterIndexed { i, _ -> i % step == 0 }.map { it.speed.toFloat() } }
                val dr = remember(s) { s.filterIndexed { i, _ -> i % step == 0 }.map { abs(it.slip).toFloat() } }
                LineChart(sp, dr, Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp), cursor / step)
            }

            SectionTitle("Summary")
            Panel(Modifier.fillMaxWidth()) {
                Row {
                    Readout("Time", Fmt.duration(sum.durationMs), "", Modifier.weight(1f), size = 20.sp)
                    Readout("Distance", Fmt.n(u.dist(sum.distanceM), 2), u.distLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Average", Fmt.n(u.speed(sum.avgSpeed), 0), u.speedLabel, Modifier.weight(1f), size = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Readout("Top speed", Fmt.n(u.speed(sum.maxSpeed), 0), u.speedLabel, Modifier.weight(1f), size = 20.sp)
                    Readout("Max drift", Fmt.angle(sum.maxSlip), "", Modifier.weight(1f), size = 20.sp, color = Yaw.accent)
                    Readout("Max lat", Fmt.n(sum.maxLatG, 2), "g", Modifier.weight(1f), size = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Readout("Braking", Fmt.n(sum.maxBrakeG, 2), "g", Modifier.weight(1f), size = 20.sp)
                    Readout("Max RPM", Fmt.n(sum.maxRpm, 0), "", Modifier.weight(1f), size = 20.sp)
                    Readout("Max water", Fmt.n(u.temp(sum.maxCoolant), 0), u.tempLabel, Modifier.weight(1f), size = 20.sp)
                }
            }

            SectionTitle("Corners (${sum.corners.size})")
            if (sum.corners.isEmpty()) Hint("No corners detected — they need GPS and steady turning above about 15 km/h.")
            for (c in sum.corners) {
                val best = c.maxSlip >= sum.maxSlip - 0.5 && c.maxSlip > 5
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (c.start <= cursor && cursor <= c.end) Yaw.panelAlt else Yaw.panel)
                        .clickable { cursor = c.apex }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${c.index}", style = numStyle(16.sp, Yaw.dim), modifier = Modifier.width(28.dp))
                    Text((if (c.direction > 0) "L" else "R") + c.grade, style = numStyle(20.sp, Yaw.accent), modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.n(u.speed(c.entrySpeed), 0)} › ${Fmt.n(u.speed(c.minSpeed), 0)} › ${Fmt.n(u.speed(c.exitSpeed), 0)} ${u.speedLabel}",
                            style = TextStyle(color = Yaw.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        Text("${c.turnDeg.toInt()}° turn · r ${c.radiusM.toInt()} m · ${Fmt.n(c.maxLatG, 2)} g", style = LabelStyle)
                    }
                    Text(Fmt.angle(c.maxSlip), style = numStyle(20.sp, if (best) Yaw.accent2 else Yaw.text))
                }
            }

            SectionTitle("Export")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("GPX", Modifier.weight(1f), Icons.Filled.Share, filled = false) { Export.shareGpx(ctx, meta) }
                BigButton("CSV", Modifier.weight(1f), Icons.Filled.Share, filled = false) { Export.shareCsv(ctx, meta) }
            }
            Spacer(Modifier.height(10.dp))
            BigButton("MAKE PACE NOTES FROM THIS RUN", Modifier.fillMaxWidth(), Icons.Filled.RecordVoiceOver, color = Yaw.accent2) {
                val st = Stages.fromSession(meta.name, s)
                Stages.save(st)
                onStage(st.id)
            }
            Hint("Turns every detected corner into a graded call, pinned to the road. A first draft to correct on the recce.", Modifier.padding(vertical = 8.dp))
            BigButton("SAVE AS TRACK", Modifier.fillMaxWidth(), Icons.Filled.Route, filled = false) {
                onTrack(Tracks.create(meta.name, TrackKind.GPS, Geo.thin(route, 5.0)).id)
            }
            Hint("Keeps the line driven in Tracks, to navigate it again.", Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (renaming && meta != null) {
        var name by remember { mutableStateOf(meta.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ Sessions.rename(id, name.trim().ifEmpty { meta.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this session?") },
            text = { Text("The recording is removed from the phone. Export it first if you want to keep it.") },
            confirmButton = { TextButton({ deleting = false; Sessions.delete(id); onBack() }) { Text("Delete", color = Yaw.bad) } },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        )
    }
}
