package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * How long a dictation waits for cloud cleanup before the raw transcript is inserted instead.
 *
 * The default is [CLEANUP_WATERFALL_HARD_CAP_MS] (8 s), tuned on short dictations against fast
 * models. A reasoning model cleaning a transcript of several minutes needs longer than that, so
 * the budget can be raised: the stored value replaces the cap for live dictation, and a cloud
 * step's read timeout follows it (see [CleanupWaterfallExecutor.execute]'s `hardCapMs`).
 * Values outside [MIN_MS]..[MAX_MS] are clamped; nothing stored means the default.
 */
object CleanupTimeout {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "cleanup_timeout_ms"
    const val MIN_MS = 2_000L
    const val MAX_MS = 120_000L

    /** The budget for a stored value: the default for zero or less, else clamped. */
    fun resolve(storedMs: Long): Long =
        if (storedMs <= 0L) CLEANUP_WATERFALL_HARD_CAP_MS else storedMs.coerceIn(MIN_MS, MAX_MS)

    fun hardCapMs(prefs: SharedPreferences): Long = resolve(prefs.getLong(KEY, 0L))

    fun hardCapMs(context: Context): Long = hardCapMs(prefs(context))

    /** Stores [ms] as the budget; zero or less goes back to the default. */
    fun set(prefs: SharedPreferences, ms: Long) {
        if (ms <= 0L) prefs.edit().remove(KEY).apply() else prefs.edit().putLong(KEY, ms).apply()
    }

    fun set(context: Context, ms: Long) = set(prefs(context), ms)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
