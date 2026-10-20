package dev.m4s4t0.yawline

import android.app.Application
import dev.m4s4t0.yawline.notes.Stages
import dev.m4s4t0.yawline.session.Sessions
import dev.m4s4t0.yawline.ui.Yaw
import dev.m4s4t0.yawline.ui.THEMES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        Yaw.palette = THEMES.firstOrNull { it.id == Prefs.theme } ?: THEMES.first()
        Sessions.init(this)
        Stages.init(this)
    }

    companion object {
        lateinit var app: YawApp
            private set
        /** Lives as long as the process: OBD links, sensors and the recorder run here, not in a screen. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
