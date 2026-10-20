package dev.m4s4t0.yawline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.yawline.notes.CoDriver
import dev.m4s4t0.yawline.notes.Recce
import dev.m4s4t0.yawline.sensors.Telemetry
import dev.m4s4t0.yawline.ui.DashScreen
import dev.m4s4t0.yawline.ui.DriftScreen
import dev.m4s4t0.yawline.ui.GarageScreen
import dev.m4s4t0.yawline.ui.LogsScreen
import dev.m4s4t0.yawline.ui.RaceScreen
import dev.m4s4t0.yawline.ui.RecceScreen
import dev.m4s4t0.yawline.ui.ReplayScreen
import dev.m4s4t0.yawline.ui.StageScreen
import dev.m4s4t0.yawline.ui.StagesScreen
import dev.m4s4t0.yawline.ui.StatusBar
import dev.m4s4t0.yawline.ui.Yaw
import dev.m4s4t0.yawline.ui.YawTheme

class MainActivity : ComponentActivity() {
    /** Text shared into the app (from Lens, Docs, a message...) waiting to become a stage. */
    private var shared by mutableStateOf<String?>(null)

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) Telemetry.restart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBars()
        takeShared(intent)
        askPermissions()
        setContent {
            YawTheme {
                LaunchedEffect(Yaw.palette) { applyBars() }
                LaunchedEffect(Prefs.keepScreenOn) {
                    if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App(shared) { shared = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    override fun onStart() { super.onStart(); Telemetry.acquire(this) }
    override fun onStop() { Telemetry.release(); super.onStop() }

    private fun takeShared(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { shared = it }
        }
    }

    private fun applyBars() {
        val style = if (Yaw.palette.light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(style, style)
    }

    private fun askPermissions() {
        val want = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) perms.launch(want.toTypedArray())
    }
}

private enum class Tab(val label: String) { DASH("DASH"), DRIFT("DRIFT"), STAGES("STAGES"), LOGS("LOGS"), GARAGE("GARAGE") }

@Composable
private fun App(shared: String?, onSharedUsed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }
    var replay by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(shared) { if (shared != null) { tab = Tab.STAGES; stage = null } }

    Box(Modifier.fillMaxSize().background(Yaw.bg).systemBarsPadding()) {
        when {
            // Full-screen modes take over while they run.
            CoDriver.running -> { BackHandler {}; RaceScreen() }
            Recce.active -> RecceScreen { id -> tab = Tab.STAGES; stage = id }
            replay != null -> ReplayScreen(replay!!, onBack = { replay = null }) { id -> replay = null; tab = Tab.STAGES; stage = id }
            stage != null && tab == Tab.STAGES -> StageScreen(stage!!) { stage = null }
            else -> Column(Modifier.fillMaxSize()) {
                StatusBar(onGarage = { tab = Tab.GARAGE })
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.DASH -> DashScreen(onGarage = { tab = Tab.GARAGE })
                        Tab.DRIFT -> DriftScreen()
                        Tab.STAGES -> StagesScreen(shared, onSharedUsed) { stage = it }
                        Tab.LOGS -> LogsScreen { replay = it }
                        Tab.GARAGE -> GarageScreen()
                    }
                }
                if (tab != Tab.DASH) BackHandler { tab = Tab.DASH }
                NavBar(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun NavBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Yaw.panel).padding(vertical = 6.dp)) {
        for (t in Tab.entries) {
            val on = t == tab
            val color = if (on) Yaw.accent else Yaw.dim
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    when (t) {
                        Tab.DASH -> Icons.Filled.Speed
                        Tab.DRIFT -> Icons.Filled.Explore
                        Tab.STAGES -> Icons.Filled.Flag
                        Tab.LOGS -> Icons.Filled.History
                        Tab.GARAGE -> Icons.Filled.Build
                    },
                    t.label, tint = color, modifier = Modifier.size(22.dp),
                )
                Text(t.label, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 1.sp))
            }
        }
    }
}
