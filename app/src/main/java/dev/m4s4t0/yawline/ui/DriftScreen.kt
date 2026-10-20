package dev.m4s4t0.yawline.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Live motion: drift angle, G-forces, yaw and the line driven. */
@Composable
fun DriftScreen() {
    val live by Telemetry.live.collectAsState()
    val trail by Telemetry.trail.collectAsState()
    val u = Prefs.units
    var peakSlip by remember { mutableDoubleStateOf(0.0) }
    var peakG by remember { mutableFloatStateOf(0f) }
    var gTrail by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    LaunchedEffect(live.tMs, live.latG, live.lonG) {
        if (live.speed > 5) peakSlip = max(peakSlip, abs(live.slip))
        peakG = max(peakG, hypot(live.latG, live.lonG).toFloat())
        gTrail = (gTrail + (live.latG.toFloat() to live.lonG.toFloat())).takeLast(75)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DRIFT ANGLE", style = LabelStyle)
                    Text(Fmt.angle(live.slip), style = numStyle(64.sp, Yaw.heat((abs(live.slip) / 45).toFloat().coerceIn(0f, 1f))))
                    Text(
                        when {
                            live.speed < 5 -> "Needs speed"
                            abs(live.slip) < 3 -> "Gripping"
                            live.slip > 0 -> "Tail out left · nose right"
                            else -> "Tail out right · nose left"
                        },
                        style = LabelStyle,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Readout("Peak", Fmt.angle(peakSlip), "", size = 28.sp, color = Yaw.accent2)
                    Spacer(Modifier.height(4.dp))
                    BigButton("RESET", height = 34.dp, filled = false, color = Yaw.dim) { peakSlip = 0.0; peakG = 0f }
                }
            }
            DriftMeter(live.slip, peakSlip, Modifier.fillMaxWidth().aspectRatio(1.6f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.weight(1f)) {
                Text("G-FORCE", style = LabelStyle)
                GgPlot(gTrail, peakG, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 6.dp))
                Row {
                    Readout("Lat", Fmt.n(abs(live.latG), 2), "g", Modifier.weight(1f), size = 20.sp)
                    Readout(if (live.lonG < 0) "Brake" else "Accel", Fmt.n(abs(live.lonG), 2), "g", Modifier.weight(1f), size = 20.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    ArcReadout("", Fmt.n(u.speed(live.speed), 0), u.speedLabel, (live.speed / 55.0).toFloat(), Modifier.fillMaxWidth())
                }
                Panel(Modifier.fillMaxWidth()) {
                    Readout("Yaw rate", Fmt.n(abs(live.yawRate), 0), "°/s", size = 24.sp)
                    Readout("Peak G", Fmt.n(peakG.toDouble(), 2), "g", size = 24.sp, color = Yaw.accent2)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LINE", style = LabelStyle, modifier = Modifier.weight(1f))
                Text(
                    "${live.source.label} · ${if (live.hasFix) "±${Fmt.n(live.accM, 0)} m · ${live.sats} sats" else "no fix"}",
                    style = LabelStyle,
                )
            }
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                if (trail.size > 1) RouteView(trail, Modifier.fillMaxSize())
                else Hint("Your line appears here once you're moving.", Modifier.align(Alignment.Center))
            }
        }
        Hint(
            "Drift angle compares the gyro's heading with the GPS direction of travel. Bolt the phone down: " +
                "a wobbly mount reads as slide. A RaceBox (25 Hz GPS) makes it much sharper — set it up in the Garage.",
            Modifier.padding(vertical = 14.dp),
        )
    }
}
