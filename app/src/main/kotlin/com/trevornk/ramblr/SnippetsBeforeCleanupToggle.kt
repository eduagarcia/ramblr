package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences

/**
 * Applies Snippets to the transcript before AI cleanup as well as to the delivered text.
 *
 * Snippets normally run once, on the text about to be delivered (see
 * [DictationRuntime.finalizeForDelivery]), so cleanup never sees an expansion. Used as fixed
 * replacements for words the transcriber keeps getting wrong ("Open Sense" -> "OPNSense"), they
 * are worth applying first too: the cleanup model then reads the right term instead of
 * guessing at the wrong one. The pass after cleanup still runs, for a trigger cleanup produced.
 *
 * Off by default, which keeps the original single pass. Does nothing while Snippets are off.
 */
object SnippetsBeforeCleanupToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "snippets_before_cleanup"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    /** [transcript] with Snippets expanded when this is on, else [transcript] itself. */
    fun apply(context: Context, transcript: String): String =
        if (isEnabled(context)) SnippetRuntimeSupport.expand(context, transcript) else transcript

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
