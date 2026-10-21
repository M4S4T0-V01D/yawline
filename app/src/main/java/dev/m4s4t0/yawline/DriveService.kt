package dev.m4s4t0.yawline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.m4s4t0.yawline.notes.CoDriver
import dev.m4s4t0.yawline.notes.Recce
import dev.m4s4t0.yawline.sensors.Telemetry
import dev.m4s4t0.yawline.session.Recorder
import dev.m4s4t0.yawline.track.Navigator
import dev.m4s4t0.yawline.track.TrackRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps GPS, sensors and the voice alive with the screen off while something is running: a recording,
 * the co-driver, a recce, a track being recorded or navigated. Stops itself when none of them are.
 */
class DriveService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Driving", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build(status()), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        Telemetry.acquire(this)
        job = YawApp.scope.launch {
            var last = ""
            while (true) {
                delay(1000)
                if (!busy()) { stopSelf(); break }
                val s = status()
                if (s != last) { nm.notify(ID, build(s)); last = s }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        job?.cancel()
        Telemetry.release()
        super.onDestroy()
    }

    private fun status(): String = buildList {
        if (Recorder.recording) add("Recording")
        if (CoDriver.running) add("Co-driver: ${CoDriver.stage?.name ?: ""}")
        if (Recce.active) add("Recce: ${Recce.name}")
        if (TrackRecorder.recording) add("Recording track")
        if (Navigator.running) add("Navigating: ${Navigator.track?.name ?: ""}")
    }.joinToString(" · ").ifEmpty { "Ready" }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("YAWLINE")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "drive"
        private const val ID = 7

        private fun busy() = Recorder.recording || CoDriver.running || Recce.active || TrackRecorder.recording || Navigator.running

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DriveService::class.java)) }
        }
    }
}
