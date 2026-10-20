package dev.m4s4t0.yawline.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.m4s4t0.yawline.Fmt
import dev.m4s4t0.yawline.Prefs
import dev.m4s4t0.yawline.Units
import dev.m4s4t0.yawline.obd.BleLink
import dev.m4s4t0.yawline.obd.BtClassicLink
import dev.m4s4t0.yawline.obd.Codes
import dev.m4s4t0.yawline.obd.DtcInfo
import dev.m4s4t0.yawline.obd.Link
import dev.m4s4t0.yawline.obd.Obd
import dev.m4s4t0.yawline.obd.ObdState
import dev.m4s4t0.yawline.obd.Pids
import dev.m4s4t0.yawline.obd.SimLink
import dev.m4s4t0.yawline.obd.UsbLink
import dev.m4s4t0.yawline.sensors.GpsSource
import dev.m4s4t0.yawline.sensors.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val USB_PERMISSION = "dev.m4s4t0.yawline.USB_PERMISSION"

/** Opens the adapter remembered by [Prefs.lastAdapter] ("sim", "bt:ADDR", "ble:ADDR" or "usb"). Null if it is gone. */
@SuppressLint("MissingPermission")
fun linkFor(ctx: Context, key: String): Link? {
    val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    return when {
        key == "sim" -> SimLink()
        key.startsWith("bt:") && bt != null -> runCatching { BtClassicLink(bt.getRemoteDevice(key.drop(3)), bt) }.getOrNull()
        key.startsWith("ble:") && bt != null -> runCatching { BleLink(ctx, bt.getRemoteDevice(key.drop(4))) }.getOrNull()
        key == "usb" -> {
            val um = ctx.getSystemService(UsbManager::class.java)
            um.deviceList.values.firstOrNull { UsbLink.chipOf(it) != UsbLink.Chip.UNKNOWN && um.hasPermission(it) }?.let { UsbLink(um, it) }
        }
        else -> null
    }
}

fun connectTo(ctx: Context, key: String, link: Link) {
    Prefs.saveLastAdapter(key, link.label)
    if (key == "sim") Telemetry.useDemo(true)
    Obd.connect(link)
}

@SuppressLint("MissingPermission")
@Composable
fun GarageScreen() {
    val ctx = LocalContext.current
    val obd by Obd.state.collectAsState()
    val values by Obd.values.collectAsState()
    val rate by Obd.rate.collectAsState()
    val live by Telemetry.live.collectAsState()
    val scope = rememberCoroutineScope()
    val u = Prefs.units
    val bt = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }

    var picking by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanFor by remember { mutableStateOf("obd") }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var codes by remember { mutableStateOf<Codes?>(null) }
    var busy by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calMsg by remember { mutableStateOf("") }

    // USB permission comes back as a broadcast.
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectTo(ctx, "usb", UsbLink(ctx.getSystemService(UsbManager::class.java), d))
                }
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }

    // BLE scan, for BLE OBD adapters or a RaceBox, stopped after 10 s.
    DisposableEffect(scanning) {
        val scanner = bt?.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.device.name ?: return
                val want = if (scanFor == "racebox") name.startsWith("RaceBox", true)
                else listOf("obd", "elm", "vlink", "vgate", "ios-vlink", "obdlink", "veepeak", "konnwei", "v-link", "android-vlink").any { name.contains(it, true) } || name.isNotBlank()
                if (want && found.none { it.address == r.device.address }) found.add(r.device)
            }
        }
        if (scanning && scanner != null) {
            found.clear()
            runCatching { scanner.startScan(cb) }
            scope.launch { delay(10_000); scanning = false }
        }
        onDispose { if (scanner != null) runCatching { scanner.stopScan(cb) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        // ------------------------------------------------ car
        SectionTitle("Car")
        Panel(Modifier.fillMaxWidth()) {
            when (val s = obd) {
                is ObdState.Live -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill("CONNECTED", Yaw.ok)
                        Spacer(Modifier.width(8.dp))
                        Text(s.adapter, style = TextStyle(color = Yaw.text, fontWeight = FontWeight.Bold, fontSize = 15.sp), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(s.info, style = LabelStyle.copy(letterSpacing = 0.sp))
                    s.vin?.let { Text("VIN  $it", style = numStyle(15.sp), modifier = Modifier.padding(top = 4.dp)) }
                    Text("${s.supported.size} sensors · ${rate.toInt()} values/s", style = LabelStyle, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    BigButton("DISCONNECT", Modifier.fillMaxWidth(), Icons.Filled.LinkOff, color = Yaw.bad, filled = false, height = 44.dp) {
                        Obd.disconnect(); if (Prefs.lastAdapter == "sim") Telemetry.useDemo(false)
                    }
                }
                is ObdState.Connecting -> {
                    Text("Connecting to ${s.adapter}", style = TextStyle(color = Yaw.text, fontWeight = FontWeight.Bold))
                    Text(s.step, style = LabelStyle)
                    Spacer(Modifier.height(8.dp))
                    BigButton("CANCEL", Modifier.fillMaxWidth(), filled = false, height = 44.dp) { Obd.disconnect() }
                }
                else -> {
                    if (s is ObdState.Failed) Text("${s.adapter}: ${s.reason}", style = TextStyle(color = Yaw.bad, fontSize = 14.sp), modifier = Modifier.padding(bottom = 8.dp))
                    if (Prefs.lastAdapter.isNotEmpty()) {
                        BigButton("RECONNECT ${Prefs.lastAdapterName.uppercase()}", Modifier.fillMaxWidth(), Icons.Filled.Refresh) {
                            val l = linkFor(ctx, Prefs.lastAdapter)
                            if (l != null) connectTo(ctx, Prefs.lastAdapter, l) else picking = true
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("BLUETOOTH", Modifier.weight(1f), Icons.Filled.Bluetooth, filled = Prefs.lastAdapter.isEmpty()) { picking = true }
                        BigButton("USB", Modifier.weight(1f), Icons.Filled.Usb, filled = false) {
                            val um = ctx.getSystemService(UsbManager::class.java)
                            val d = um.deviceList.values.firstOrNull { UsbLink.chipOf(it) != UsbLink.Chip.UNKNOWN }
                            when {
                                d == null -> busy = "No supported USB adapter found (FTDI, CP210x or CDC). CH340 cables aren't supported."
                                um.hasPermission(d) -> connectTo(ctx, "usb", UsbLink(um, d))
                                else -> um.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, Intent(USB_PERMISSION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BigButton("SIMULATOR", Modifier.fillMaxWidth(), Icons.Filled.PlayArrow, color = Yaw.accent2, filled = false, height = 44.dp) {
                        connectTo(ctx, "sim", SimLink())
                    }
                    Hint("The simulator is a turbo AWD car on a forest stage: every screen works without a car, GPS included.", Modifier.padding(top = 6.dp))
                }
            }
        }

        // ------------------------------------------------ trouble codes
        if (obd is ObdState.Live) {
            SectionTitle("Trouble codes")
            Panel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("READ", Modifier.weight(1f), height = 44.dp) {
                        busy = "Reading codes…"
                        scope.launch { codes = runCatching { Obd.readCodes() }.getOrNull(); busy = "" }
                    }
                    BigButton("CLEAR", Modifier.weight(1f), color = Yaw.bad, filled = false, height = 44.dp) { confirmClear = true }
                }
                codes?.let { c ->
                    val groups = listOf("Stored" to c.stored, "Pending" to c.pending, "Permanent" to c.permanent)
                    if (groups.all { it.second.isEmpty() }) Text("No codes. All clear.", style = TextStyle(color = Yaw.ok, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 10.dp))
                    for ((label, list) in groups) if (list.isNotEmpty()) {
                        Text(label.uppercase(), style = LabelStyle, modifier = Modifier.padding(top = 10.dp))
                        for (code in list) Row(Modifier.padding(vertical = 4.dp)) {
                            Text(code, style = numStyle(16.sp, if (DtcInfo.severe(code)) Yaw.bad else Yaw.warn), modifier = Modifier.width(70.dp))
                            Text(DtcInfo.describe(code), style = TextStyle(color = Yaw.text, fontSize = 14.sp))
                        }
                    }
                }
                if (busy.isNotEmpty()) Hint(busy, Modifier.padding(top = 8.dp))
            }

            SectionTitle("All sensors")
            Panel(Modifier.fillMaxWidth()) {
                val supported = (obd as ObdState.Live).supported
                val shown = Pids.ALL.filter { it.derived && values.containsKey(it.code) || it.code in supported }
                for (p in shown) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = TextStyle(color = Yaw.text, fontSize = 14.sp), modifier = Modifier.weight(1f))
                    Text(Pids.format(p, values[p.code], u), style = numStyle(16.sp))
                    Text(" " + Pids.unit(p, u), style = LabelStyle, modifier = Modifier.width(44.dp))
                }
                Hint("Only the standard (Mode 01) sensors your ECU reports are listed. Maker-specific values like oil temp on many cars need extended PIDs.", Modifier.padding(top = 6.dp))
            }
        } else if (busy.isNotEmpty()) Hint(busy, Modifier.padding(top = 8.dp))

        // ------------------------------------------------ GPS
        SectionTitle("Position")
        Panel(Modifier.fillMaxWidth()) {
            val srcIdx = when { Telemetry.demo -> 2; Prefs.raceBox.isNotEmpty() -> 1; else -> 0 }
            ChipRow(listOf("Phone GPS", "RaceBox", "Demo"), srcIdx) { i ->
                when (i) {
                    0 -> { Prefs.saveRaceBox(""); Telemetry.useDemo(false); Telemetry.restart() }
                    1 -> { scanFor = "racebox"; scanning = true }
                    2 -> Telemetry.useDemo(true)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${live.source.label} · " + if (live.hasFix) "±${Fmt.n(live.accM, 1)} m · ${live.sats} sats · ${Fmt.n(live.gpsHz.toDouble(), 0)} Hz" else "no fix yet",
                style = TextStyle(color = Yaw.text, fontSize = 14.sp),
            )
            if (Telemetry.raceBoxStatus.isNotEmpty()) Text(Telemetry.raceBoxStatus, style = LabelStyle)
            if (scanning && scanFor == "racebox") RaceBoxPicker(found) { d -> Prefs.saveRaceBox(d.address); scanning = false; Telemetry.useDemo(false); Telemetry.restart() }
        }

        // ------------------------------------------------ phone mount
        SectionTitle("Phone mount")
        Panel(Modifier.fillMaxWidth()) {
            Text("Mount offset ${Fmt.n(Prefs.mountYawOffset.toDouble(), 0)}°", style = TextStyle(color = Yaw.text, fontWeight = FontWeight.Bold))
            Hint("The phone works out which way is forward from gravity. If it's mounted at an angle, calibrate: press START, accelerate firmly in a straight line, then press DONE.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(if (calibrating) "DONE" else "START", Modifier.weight(1f), height = 44.dp, color = if (calibrating) Yaw.ok else Yaw.accent) {
                    val m = Telemetry.motionForCalibration
                    if (m == null) { calMsg = "Sensors aren't running."; return@BigButton }
                    if (!calibrating) { m.beginCalibration(); calibrating = true; calMsg = "Accelerate straight now…" }
                    else {
                        calibrating = false
                        val off = m.endCalibration()
                        calMsg = if (off == null) "Not enough acceleration — try again harder." else { Prefs.saveMountYawOffset(off); "Saved: ${off.toInt()}°" }
                    }
                }
                BigButton("RESET", Modifier.weight(1f), height = 44.dp, filled = false) { Prefs.saveMountYawOffset(0f); calMsg = "" }
            }
            if (calMsg.isNotEmpty()) Hint(calMsg, Modifier.padding(top = 6.dp))
        }

        // ------------------------------------------------ settings
        SectionTitle("Look")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in THEMES) {
                val on = p.id == Prefs.theme
                Column(
                    Modifier.width(118.dp).clip(RoundedCornerShape(14.dp)).background(p.panel)
                        .border(2.dp, if (on) p.accent else p.border, RoundedCornerShape(14.dp))
                        .clickable { Prefs.saveTheme(p.id); Yaw.palette = p }.padding(10.dp),
                ) {
                    Row {
                        for (c in listOf(p.bg, p.accent, p.accent2, p.ok)) Box(Modifier.size(16.dp).clip(CircleShape).background(c).border(1.dp, p.border, CircleShape))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.name, style = TextStyle(color = p.text, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                    Text(p.blurb, style = TextStyle(color = p.dim, fontSize = 11.sp), maxLines = 2)
                }
            }
        }

        SectionTitle("Settings")
        Panel(Modifier.fillMaxWidth()) {
            Text("UNITS", style = LabelStyle)
            ChipRow(Units.entries.map { it.label }, u.ordinal, Modifier.padding(vertical = 6.dp)) { Prefs.saveUnits(Units.entries[it]) }
            SliderRow("Shift light", "${Prefs.shiftRpm} rpm", Prefs.shiftRpm.toFloat(), 3000f..10000f) { Prefs.saveShiftRpm((it / 100).toInt() * 100) }
            SliderRow("Coolant warning", "${Fmt.n(u.temp(Prefs.coolantWarnC.toDouble()), 0)} ${u.tempLabel}", Prefs.coolantWarnC.toFloat(), 90f..125f) { Prefs.saveCoolantWarnC(it.toInt()) }
            SliderRow("Call notes ahead", "${Fmt.n(Prefs.callLeadSec.toDouble(), 1)} s", Prefs.callLeadSec, 2f..8f) { Prefs.saveCallLeadSec((it * 2).toInt() / 2f) }
            SliderRow("Voice speed", "${Fmt.n(Prefs.voiceRate.toDouble(), 2)}×", Prefs.voiceRate, 0.8f..1.8f) { Prefs.saveVoiceRate((it * 20).toInt() / 20f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on", style = TextStyle(color = Yaw.text, fontSize = 15.sp), modifier = Modifier.weight(1f))
                Switch(Prefs.keepScreenOn, { Prefs.saveKeepScreenOn(it) })
            }
        }

        // ------------------------------------------------ hardware
        SectionTitle("What to plug in")
        Panel(Modifier.fillMaxWidth()) {
            for ((title, body) in HARDWARE) {
                Text(title, style = TextStyle(color = Yaw.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
                Text(body, style = TextStyle(color = Yaw.text, fontSize = 13.sp, lineHeight = 18.sp))
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (picking) AdapterPicker(
        paired = remember(bt) { runCatching { bt?.bondedDevices?.toList() }.getOrNull().orEmpty().sortedBy { it.name ?: "" } },
        found = if (scanFor == "obd") found else emptyList(),
        scanning = scanning && scanFor == "obd",
        onScan = { scanFor = "obd"; scanning = true },
        onDismiss = { picking = false; scanning = false },
        onPick = { key, link -> picking = false; scanning = false; connectTo(ctx, key, link) },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear trouble codes?") },
        text = { Text("This turns off the check-engine light and resets readiness monitors (the car may fail an emissions test until they re-run). Engine off, ignition on.") },
        confirmButton = {
            TextButton({
                confirmClear = false; busy = "Clearing…"
                scope.launch { val ok = Obd.clearCodes(); codes = runCatching { Obd.readCodes() }.getOrNull(); busy = if (ok) "Codes cleared." else "The car refused to clear codes." }
            }) { Text("Clear", color = Yaw.bad) }
        },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(label, style = TextStyle(color = Yaw.text, fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(value, style = numStyle(15.sp, Yaw.accent))
    }
    Slider(v, onChange, valueRange = range)
}

@SuppressLint("MissingPermission")
@Composable
private fun AdapterPicker(
    paired: List<BluetoothDevice>,
    found: List<BluetoothDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String, Link) -> Unit,
) {
    val ctx = LocalContext.current
    val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose adapter") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("PAIRED (CLASSIC)", style = LabelStyle)
                if (paired.isEmpty()) Hint("Nothing paired. Pair OBDLink MX+ / vLinker MC+ in Android's Bluetooth settings first (PIN is usually 1234 or 0000).")
                for (d in paired) DeviceRow(d.name ?: d.address, d.address) { if (bt != null) onPick("bt:${d.address}", BtClassicLink(d, bt)) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BLUETOOTH LE", style = LabelStyle, modifier = Modifier.weight(1f))
                    TextButton(onScan, enabled = !scanning) {
                        Icon(Icons.Filled.BluetoothSearching, null); Text(if (scanning) " Scanning…" else " Scan")
                    }
                }
                Hint("For OBDLink CX, vLinker FS/MC+ BLE, Veepeak BLE and similar. Don't pair these — just scan.")
                for (d in found) DeviceRow(d.name ?: d.address, d.address) { onPick("ble:${d.address}", BleLink(ctx, d)) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun RaceBoxPicker(found: List<BluetoothDevice>, onPick: (BluetoothDevice) -> Unit) {
    Text("Looking for RaceBox… (switch it on)", style = LabelStyle, modifier = Modifier.padding(top = 8.dp))
    for (d in found) DeviceRow(d.name ?: d.address, d.address) { onPick(d) }
}

@Composable
private fun DeviceRow(name: String, addr: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)) {
        Text(name, style = TextStyle(color = Yaw.text, fontWeight = FontWeight.Bold, fontSize = 15.sp))
        Text(addr, style = LabelStyle)
    }
}

private val HARDWARE = listOf(
    "Best: OBDLink MX+ (Bluetooth)" to
        "STN chip, fast and reliable, sleeps with the car. Reads 30–60 values a second here. Pair it in Android settings, then BLUETOOTH. The OBDLink CX (BLE) is the cheaper equivalent.",
    "Good value: vLinker MC+ / FS" to
        "Genuine-chip adapters at a lower price. MC+ supports Classic and BLE; FS is BLE only. Use SCAN for BLE.",
    "Wired: OBDLink EX or SX USB" to
        "FTDI-based USB cable through a USB-C OTG adapter. No radio dropouts, the most robust choice in a rally car. Also works: other FTDI, CP210x or CDC adapters.",
    "Avoid: cheap \"ELM327 v1.5/v2.1\" clones" to
        "Most are pirated firmware on slow chips. They drop out, miss commands and can't do multi-sensor requests, so updates crawl along at 3–6 a second.",
    "For drift angle: RaceBox Mini / Mini S" to
        "25 Hz GPS with its own IMU, over Bluetooth LE. Makes the line and the slip angle far more precise than the phone's 1 Hz GPS. Choose RaceBox under Position.",
    "Mount" to
        "A rigid, screw-down mount (RAM ball or a bracket on the roll cage). Any wobble shows up as fake drift. Keep it upright or flat, not loose in a holder.",
)
