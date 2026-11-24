package com.trevornk.ramblr

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The apps Ramblr turns its accessibility service off for, and whether it is off for one now.
 *
 * Banking apps refuse to work, or warn, while an accessibility service they do not know is
 * enabled: it is how remote-control scams operate the phone. The exclusion list (#256) only makes
 * Ramblr behave in such an app; the service stays enabled and the app still sees it. This list is
 * the off switch (#255) done automatically: the service disables itself the moment a listed app
 * comes to the front, and [PauseWatchService] enables it again once the user has left that app.
 *
 * Empty by default, and inert without the advanced tier (WRITE_SECURE_SETTINGS granted over adb,
 * see [InvocationSecureSettings.canWrite]): an app cannot switch its own accessibility service
 * back on without it, so Ramblr never pauses when it could not come back by itself.
 */
object PauseNearAppsStore {
    private const val PREFS_NAME = "ramblr"
    const val KEY_PACKAGES = "pause_near_apps"

    /** The listed app Ramblr is paused for right now; absent while it is not paused. */
    const val KEY_ACTIVE_PACKAGE = "pause_near_active_package"

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cached: Set<String> = emptySet()

    /** The list, parsed once per stored value: this is read on every window event. */
    fun packages(prefs: SharedPreferences): Set<String> {
        val raw = prefs.getString(KEY_PACKAGES, null)
        if (raw != cachedRaw) {
            cached = PerAppExclusionStore.parse(raw)
            cachedRaw = raw
        }
        return cached
    }

    fun packages(context: Context): Set<String> = packages(prefs(context))

    fun setPackages(prefs: SharedPreferences, packages: Set<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putString(KEY_PACKAGES, PerAppExclusionStore.serialize(cleaned)).apply()
    }

    fun setPackages(context: Context, packages: Set<String>) = setPackages(prefs(context), packages)

    fun setListed(context: Context, packageName: String, listed: Boolean) {
        val current = packages(context).toMutableSet()
        if (listed) current.add(packageName) else current.remove(packageName)
        setPackages(context, current)
    }

    fun activePackage(context: Context): String? = prefs(context).getString(KEY_ACTIVE_PACKAGE, null)

    fun setActivePackage(context: Context, packageName: String?) {
        prefs(context).edit().apply {
            if (packageName == null) remove(KEY_ACTIVE_PACKAGE) else putString(KEY_ACTIVE_PACKAGE, packageName)
        }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Decides when the user has left the app Ramblr paused for, from the package in front at each
 * check. Pure, so the rules are unit-tested:
 *
 *  - a listed app in front (the same one or another): still paused;
 *  - a system surface that opens over an app without the user leaving it (a permission or
 *    biometric prompt, a chooser, [TRANSIENT_PACKAGES]): still paused;
 *  - anything else in front for [settleMs] without interruption: left. The wait keeps a moment
 *    spent in between two screens of the listed app from turning the service back on under it.
 */
class PauseLeaveDetector(
    private val listed: Set<String>,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) {
    private var awaySinceMs: Long? = null

    /** True once the user has been out of the listed apps for [settleMs]. [foreground] null means
     *  "unknown at this check" and changes nothing. */
    fun hasLeft(foreground: String?, nowMs: Long): Boolean {
        if (foreground == null) return false
        if (foreground in listed || foreground in TRANSIENT_PACKAGES) {
            awaySinceMs = null
            return false
        }
        val since = awaySinceMs ?: nowMs.also { awaySinceMs = it }
        return nowMs - since >= settleMs
    }

    companion object {
        const val DEFAULT_SETTLE_MS = 1_500L

        /** Surfaces that come to the front on top of an app the user is still in. */
        val TRANSIENT_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.samsung.android.biometrics.app.setting",
        )
    }
}

object PauseNearApps {
    private const val TAG = "PhoneWhisper"

    /** Set from the moment the service starts disabling itself until it next connects: events
     *  still arrive in between and must not start a second pause. Main thread. */
    private var pauseInFlight = false

    /** Events that announce an app coming to the front; added to the service's subscription
     *  while the list has entries (see [eventTypes]). */
    const val WINDOW_EVENTS: Int =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED

    /** What the accessibility service must listen to for this feature: nothing unless it can run. */
    fun eventTypes(context: Context): Int =
        if (PauseNearAppsStore.packages(context).isNotEmpty() && InvocationSecureSettings.canWrite(context)) WINDOW_EVENTS else 0

    /** Whether [packageName] coming to the front must pause Ramblr. */
    fun shouldPause(packageName: String?, listed: Set<String>, canReEnable: Boolean): Boolean =
        canReEnable && !packageName.isNullOrEmpty() && packageName in listed

    /**
     * Called by the accessibility service for every event, before anything else. Returns true
     * when it paused: the service is then disabling itself and the event needs no more handling.
     * [activeWindowPackage] is asked only when the event itself does not name a listed app.
     */
    fun onAccessibilityEvent(
        service: WhisperAccessibilityService,
        eventType: Int,
        eventPackage: String?,
        activeWindowPackage: () -> String?,
    ): Boolean {
        if (pauseInFlight) return true
        if (eventType and WINDOW_EVENTS == 0) return false
        val listed = PauseNearAppsStore.packages(service)
        if (listed.isEmpty()) return false
        val canReEnable = InvocationSecureSettings.canWrite(service)
        val trigger = eventPackage?.takeIf { shouldPause(it, listed, canReEnable) }
            ?: activeWindowPackage()?.takeIf { shouldPause(it, listed, canReEnable) }
            ?: return false
        return pause(service, trigger)
    }

    /**
     * For the moment the service connects. Connected means not paused, whoever turned it on: the
     * stored pause is cleared and a watcher still running is stopped. Then, since a listed app
     * may already be in front (the user enabled the service from its screen, say), that is
     * checked once; returns true when it paused again.
     */
    fun onServiceConnected(service: WhisperAccessibilityService, activeWindowPackage: String?): Boolean {
        pauseInFlight = false
        if (PauseNearAppsStore.activePackage(service) != null) PauseNearAppsStore.setActivePackage(service, null)
        if (PauseWatchService.isRunning) service.stopService(Intent(service, PauseWatchService::class.java))
        val listed = PauseNearAppsStore.packages(service)
        if (!shouldPause(activeWindowPackage, listed, InvocationSecureSettings.canWrite(service))) return false
        return pause(service, activeWindowPackage!!)
    }

    /**
     * Turns the service off for [packageName]. The watcher is started first, while this process
     * still has the standing of a bound accessibility service: it is what brings Ramblr back.
     * Recorded as a deliberate turn-off, so the "Ramblr was turned off" recovery notice (#254)
     * stays quiet.
     */
    private fun pause(service: WhisperAccessibilityService, packageName: String): Boolean {
        Log.i(TAG, "Pausing near $packageName: turning the accessibility service off")
        pauseInFlight = true
        PauseNearAppsStore.setActivePackage(service, packageName)
        PauseWatchService.start(service, packageName)
        InvocationGuardRail.dismissBanner(service)
        InvocationGuardRail.recordUserTurnedOff(service)
        service.disableSelf()
        return true
    }

    /**
     * Turns the service back on and forgets the pause. Safe to call when not paused. Returns
     * whether the service is enabled afterwards.
     */
    fun resume(context: Context, reason: String): Boolean {
        val wasPausedFor = PauseNearAppsStore.activePackage(context)
        PauseNearAppsStore.setActivePackage(context, null)
        if (InvocationSecureSettings.isActiveComponentEnabled(context)) return true
        val enabled = InvocationSecureSettings.reEnableService(context)
        if (enabled) {
            // With no other service enabled the master switch is off too; the list alone does not
            // bring a service back on every Android version.
            runCatching { Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
        }
        Log.i(TAG, "Resuming after $wasPausedFor ($reason): service enabled=$enabled")
        return enabled
    }

    /**
     * A pause whose watcher did not survive (the app was updated, the phone restarted, the process
     * was killed and not restarted) would leave Ramblr off for good. Called when the process
     * starts and after an update or a boot: none of those happens with the listed app still in
     * front, so the service simply comes back.
     */
    fun recoverIfStranded(context: Context) {
        if (PauseNearAppsStore.activePackage(context) == null || PauseWatchService.isRunning || pauseInFlight) return
        resume(context, "recovered a pause without a watcher")
    }

    /** Whether Ramblr may read which app is in front without its accessibility service: the
     *  "usage access" special permission, which the watcher needs to notice the user leaving. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The package of the newest "came to the front" event in ([sinceMs], now], or null when
     * there is none: the app in front is then still the one from the previous check.
     */
    fun newestForeground(context: Context, sinceMs: Long, nowMs: Long): String? {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        return try {
            val events = usage.queryEvents(sinceMs, nowMs)
            val event = UsageEvents.Event()
            var newest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) newest = event.packageName
            }
            newest
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read usage events", e)
            null
        }
    }
}

/** Brings Ramblr back after an app update or a restart that happened while it was paused. */
class PauseRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED ->
                PauseNearApps.recoverIfStranded(context)
        }
    }
}
