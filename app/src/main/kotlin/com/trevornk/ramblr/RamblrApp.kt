package com.trevornk.ramblr

import android.app.Application

/**
 * Process entry point: applies [PersonalDefaults] before any activity, service or keyboard reads
 * a setting (without the optional asset that is a single failed file lookup), and brings the
 * accessibility service back when a pause near an app lost its watcher.
 */
class RamblrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PersonalDefaults.applyIfNeeded(this)
        // A pause near an app whose watcher is gone (see PauseNearApps): checked a moment after
        // start, since the system may be starting this process to restart that very watcher.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ PauseNearApps.recoverIfStranded(this) }, 5_000L)
    }
}
