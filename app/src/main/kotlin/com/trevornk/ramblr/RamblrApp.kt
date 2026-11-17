package com.trevornk.ramblr

import android.app.Application

/**
 * Process entry point. Its one job is to apply [PersonalDefaults] before any activity, service or
 * keyboard reads a setting; without the optional asset that is a single failed file lookup.
 */
class RamblrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PersonalDefaults.applyIfNeeded(this)
    }
}
