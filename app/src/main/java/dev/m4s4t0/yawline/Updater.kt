package dev.m4s4t0.yawline

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from this app's GitHub releases. The release workflow (.github/workflows/release.yml)
 * builds YAWLINE whenever version.properties changes and publishes YAWLINE.apk with a latest.json
 * beside it. The APK is checked against the published SHA-256, then handed to Android's
 * installer. Android asks the user to confirm the first time; after that the app updates itself quietly.
 */
object Updater {
    data class Release(val versionCode: Int, val versionName: String, val sha256: String, val size: Long, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val API = "https://api.github.com/repos/"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private fun open(url: String, accept: String = "application/vnd.github+json"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "YAWLINE/${BuildConfig.VERSION_NAME}")
        }

    private fun readJson(url: String): JSONObject {
        val c = open(url)
        try {
            if (c.responseCode == 404) throw NoRelease()
            if (c.responseCode !in 200..299) throw IllegalStateException("GitHub answered ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private class NoRelease : Exception()

    /** Look for a newer release. [quiet] skips it if checked recently and keeps errors to itself. */
    suspend fun check(ctx: Context, quiet: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (state.value is State.Downloading || state.value is State.Installing) return@withContext null
        val now = System.currentTimeMillis()
        if (quiet && now - prefs(ctx).getLong("lastCheck", 0) < CHECK_EVERY_MS) return@withContext (state.value as? State.Available)?.release
        if (!quiet) state.value = State.Checking
        try {
            val rel = readJson(API + BuildConfig.UPDATE_REPO + "/releases/latest")
            val assets = rel.getJSONArray("assets")
            var meta: String? = null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) {
                    "latest.json" -> meta = a.getString("browser_download_url")
                    "YAWLINE.apk" -> apk = a.getString("browser_download_url")
                }
            }
            if (meta == null || apk == null) throw NoRelease()
            val j = readJson(meta)
            val r = Release(
                versionCode = j.optInt("versionCode"),
                versionName = j.optString("versionName", "?"),
                sha256 = j.optString("sha256"),
                size = j.optLong("size"),
                notes = j.optString("notes"),
                apkUrl = apk,
            )
            prefs(ctx).edit().putLong("lastCheck", now).apply()
            if (r.versionCode > BuildConfig.VERSION_CODE) {
                state.value = State.Available(r)
                r
            } else {
                state.value = State.UpToDate
                null
            }
        } catch (e: NoRelease) {
            prefs(ctx).edit().putLong("lastCheck", now).apply()
            state.value = State.UpToDate
            null
        } catch (e: Exception) {
            state.value = if (quiet) State.Idle else State.Failed("Couldn't check for updates: ${e.message}")
            null
        }
    }

    /** Download, verify and install [r]. May first send the user to allow installs from this app. */
    suspend fun install(ctx: Context, r: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            state.value = State.Failed("Allow YAWLINE to install updates, then tap Update again.", r)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            state.value = State.Downloading(r, 0f)
            val file = withContext(Dispatchers.IO) { download(ctx, r) }
            state.value = State.Installing(r)
            withContext(Dispatchers.IO) { commit(ctx, file) }
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: "The update failed.", r)
        }
    }

    private fun download(ctx: Context, r: Release): File {
        val out = File(ctx.cacheDir, "update-${r.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val c = open(r.apkUrl, accept = "application/octet-stream")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L
            var lastShown = 0f
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val p = if (total > 0) done.toFloat() / total else 0f
                        if (p - lastShown > 0.01f) {
                            lastShown = p
                            state.value = State.Downloading(r, p)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(r.sha256, ignoreCase = true)) {
            out.delete()
            throw IllegalStateException("The download was damaged (checksum mismatch). Try again.")
        }
        return out
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("yawline.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
        }
        apk.delete()
    }
}

/** The installer's answer: ask the user to confirm, or report a failure. Success restarts the app. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed"
                val r = (Updater.state.value as? Updater.State.Installing)?.release
                Updater.state.value = Updater.State.Failed("The update was not installed: $msg", r)
            }
        }
    }
}
