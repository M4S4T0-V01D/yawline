package dev.m4s4t0.yawline.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.obd.Kind
import dev.m4s4t0.yawline.obd.Obd
import dev.m4s4t0.yawline.obd.ObdState
import dev.m4s4t0.yawline.obd.Pids
import dev.m4s4t0.yawline.sensors.GpsSource
import dev.m4s4t0.yawline.sensors.Telemetry
import dev.m4s4t0.yawline.session.Recorder
import kotlinx.coroutines.delay

/** The top strip on every main screen: logo, car and GPS status, record button. */
@Composable
fun StatusBar(onGarage: () -> Unit) {
    val obd by Obd.state.collectAsState()
    val live by Telemetry.live.collectAsState()
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(20.sp)
        Spacer(Modifier.weight(1f))
        val (carText, carColor) = when (val s = obd) {
            is ObdState.Live -> "CAR" to Yaw.ok
            is ObdState.Connecting -> "…" to Yaw.warn
            is ObdState.Failed -> "CAR" to Yaw.bad
            ObdState.Off -> "NO CAR" to Yaw.dim
        }
        Pill(carText, carColor, onClick = onGarage)
        Spacer(Modifier.width(6.dp))
        val gpsText = when {
            live.source == GpsSource.DEMO -> "DEMO"
            !live.hasFix -> "GPS…"
            live.gpsHz >= 1.5f -> "GPS ${live.gpsHz.toInt()}Hz"
            else -> "GPS"
        }
        Pill(gpsText, if (live.hasFix) Yaw.ok else Yaw.warn, onClick = onGarage)
        Spacer(Modifier.width(8.dp))
        RecordButton { if (Recorder.recording) Recorder.stop() else Recorder.start(ctx) }
    }
}

@Composable
fun RecordButton(onClick: () -> Unit) {
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(Recorder.recording) { while (Recorder.recording) { blink = !blink; delay(600) } }
    val on = Recorder.recording
    Row(
        Modifier.clip(CircleShape)
            .background(if (on) Yaw.bad else Yaw.panelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(if (on) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null,
            tint = if (on) Color.White else Yaw.bad.copy(alpha = if (blink) 0.5f else 1f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        var t by remember { mutableStateOf(0L) }
        LaunchedEffect(on) { while (on) { t = Recorder.elapsedMs; delay(500) } }
        Text(if (on) Fmt.duration(t) else "REC", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black, color = if (on) Color.White else Yaw.text))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashScreen(onGarage: () -> Unit) {
    val values by Obd.values.collectAsState()
    val obd by Obd.state.collectAsState()
    val live by Telemetry.live.collectAsState()
    val rate by Obd.rate.collectAsState()
    val u = Prefs.units
    var editing by remember { mutableIntStateOf(-1) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        RpmBar(values[0x0C] ?: Double.NaN, Prefs.shiftRpm)
        Spacer(Modifier.height(12.dp))
        // Hero: speed (GPS, which is truer than the car's when wheels spin) and RPM.
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("SPEED", style = LabelStyle)
                Row(verticalAlignment = Alignment.Bottom) {
                    val speed = if (live.hasFix) u.speed(live.speed) else values[0x0D]?.let { u.kmh(it) } ?: Double.NaN
                    Text(Fmt.n(speed, 0), style = numStyle(84.sp))
                    Text(u.speedLabel, style = numStyle(16.sp, Yaw.dim, FontWeight.Bold), modifier = Modifier.padding(bottom = 16.dp, start = 4.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("RPM", style = LabelStyle)
                val rpm = values[0x0C]
                Text(Fmt.n(rpm ?: Double.NaN, 0), style = numStyle(44.sp, if (rpm != null && rpm >= Prefs.shiftRpm) Yaw.bad else Yaw.accent))
            }
        }
        if (obd !is ObdState.Live) {
            Spacer(Modifier.height(8.dp))
            Panel(Modifier.fillMaxWidth(), onClick = onGarage, border = Yaw.mix(Yaw.border, Yaw.accent, 0.5f)) {
                Text(
                    when (val s = obd) {
                        is ObdState.Connecting -> "Connecting to ${s.adapter} — ${s.step}…"
                        is ObdState.Failed -> "Couldn't connect: ${s.reason}"
                        else -> "No car connected"
                    },
                    style = TextStyle(color = Yaw.text, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                )
                Hint("Tap to open the Garage and connect an OBD adapter, or start the simulator to try everything out.")
            }
        }
        Spacer(Modifier.height(12.dp))
        val tiles = Prefs.dashTiles
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { col, code ->
                    val idx = row * 2 + col
                    Tile(code, values, Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { editing = idx }))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Hint("Long-press a tile to change it." + if (rate > 0) "  Reading ${rate.toInt()} values/s." else "", Modifier.padding(bottom = 16.dp))
    }

    if (editing >= 0) {
        val supported = (obd as? ObdState.Live)?.supported
        val choices = Pids.ALL.filter { p -> supported == null || p.derived || p.code in supported }
        AlertDialog(
            onDismissRequest = { editing = -1 },
            confirmButton = { TextButton({ editing = -1 }) { Text("Close") } },
            title = { Text("Show on this tile") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    items(choices) { p ->
                        Text(
                            "${p.name}  ·  ${Pids.unit(p, u)}",
                            color = Yaw.text,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val l = Prefs.dashTiles.toMutableList()
                                if (editing < l.size) l[editing] = p.code
                                Prefs.saveDashTiles(l)
                                editing = -1
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun Tile(code: Int, values: Map<Int, Double>, modifier: Modifier) {
    val p = Pids[code] ?: return
    val u = Prefs.units
    val v = values[code]
    val f = Pids.fraction(p, v)
    val hot = when {
        v == null -> false
        p.code == 0x05 -> v >= Prefs.coolantWarnC
        p.code == 0x5C -> v >= 130
        p.code == 0x0F -> v >= 60
        p.code == 0x42 -> v < 12.0
        else -> false
    }
    val color = when {
        hot -> Yaw.bad
        p.kind == Kind.TEMP -> Yaw.heat(f)
        p.kind == Kind.BOOST -> Yaw.accent2
        else -> Yaw.accent
    }
    Panel(modifier, border = if (hot) Yaw.bad else Yaw.border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.short, style = LabelStyle, modifier = Modifier.weight(1f))
            if (hot) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Yaw.bad))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Pids.format(p, v, u), style = numStyle(32.sp, if (hot) Yaw.bad else Yaw.text))
            Text(Pids.unit(p, u), style = numStyle(12.sp, Yaw.dim, FontWeight.Bold), modifier = Modifier.padding(start = 3.dp, bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        MiniBar(f, color)
    }
}

/** A square arc gauge with the value in the middle; used on the Drift screen for speed. */
@Composable
fun ArcReadout(label: String, value: String, unit: String, fraction: Float, modifier: Modifier, color: Color = Yaw.accent) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        ArcGauge(fraction, Modifier.fillMaxSize(), color)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = numStyle(30.sp))
            Text("$label $unit".trim().uppercase(), style = LabelStyle)
        }
    }
}
