package dev.m4s4t0.yawline.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.notes.CoDriver
import dev.m4s4t0.yawline.notes.NoteParser
import dev.m4s4t0.yawline.notes.PaceNote
import dev.m4s4t0.yawline.notes.Recce
import dev.m4s4t0.yawline.notes.Stage
import dev.m4s4t0.yawline.notes.Stages
import dev.m4s4t0.yawline.notes.Voice
import dev.m4s4t0.yawline.sensors.Telemetry
import java.io.File

// ---------------------------------------------------------------- list and import

@Composable
fun StagesScreen(shared: String?, onSharedUsed: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val all by Stages.all.collectAsState()
    val u = Prefs.units
    var importing by remember { mutableStateOf<String?>(null) }
    var recceName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shared) { if (shared != null) { importing = shared; onSharedUsed() } }
    val openText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importing = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        SectionTitle("New stage")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("TYPE / PASTE", Modifier.weight(1f), Icons.Filled.Add) { importing = "" }
            BigButton("FILE", Modifier.weight(1f), Icons.Filled.Description, filled = false) { openText.launch(arrayOf("text/*")) }
        }
        Spacer(Modifier.height(10.dp))
        BigButton("RECCE: MAKE NOTES ON THE ROAD", Modifier.fillMaxWidth(), Icons.Filled.Flag, color = Yaw.accent2) { recceName = "" }
        Hint(
            "From a printed sheet: photograph it, open the photo in Google Lens, \"Copy text\", then paste here — or share the text straight to YAWLINE. " +
                "From Google Sheets: download as .csv or .txt and use FILE. Photos of the sheet can also be kept with each stage for reference.",
            Modifier.padding(vertical = 8.dp),
        )

        SectionTitle("Stages")
        if (all.isEmpty()) Hint("No stages yet.")
        for (s in all) {
            Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onOpen(s.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = TextStyle(color = Yaw.text, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                        Text(
                            "${s.notes.size} notes · ${Fmt.n(u.dist(s.lengthM), 2)} ${u.distLabel}" +
                                (if (s.track.isNotEmpty()) " · GPS-pinned" else "") + (if (s.photos.isNotEmpty()) " · ${s.photos.size} photos" else ""),
                            style = LabelStyle,
                        )
                    }
                    BigButton("", icon = Icons.Filled.PlayArrow, height = 44.dp) { CoDriver.start(ctx, s) }
                }
                Text(s.notes.take(6).joinToString("  ·  ") { it.text }, style = TextStyle(color = Yaw.dim, fontSize = 13.sp), maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    importing?.let { start -> ImportDialog(start, onDismiss = { importing = null }) { id -> importing = null; onOpen(id) } }
    recceName?.let {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { recceName = null },
            title = { Text("Start a recce") },
            text = {
                Column {
                    Text("Drive the stage slowly and tap each call as you reach it. Every note is pinned to where you were, and distances are measured for you.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Stage name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ recceName = null; Recce.start(ctx, name.ifBlank { "Recce" }) }) { Text("Start") } },
            dismissButton = { TextButton({ recceName = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ImportDialog(start: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(start) }
    val sheet = remember(text) { NoteParser.parseSheet(text) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Yaw.bg).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New stage", style = TextStyle(color = Yaw.text, fontSize = 20.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Yaw.text) }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                text, { text = it }, label = { Text("Notes, one or more per line") },
                placeholder = { Text("80\nL4 100\nR3- tgt dc into\nL5 opn 150\nHP R !! 50\nfin") },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            Text("PREVIEW · ${sheet.notes.size} CALLS" + if (sheet.startGap > 0) " · START ${sheet.startGap} m" else "", style = LabelStyle)
            Column(Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState())) {
                for (n in sheet.notes) {
                    Text(NoteParser.speak(n) + (n.distAfter?.let { " · $it" } ?: ""), style = TextStyle(color = Yaw.text, fontSize = 14.sp),
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            BigButton("SAVE STAGE", Modifier.fillMaxWidth()) {
                if (sheet.notes.isEmpty()) return@BigButton
                val s = Stage(Stages.newId(), name.ifBlank { "Stage ${Stages.all.value.size + 1}" }, sheet.notes, sheet.startGap)
                Stages.save(s)
                onSaved(s.id)
            }
        }
    }
}

// ---------------------------------------------------------------- one stage

@Composable
fun StageScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by Stages.all.collectAsState()
    val stage = all.firstOrNull { it.id == id }
    if (stage == null) { LaunchedEffect(Unit) { onBack() }; return }
    val u = Prefs.units
    var edit by remember { mutableIntStateOf(-2) } // -2 none, -1 new at end, else index
    var insertAt by remember { mutableIntStateOf(-1) }
    var photo by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) Stages.addPhotos(ctx, stage, uris)
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Yaw.text) }
            Text(stage.name, style = TextStyle(color = Yaw.text, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).clickable { renaming = true })
            IconButton({ deleting = true }) { Icon(Icons.Filled.Delete, "Delete", tint = Yaw.dim) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                BigButton("START CO-DRIVER", Modifier.fillMaxWidth(), Icons.Filled.PlayArrow) { CoDriver.start(ctx, stage) }
                Hint(
                    if (stage.track.isNotEmpty()) "Notes are pinned to GPS: calls follow where you are on the road. SYNC at the start line if needed."
                    else "Calls are timed by distance driven from the start. Press SYNC as you cross the start line.",
                    Modifier.padding(vertical = 8.dp),
                )
                if (stage.track.size > 1) {
                    Panel(Modifier.fillMaxWidth().height(200.dp), padding = 6.dp) {
                        RouteView(stage.track.map { it.lat to it.lon }, Modifier.fillMaxSize(),
                            marks = Stages.positions(stage).map { p -> stage.track.indexOfFirst { it.d >= p }.coerceAtLeast(0) })
                    }
                }
                SectionTitle("Photos") {
                    TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Filled.AddPhotoAlternate, null, tint = Yaw.accent); Spacer(Modifier.width(4.dp)); Text("ADD", color = Yaw.accent)
                    }
                }
                if (stage.photos.isEmpty()) Hint("Keep pictures of the printed notes or the roadbook here.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in stage.photos) Thumb(Stages.photoFile(stage, p)) { photo = p }
                }
                SectionTitle("Notes (${stage.notes.size})") {
                    TextButton({ edit = -1 }) { Icon(Icons.Filled.Add, null, tint = Yaw.accent); Text("ADD", color = Yaw.accent) }
                }
                if (stage.startGap > 0) Text("Start · ${stage.startGap} m", style = LabelStyle, modifier = Modifier.padding(bottom = 6.dp))
            }
            itemsIndexed(stage.notes) { i, n ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Yaw.panel)
                        .clickable { edit = i }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = numStyle(14.sp, Yaw.dim), modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.text, style = TextStyle(color = Yaw.text, fontSize = 18.sp, fontWeight = FontWeight.Black))
                        Text(NoteParser.speak(n), style = LabelStyle.copy(letterSpacing = 0.sp))
                    }
                    Text(
                        n.distAfter?.let { "${it}" } ?: if (n.linked) "›" else "",
                        style = numStyle(16.sp, Yaw.accent2),
                    )
                    IconButton({ Voice.init(ctx); Voice.say(NoteParser.speak(n), urgent = true) }) { Icon(Icons.Filled.VolumeUp, "Say", tint = Yaw.dim) }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Hint("Tap a note to edit, delete or insert one before it. Distances are in metres, as on most sheets.")
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (edit != -2) {
        val existing = stage.notes.getOrNull(edit)
        NoteEditor(
            existing,
            onDismiss = { edit = -2 },
            onSave = { note ->
                val l = stage.notes.toMutableList()
                if (existing != null) l[edit] = note.copy(lat = existing.lat, lon = existing.lon) else l.add(note)
                Stages.save(stage.copy(notes = l)); edit = -2
            },
            onDelete = existing?.let { { val l = stage.notes.toMutableList(); l.removeAt(edit); Stages.save(stage.copy(notes = l)); edit = -2 } },
            onInsertBefore = existing?.let { { insertAt = edit; edit = -2 } },
        )
    }
    if (insertAt >= 0) {
        NoteEditor(null, onDismiss = { insertAt = -1 }, onSave = { note ->
            val l = stage.notes.toMutableList(); l.add(insertAt.coerceAtMost(l.size), note)
            Stages.save(stage.copy(notes = l)); insertAt = -1
        })
    }
    photo?.let { p ->
        PhotoViewer(Stages.photoFile(stage, p), onDismiss = { photo = null }, onDelete = {
            Stages.photoFile(stage, p).delete(); Stages.save(stage.copy(photos = stage.photos - p)); photo = null
        })
    }
    if (renaming) {
        var name by remember { mutableStateOf(stage.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename stage") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ Stages.save(stage.copy(name = name.ifBlank { stage.name })); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${stage.name}?") },
            text = { Text("Its notes and photos are removed from the phone.") },
            confirmButton = { TextButton({ deleting = false; Stages.delete(stage.id); onBack() }) { Text("Delete", color = Yaw.bad) } },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoteEditor(note: PaceNote?, onDismiss: () -> Unit, onSave: (PaceNote) -> Unit, onDelete: (() -> Unit)? = null, onInsertBefore: (() -> Unit)? = null) {
    var text by remember { mutableStateOf(note?.text ?: "") }
    var dist by remember { mutableStateOf(note?.distAfter?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "New note" else "Edit note") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Call, e.g. R3- tgt dc") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(dist, { dist = it.filter(Char::isDigit) }, label = { Text("Distance to next (m)") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Text("Says: " + NoteParser.speak(PaceNote(text)), color = Yaw.dim, fontSize = 13.sp)
                if (onDelete != null || onInsertBefore != null) Row {
                    onInsertBefore?.let { TextButton(it) { Text("Insert before") } }
                    onDelete?.let { TextButton(it) { Text("Delete", color = Yaw.bad) } }
                }
            }
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) onSave(PaceNote(text.trim(), dist.toIntOrNull())) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Thumb(f: File, onClick: () -> Unit) {
    val bmp = remember(f) {
        runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(f.path, o)?.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Yaw.panelAlt).clickable(onClick = onClick)) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PhotoViewer(f: File, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(f) {
        runCatching {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / s > 2600) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) Image(
                bmp, null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f); ox += pan.x; oy += pan.y
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
                contentScale = ContentScale.Fit,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton({
                    // Hand the photo to Lens (or anything else) to copy the text out.
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    val i = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(i, "Copy text with…"))
                }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                IconButton(onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- race mode

/** Full screen while the co-driver runs: the next calls, big, and buttons you can hit at speed. */
@Composable
fun RaceScreen() {
    val s = CoDriver.stage ?: return
    val live by Telemetry.live.collectAsState()
    val u = Prefs.units
    val next = CoDriver.next
    Column(Modifier.fillMaxSize().background(Yaw.bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name.uppercase(), style = LabelStyle, modifier = Modifier.weight(1f))
            if (CoDriver.offTrack) Pill("OFF ROUTE", Yaw.warn)
            Spacer(Modifier.width(6.dp))
            Text("${Fmt.n(u.speed(live.speed), 0)} ${u.speedLabel}", style = numStyle(18.sp))
        }
        Spacer(Modifier.height(8.dp))
        val toNext = CoDriver.distanceToNext
        Text(if (toNext.isNaN()) "FINISH" else "${maxOf(0, toNext.toInt())} m", style = numStyle(28.sp, Yaw.dim))
        val upcoming = s.notes.drop(next).take(3)
        if (upcoming.isEmpty()) {
            Text("Stage complete", style = numStyle(48.sp, Yaw.ok))
        }
        upcoming.forEachIndexed { i, n ->
            Text(
                n.text,
                style = numStyle(if (i == 0) 64.sp else 30.sp, if (i == 0) Yaw.accent else Yaw.dim),
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("LAST: ${CoDriver.lastCall}", style = LabelStyle, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
        Text("${next} / ${s.notes.size}  ·  ${Fmt.n(CoDriver.position, 0)} m", style = LabelStyle, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("−50", Modifier.weight(1f), filled = false, height = 64.dp) { CoDriver.nudge(-50.0) }
            BigButton("+50", Modifier.weight(1f), filled = false, height = 64.dp) { CoDriver.nudge(50.0) }
            BigButton("SYNC", Modifier.weight(1f), filled = false, color = Yaw.accent2, height = 64.dp) { CoDriver.sync() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("BACK", Modifier.weight(1f), filled = false, height = 72.dp) { CoDriver.back() }
            BigButton("REPEAT", Modifier.weight(1.4f), height = 72.dp) { CoDriver.repeat() }
            BigButton("SKIP", Modifier.weight(1f), filled = false, height = 72.dp) { CoDriver.skip() }
        }
        Spacer(Modifier.height(8.dp))
        BigButton("STOP", Modifier.fillMaxWidth(), Icons.Filled.Close, color = Yaw.bad, filled = false, height = 48.dp) { CoDriver.stop() }
    }
}

// ---------------------------------------------------------------- recce pad

/** Big buttons to build calls while driving the stage slowly: direction, grade, then modifiers. */
@Composable
fun RecceScreen(onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val live by Telemetry.live.collectAsState()
    var dir by remember { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said != null) Recce.add(NoteParser.fromSpeech(said))
    }
    fun call(g: String) {
        if (dir.isEmpty() && g != "HP" && g != "SQ") return
        Recce.add(if (dir.isEmpty()) g else "$dir$g")
        dir = ""
    }
    BackHandler { confirmEnd = true }

    Column(Modifier.fillMaxSize().background(Yaw.bg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECCE · ${Recce.name.uppercase()}", style = LabelStyle)
                Text(if (live.hasFix) "GPS ±${Fmt.n(live.accM, 0)} m · ${Recce.notes.size} notes" else "Waiting for GPS…", style = LabelStyle)
            }
            BigButton("FINISH", height = 40.dp, color = Yaw.ok) { confirmEnd = true }
        }
        // The last few calls, newest at the bottom.
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
            for (n in Recce.notes.takeLast(4)) {
                Text(n.text, style = numStyle(if (n === Recce.notes.last()) 36.sp else 20.sp, if (n === Recce.notes.last()) Yaw.accent else Yaw.dim), maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("LEFT", Modifier.weight(1f), color = if (dir == "L") Yaw.accent else Yaw.dim, filled = dir == "L", height = 64.dp) { dir = "L" }
            BigButton("RIGHT", Modifier.weight(1f), color = if (dir == "R") Yaw.accent else Yaw.dim, filled = dir == "R", height = 64.dp) { dir = "R" }
        }
        Spacer(Modifier.height(8.dp))
        for (row in listOf(listOf("1", "2", "3", "HP"), listOf("4", "5", "6", "SQ"))) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in row) BigButton(g, Modifier.weight(1f), height = 64.dp, filled = dir.isNotEmpty() || g.length == 2) { call(g) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf("+", "-", "tgt", "opn", "long", "dc", "cr", "!", "into", "ki", "ko", "jmp", "ruts", "brake")) {
                BigButton(m, height = 48.dp, filled = false, color = Yaw.accent2) {
                    if (m == "+" || m == "-") {
                        val last = Recce.notes.lastOrNull()
                        if (last != null) Recce.notes[Recce.notes.size - 1] = last.copy(text = last.text.replace(Regex("^([LR](?:\\d))"), "$1$m"))
                    } else Recce.amend(m)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("UNDO", Modifier.weight(1f), Icons.AutoMirrored.Filled.Undo, filled = false, height = 56.dp) { Recce.undo() }
            BigButton("SAY IT", Modifier.weight(1.4f), Icons.Filled.Mic, color = Yaw.accent2, height = 56.dp) {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "e.g. \"left four tightens don't cut\"")
                runCatching { speech.launch(i) }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End recce?") },
            text = { Text("${Recce.notes.size} notes will be saved as a stage with their GPS positions and distances.") },
            confirmButton = { TextButton({ confirmEnd = false; Recce.finish()?.let { onDone(it.id) } }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton({ confirmEnd = false; Recce.cancel() }) { Text("Discard", color = Yaw.bad) }
                    TextButton({ confirmEnd = false }) { Text("Keep going") }
                }
            },
        )
    }
}
