package dev.m4s4t0.yawline.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.sensors.Telemetry
import dev.m4s4t0.yawline.track.Geo
import dev.m4s4t0.yawline.track.Guide
import dev.m4s4t0.yawline.track.Navigator
import dev.m4s4t0.yawline.track.TrackKind
import dev.m4s4t0.yawline.track.TrackRecorder
import dev.m4s4t0.yawline.track.Tracks
import dev.m4s4t0.yawline.track.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** "1.24 km" for long distances, "350 m" for short ones, in the user's units. */
private fun distText(m: Double): String {
    val u = Prefs.units
    return if (m >= 1000) "${Fmt.n(u.dist(m), 2)} ${u.distLabel}" else "${Fmt.n(u.shortDist(m), 0)} ${u.shortDistLabel}"
}

private fun defaultName(prefix: String) = prefix + " " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())

@Composable
fun TracksScreen(onOpen: (String) -> Unit, onDraw: () -> Unit) {
    val ctx = LocalContext.current
    val all by Tracks.all.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = Tracks.import(ctx, uri)
            if (t != null) onOpen(t.id) else Toast.makeText(ctx, "No track points in that file", Toast.LENGTH_SHORT).show()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("New line")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("RECORD", Modifier.weight(1f), Icons.Filled.FiberManualRecord, color = Yaw.bad) { TrackRecorder.start(ctx) }
                BigButton("DRAW", Modifier.weight(1f), Icons.Filled.Gesture) { onDraw() }
            }
            Spacer(Modifier.height(10.dp))
            BigButton("IMPORT GPX", Modifier.fillMaxWidth(), Icons.Filled.FileOpen, filled = false, height = 44.dp) {
                runCatching { pick.launch(arrayOf("*/*")) }
            }
            Hint(
                "Record lays the line down as you drive it. Draw lets you plot where you want to go on the map, " +
                    "point by point or freehand. Open any saved line to navigate it.",
                Modifier.padding(top = 8.dp),
            )
        }
        item { SectionTitle("Saved lines") }
        if (all.isEmpty()) item { Hint("Nothing saved yet.") }
        items(all, key = { it.id }) { t ->
            Panel(Modifier.fillMaxWidth(), onClick = { onOpen(t.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Yaw.panelAlt)) {
                        val pts = remember(t) { t.latLon }
                        if (pts.size > 1) RouteView(pts, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = TextStyle(color = Yaw.text, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text("${t.kind.label} · ${distText(t.lengthM)}", style = LabelStyle)
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t.createdMs)), style = LabelStyle)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ---------------------------------------------------------------- recording

/** Full screen while a track records: the line so far on the map and how long it is. */
@Composable
fun TrackRecordScreen() {
    val ctx = LocalContext.current
    val live by Telemetry.live.collectAsState()
    val pts by TrackRecorder.pts.collectAsState()
    val map = rememberMapState()
    var saving by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        map.follow = true
        while (true) { now = TrackRecorder.elapsedMs; delay(500) }
    }
    LaunchedEffect(live.hasFix) { if (live.hasFix && map.zoom < 14) map.moveTo(live.lat, live.lon, 16.0) }
    BackHandler { saving = true }

    Column(Modifier.fillMaxSize().background(Yaw.bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("RECORDING", Yaw.bad)
            Spacer(Modifier.weight(1f))
            Text(if (live.hasFix) "GPS ±${Fmt.n(live.accM, 0)} m" else "Waiting for GPS…", style = LabelStyle)
        }
        Spacer(Modifier.height(8.dp))
        MapView(map, Modifier.fillMaxWidth().weight(1f), lines = listOf(MapLine(pts, Yaw.bad)), me = live)
        Spacer(Modifier.height(10.dp))
        Row {
            Readout("Dist", distText(TrackRecorder.distanceM).substringBefore(' '), distText(TrackRecorder.distanceM).substringAfter(' '), Modifier.weight(1f))
            Readout("Time", Fmt.duration(now), "", Modifier.weight(1f))
            Readout("Points", pts.size.toString(), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("DISCARD", Modifier.weight(1f), Icons.Filled.Close, color = Yaw.dim, filled = false, height = 60.dp) { discarding = true }
            BigButton("STOP & SAVE", Modifier.weight(1.6f), Icons.Filled.Stop, color = Yaw.bad, height = 60.dp) { saving = true }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(defaultName("Track")) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save track") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    saving = false
                    if (TrackRecorder.stop(name.trim().ifEmpty { defaultName("Track") }) == null) {
                        Toast.makeText(ctx, "Too short to save", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ saving = false }) { Text("Keep recording") } },
        )
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Discard this track?") },
            confirmButton = { TextButton({ discarding = false; TrackRecorder.cancel() }) { Text("Discard", color = Yaw.bad) } },
            dismissButton = { TextButton({ discarding = false }) { Text("Keep recording") } },
        )
    }
}

// ---------------------------------------------------------------- drawing

/**
 * Plots a line on the map: tap to drop points, or switch to freehand and draw with a finger. Opened
 * with an [id] it edits that line; [onDone] gets the saved id, or null if nothing was saved.
 */
@Composable
fun TrackEditorScreen(id: String?, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val live by Telemetry.live.collectAsState()
    val original = remember(id) { id?.let { Tracks.get(it) } }
    var pts by remember { mutableStateOf(original?.latLon ?: emptyList()) }
    var history by remember { mutableStateOf(listOf<List<Pair<Double, Double>>>()) }
    var mode by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val map = rememberMapState()
    val changed = pts != (original?.latLon ?: emptyList<Pair<Double, Double>>())
    val length = remember(pts) { Geo.measure(pts).lastOrNull()?.d ?: 0.0 }

    fun edit(next: List<Pair<Double, Double>>) { history = (history + listOf(pts)).takeLast(100); pts = next }
    fun save(name: String) {
        val t = if (original != null) original.copy(name = name, pts = Geo.measure(pts)).also { Tracks.save(it) }
        else Tracks.create(name, TrackKind.DRAWN, pts)
        onDone(t.id)
    }

    LaunchedEffect(Unit) { if (pts.isNotEmpty()) map.fit(pts) }
    LaunchedEffect(live.hasFix) { if (live.hasFix && !map.touched) map.moveTo(live.lat, live.lon, 15.0) }
    BackHandler { if (changed) leaving = true else onDone(null) }

    Column(Modifier.fillMaxSize().background(Yaw.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (changed) leaving = true else onDone(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Yaw.text) }
            Column(Modifier.weight(1f)) {
                Text(original?.name ?: "Draw a line", style = TextStyle(color = Yaw.text, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text("${distText(length)} · ${pts.size} points", style = LabelStyle)
            }
            BigButton("SAVE", height = 40.dp, color = Yaw.ok) { if (pts.size >= 2) { if (original != null) save(original.name) else naming = true } }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            ChipRow(listOf("TAP POINTS", "FREEHAND"), mode) { mode = it }
            Hint(
                if (mode == 0) "Tap the map to add the next point. Drag to move the map, pinch to zoom."
                else "Draw with one finger. Two fingers move and zoom the map. Taps still add a point.",
                Modifier.padding(vertical = 6.dp),
            )
            MapView(
                map, Modifier.fillMaxWidth().weight(1f),
                lines = listOf(MapLine(pts, Yaw.accent, ends = true)),
                dots = if (mode == 0 && pts.size <= 400) pts.map { MapDot(it.first, it.second, Yaw.accent, 4.dp) } else emptyList(),
                me = live,
                onTap = { lat, lon -> edit(pts + (lat to lon)) },
                freehand = mode == 1,
                onStroke = { s -> edit(pts + Geo.thin(s, 2.0)) },
            )
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("UNDO", Modifier.weight(1f), filled = false) {
                history.lastOrNull()?.let { pts = it; history = history.dropLast(1) }
            }
            BigButton("+ ME", Modifier.weight(1f), filled = false, color = Yaw.accent2) {
                if (live.hasFix) edit(pts + (live.lat to live.lon))
                else Toast.makeText(ctx, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            }
            BigButton("FLIP", Modifier.weight(1f), filled = false) { if (pts.size > 1) edit(pts.reversed()) }
            BigButton("CLEAR", Modifier.weight(1f), filled = false, color = Yaw.bad) { if (pts.isNotEmpty()) edit(emptyList()) }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf(defaultName("Line")) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save line") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton({ naming = false; save(name.trim().ifEmpty { defaultName("Line") }) }) { Text("Save") } },
            dismissButton = { TextButton({ naming = false }) { Text("Cancel") } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave without saving?") },
            confirmButton = { TextButton({ leaving = false; onDone(null) }) { Text("Discard", color = Yaw.bad) } },
            dismissButton = { TextButton({ leaving = false }) { Text("Keep editing") } },
        )
    }
}

// ---------------------------------------------------------------- one track

@Composable
fun TrackScreen(id: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val t = Tracks.all.collectAsState().value.firstOrNull { it.id == id }
    val live by Telemetry.live.collectAsState()
    val map = rememberMapState()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf<List<Turn>?>(null) }
    LaunchedEffect(t) {
        if (t != null) {
            map.fit(t.latLon)
            turns = withContext(Dispatchers.Default) { Guide.findTurns(t.pts) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Yaw.text) }
            Text(t?.name ?: "Track", style = TextStyle(color = Yaw.text, fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
            IconButton({ renaming = true }) { Icon(Icons.Filled.Edit, "Rename", tint = Yaw.dim) }
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, "Delete", tint = Yaw.dim) }
        }
        if (t == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint("Track not found.") }
            return
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            MapView(map, Modifier.fillMaxWidth().weight(1f), lines = listOf(MapLine(t.latLon, Yaw.accent, ends = true)), me = live)
            Spacer(Modifier.height(10.dp))
            Row {
                Readout("Length", distText(t.lengthM).substringBefore(' '), distText(t.lengthM).substringAfter(' '), Modifier.weight(1f), size = 22.sp)
                Readout("Turns", turns?.size?.toString() ?: "…", "", Modifier.weight(1f), size = 22.sp)
                Readout("Type", t.kind.label, "", Modifier.weight(1f), size = 22.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("NAVIGATE", Modifier.weight(1.6f), Icons.Filled.Navigation, height = 60.dp) { Navigator.start(ctx, t) }
                BigButton("REVERSED", Modifier.weight(1f), Icons.Filled.SwapHoriz, filled = false, height = 60.dp) { Navigator.start(ctx, t, reverse = true) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("EDIT LINE", Modifier.weight(1f), Icons.Filled.Gesture, filled = false, color = Yaw.accent2, height = 44.dp) { onEdit() }
                BigButton("GPX", Modifier.weight(1f), Icons.Filled.Share, filled = false, height = 44.dp) { Tracks.shareGpx(ctx, t) }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (renaming && t != null) {
        var name by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ Tracks.rename(id, name.trim().ifEmpty { t.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this line?") },
            text = { Text("It's removed from the phone. Share it as GPX first if you want to keep it.") },
            confirmButton = { TextButton({ deleting = false; Tracks.delete(id); onBack() }) { Text("Delete", color = Yaw.bad) } },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- navigating

private fun turnIcon(t: Turn): ImageVector {
    val a = abs(t.deg)
    return when {
        a >= 135 -> if (t.left) Icons.Filled.UTurnLeft else Icons.Filled.UTurnRight
        a >= 60 -> if (t.left) Icons.Filled.TurnLeft else Icons.Filled.TurnRight
        else -> if (t.left) Icons.Filled.TurnSlightLeft else Icons.Filled.TurnSlightRight
    }
}

/** Full screen while navigating: the next turn big at the top, the map following the car, STOP. */
@Composable
fun NavScreen() {
    val t = Navigator.track ?: return
    val live by Telemetry.live.collectAsState()
    val u = Prefs.units
    val map = rememberMapState()
    val all = remember(t) { t.latLon }
    LaunchedEffect(Unit) { map.fit(all); map.follow = true }
    LaunchedEffect(live.hasFix) { if (live.hasFix && map.follow) map.moveTo(live.lat, live.lon, max(map.zoom, 16.0)) }
    BackHandler {}

    // Split the line where the car is: driven part dimmed, the rest bright.
    val split = remember(t, (Navigator.alongM / 10).toInt()) { t.pts.indexOfFirst { it.d > Navigator.alongM }.let { if (it < 0) t.pts.size else it } }
    val done = all.take(split) + listOfNotNull(Navigator.target.takeIf { Navigator.joined })
    val ahead = listOfNotNull(Navigator.target.takeIf { Navigator.joined }) + all.drop(split)
    val lines = buildList {
        add(MapLine(done, Yaw.dim))
        add(MapLine(ahead, Yaw.accent, ends = false))
        val tgt = Navigator.target
        if (live.hasFix && tgt != null && (!Navigator.joined || Navigator.offRoute)) add(MapLine(listOf(live.lat to live.lon, tgt), Yaw.warn, 3.dp, dashed = true))
    }
    val dots = listOf(MapDot(all.last().first, all.last().second, Yaw.bad, 7.dp))

    Column(Modifier.fillMaxSize().background(Yaw.bg).padding(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            val next = Navigator.next
            val (icon, big, small, color) = when {
                Navigator.arrived -> Quad(Icons.Filled.SportsScore, "ARRIVED", t.name, Yaw.ok)
                !Navigator.joined -> Quad(Icons.Filled.Navigation, distText(Navigator.offM.takeIf { !it.isNaN() } ?: 0.0), "Head to the line", Yaw.warn)
                Navigator.offRoute -> Quad(Icons.Filled.Navigation, "OFF ROUTE", "${distText(Navigator.offM)} off the line", Yaw.bad)
                next == null -> Quad(Icons.Filled.Straight, distText(Navigator.toGoM), "to the finish", Yaw.accent)
                else -> Quad(turnIcon(next), distText(max(0.0, Navigator.toTurnM)), next.call.uppercase(), Yaw.accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(big, style = numStyle(40.sp, color), maxLines = 1)
                    Text(small, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Yaw.text), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MapView(map, Modifier.fillMaxWidth().weight(1f), lines = lines, dots = dots, me = live)
        Spacer(Modifier.height(10.dp))
        Row {
            Readout("To go", distText(Navigator.toGoM).substringBefore(' '), distText(Navigator.toGoM).substringAfter(' '), Modifier.weight(1f))
            Readout("Speed", Fmt.n(u.speed(live.speed), 0), u.speedLabel, Modifier.weight(1f))
            Readout("Off line", if (Navigator.offM.isNaN()) "--" else Fmt.n(u.shortDist(Navigator.offM), 0), u.shortDistLabel, Modifier.weight(1f),
                color = if (Navigator.offM > 25) Yaw.warn else Yaw.text)
        }
        Spacer(Modifier.height(10.dp))
        BigButton("STOP", Modifier.fillMaxWidth(), Icons.Filled.Close, color = Yaw.bad, filled = false, height = 56.dp) { Navigator.stop() }
    }
}

private data class Quad(val icon: ImageVector, val big: String, val small: String, val color: Color)
