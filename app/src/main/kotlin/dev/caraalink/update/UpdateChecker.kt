package dev.caraalink.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for something newer than this install: a tagged Release
 * (Stable channel) or the rolling `dev` pre-release
 * (`.github/workflows/android-ci.yml`'s "Publish rolling dev pre-release"
 * step -- overwritten on every push to `main` with the latest debug APK).
 * Never downloads or installs anything itself -- it just opens the
 * release page, where the APK asset is a normal public download (release
 * assets, unlike Actions artifacts, don't need an authenticated session).
 */
object UpdateChecker {

    private const val OWNER = "JObersi10"
    private const val REPO = "CarAALink"
    private const val DEV_RELEASE_TAG = "dev"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4

    private fun logger(context: Context) = DiagnosticLogger(context, "update-checker")

    /** Called from MainActivity.onCreate() -- silent unless something newer actually exists. */
    fun checkIfDue(context: Context) {
        if (!AppSettings.updateAutoCheck(context)) return
        checkNow(context, manual = false)
    }

    /** [manual] controls whether a "you're up to date" / error Toast shows; a found update always notifies. */
    fun checkNow(context: Context, manual: Boolean) {
        Thread({
            val result = runCatching {
                when (AppSettings.updateChannel(context)) {
                    AppSettings.UpdateChannel.STABLE -> checkStable()
                    AppSettings.UpdateChannel.DEV -> checkDev()
                }
            }
            val outcome = result.getOrNull()
            logger(context).log("update check (${AppSettings.updateChannel(context)}): ${result.exceptionOrNull() ?: outcome ?: "up to date"}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    result.isFailure -> if (manual) toast(context, "Update check failed: ${result.exceptionOrNull()?.message}")
                    outcome != null -> notify(context, outcome.first, outcome.second)
                    manual -> toast(context, "Already on the latest ${if (AppSettings.updateChannel(context) == AppSettings.UpdateChannel.DEV) "dev build" else "release"}")
                }
            }
        }, "UpdateChecker").start()
    }

    /**
     * Returns (title, url) if a newer tagged release exists, else null.
     * `/releases/latest` is a hard 404 (not an empty list) when the repo
     * has never had a single Release published -- the owner cuts those
     * manually, so a dev build like this one will hit that until the
     * first one ever exists. That's "nothing to compare against", not a
     * failure -- treated as up to date, no error surfaced.
     */
    private fun checkStable(): Pair<String, String>? {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases/latest") ?: return null
        val json = JSONObject(body)
        val tag = json.getString("tag_name").removePrefix("v")
        if (tag == BuildConfig.VERSION_NAME) return null
        return "Update available: v$tag" to json.getString("html_url")
    }

    /**
     * Returns (title, url) if the rolling `dev` pre-release's build is
     * newer than this install, else null. Two calls: the Release itself
     * (for its page URL to show/open) and the git ref the `dev` tag
     * actually points at (to resolve it to a commit, since the Release
     * API's own JSON doesn't include that). No Release published under
     * that tag yet -> nothing to compare against, same as Stable's
     * no-releases-yet case.
     */
    private fun checkDev(): Pair<String, String>? {
        val releaseBody = get("https://api.github.com/repos/$OWNER/$REPO/releases/tags/$DEV_RELEASE_TAG") ?: return null
        val release = JSONObject(releaseBody)
        val refBody = get("https://api.github.com/repos/$OWNER/$REPO/git/ref/tags/$DEV_RELEASE_TAG") ?: return null
        val sha = JSONObject(refBody).getJSONObject("object").getString("sha").take(BuildConfig.GIT_SHA.length)
        if (sha == BuildConfig.GIT_SHA || BuildConfig.GIT_SHA == "unknown") return null
        return "Dev build updated ($sha)" to release.getString("html_url")
    }

    /** null on a 404 (nothing there to compare against); throws on any other non-2xx. */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun notify(context: Context, title: String, url: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.caraalink.R.drawable.ic_tile_settings)
            .setContentTitle(title)
            .setContentText("Tap to open on GitHub")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
