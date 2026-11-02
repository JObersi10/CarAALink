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
 * Checks GitHub for something newer than this install, either a tagged
 * Release (Stable channel) or the latest commit on a branch (Dev channel --
 * "updates from dev or from one of my PRs"). Never downloads or installs
 * anything itself: GitHub Actions artifacts need the owner's own
 * authenticated browser session to download regardless (that's a GitHub
 * API constraint, not a choice made here -- even public-repo workflow
 * artifacts require being logged in), so both channels just open the right
 * GitHub page and let the owner take it from there. Same
 * no-embedded-credentials reasoning as the rest of this app.
 */
object UpdateChecker {

    private const val OWNER = "JObersi10"
    private const val REPO = "CarAALink"
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
                    AppSettings.UpdateChannel.DEV -> checkDev(context)
                }
            }
            val outcome = result.getOrNull()
            logger(context).log("update check (${AppSettings.updateChannel(context)}): ${result.exceptionOrNull() ?: outcome ?: "up to date"}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    result.isFailure -> if (manual) toast(context, "Update check failed: ${result.exceptionOrNull()?.message}")
                    outcome != null -> notify(context, outcome.first, outcome.second)
                    manual -> toast(context, "Already on the latest ${if (AppSettings.updateChannel(context) == AppSettings.UpdateChannel.DEV) "dev commit" else "release"}")
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

    /** Returns (title, url) if the watched branch has moved past this build's commit, else null. */
    private fun checkDev(context: Context): Pair<String, String>? {
        val branch = AppSettings.updateDevBranch(context).ifBlank { BuildConfig.GIT_BRANCH }
        val body = get("https://api.github.com/repos/$OWNER/$REPO/commits/$branch") ?: return null
        val json = JSONObject(body)
        val sha = json.getString("sha").take(BuildConfig.GIT_SHA.length)
        if (sha == BuildConfig.GIT_SHA || BuildConfig.GIT_SHA == "unknown") return null
        val actionsUrl = "https://github.com/$OWNER/$REPO/actions?query=branch%3A$branch"
        return "Dev build updated on $branch ($sha)" to actionsUrl
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
