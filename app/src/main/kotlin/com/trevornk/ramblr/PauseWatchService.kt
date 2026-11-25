package com.trevornk.ramblr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Runs while Ramblr's accessibility service is paused for a listed app (see [PauseNearApps]) and
 * turns it back on once the user has left that app.
 *
 * A foreground service because nothing else keeps the process alive: with the accessibility
 * service disabled, the system no longer binds to Ramblr at all. Its notification says why the
 * floating icon is gone and turns Ramblr back on when tapped, which is also the only way back
 * when "usage access" was not granted and the watcher cannot see the app in front.
 */
class PauseWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var detector: PauseLeaveDetector? = null
    private var lastCheckMs = 0L
    private var pausedFor: String? = null

    private val check = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val foreground = PauseNearApps.newestForeground(this@PauseWatchService, lastCheckMs, now) ?: lastForeground
            lastCheckMs = now
            if (foreground != lastForeground) Log.i(TAG, "Pause watcher: $foreground is in front")
            lastForeground = foreground
            if (detector?.hasLeft(foreground, now) == true) {
                finish("left ${pausedFor ?: "the app"} for $foreground")
                return
            }
            handler.postDelayed(this, if (isInteractive()) CHECK_MS else CHECK_SCREEN_OFF_MS)
        }
    }

    /** The app in front as of the last check: no new event means it has not changed. */
    private var lastForeground: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is the system restarting this service after the process was killed: the
        // pause it was watching is the stored one.
        val target = intent?.getStringExtra(EXTRA_PACKAGE) ?: PauseNearAppsStore.activePackage(this)
        if (intent?.action == ACTION_RESUME || target == null) {
            // Still a foreground start, so it must show its notification before it may stop.
            startInForeground(target)
            finish(if (intent?.action == ACTION_RESUME) "notification tapped" else "no pause to watch")
            return START_NOT_STICKY
        }
        pausedFor = target
        startInForeground(target)
        isRunning = true
        handler.removeCallbacks(check)
        if (PauseNearApps.hasUsageAccess(this)) {
            val now = System.currentTimeMillis()
            // Paused on the tap that launches the app: it is not in front yet, and must show up.
            val anticipated = intent?.getBooleanExtra(EXTRA_ANTICIPATED, false) == true
            detector = PauseLeaveDetector(
                PauseNearAppsStore.packages(this) + target,
                confirmByMs = if (anticipated) now + PauseLeaveDetector.CONFIRM_LAUNCH_MS else null,
            )
            lastForeground = if (anticipated) null else target
            lastCheckMs = now - 500
            handler.postDelayed(check, CHECK_MS)
        } else {
            Log.w(TAG, "Paused near $target without usage access: only the notification turns Ramblr back on")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        isRunning = false
        super.onDestroy()
    }

    private fun finish(reason: String) {
        handler.removeCallbacks(check)
        PauseNearApps.resume(this, reason)
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(target: String?) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, target, PauseNearApps.hasUsageAccess(this)), type)
    }

    private fun isInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

    companion object {
        private const val TAG = "PhoneWhisper"
        const val CHANNEL_ID = "pause_near_apps"
        const val NOTIFICATION_ID = 0x9A05
        const val ACTION_RESUME = "com.trevornk.ramblr.action.RESUME_AFTER_PAUSE"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ANTICIPATED = "anticipated"
        private const val CHECK_MS = 700L
        private const val CHECK_SCREEN_OFF_MS = 10_000L

        /** True while an instance is watching a pause in this process. */
        @Volatile var isRunning = false
            private set

        /**
         * Starts watching the pause for [packageName]. Must be called before the accessibility
         * service disables itself. If the system refuses the foreground start, a plain
         * notification with the same "turn back on" tap is posted instead, so a pause never
         * leaves the user without a way back.
         */
        fun start(context: Context, packageName: String, anticipated: Boolean = false) {
            val intent = Intent(context, PauseWatchService::class.java)
                .putExtra(EXTRA_PACKAGE, packageName).putExtra(EXTRA_ANTICIPATED, anticipated)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the pause watcher; posting the notification alone", e)
                runCatching {
                    ensureChannel(context)
                    context.getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(context, packageName, watching = false))
                }
            }
        }

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                // Low: it explains a state, it must not make a sound each time a bank app opens.
                NotificationChannel(CHANNEL_ID, "Paused near apps", NotificationManager.IMPORTANCE_LOW)
            )
        }

        private fun appLabel(context: Context, packageName: String?): String {
            if (packageName == null) return "an app"
            return try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) {
                packageName // not visible to this app: the package name still says which one
            }
        }

        internal fun notification(context: Context, packageName: String?, watching: Boolean): Notification {
            ensureChannel(context)
            val resume = PendingIntent.getForegroundService(
                context, 0,
                Intent(context, PauseWatchService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val label = appLabel(context, packageName)
            val text = if (watching) "Off while $label is open. Back on when you leave it, or tap to turn it on now."
            else "Off since $label was opened. Tap to turn it back on."
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Ramblr paused")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setContentIntent(resume)
                .build()
        }
    }
}
