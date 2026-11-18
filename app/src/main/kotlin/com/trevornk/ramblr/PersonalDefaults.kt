package com.trevornk.ramblr

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Settings baked into a private build, so a phone is set up by installing the APK: providers and
 * their keys, dictation language, vocabulary, fixed replacements, the cleanup prompt.
 *
 * The source is one optional asset, [ASSET], packaged from `personal/assets/` at the repository
 * root. That directory is gitignored (it holds API keys), so a public build has no such asset and
 * this whole file does nothing. `personal-defaults.example.json` documents the format, and
 * `scripts/personal-defaults-from-whisrs.py` writes the file from a whisrs desktop configuration.
 *
 * The asset is applied once per `version` value: [applyIfNeeded] compares it with the one applied
 * last and returns when they match, so what the user later changes in Settings stays until a
 * build with a different `version` is installed. Applying overwrites only the settings the asset
 * names; provider entries and snippets the user added themselves are kept.
 */
object PersonalDefaults {
    private const val TAG = "PersonalDefaults"
    const val ASSET = "personal_defaults.json"
    private const val PREFS_NAME = "ramblr"
    const val KEY_APPLIED_VERSION = "personal_defaults_applied_version"

    /** Stable ids, so a newer asset replaces the entries (and their keys) an older one made. */
    const val TRANSCRIPTION_ENTRY_ID = "personal-transcription"
    const val CLEANUP_ENTRY_ID = "personal-cleanup"
    const val STYLE_KEY = "custom_personal"
    private const val SNIPPET_KEY_PREFIX = "personal_"

    /** Set for the unit-test JVM by app/build.gradle.kts, so tests see the app's own defaults. */
    const val SKIP_PROPERTY = "ramblr.skipPersonalDefaults"

    fun applyIfNeeded(context: Context) {
        if (System.getProperty(SKIP_PROPERTY) == "true") return
        val raw = try {
            context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            return // no asset: a public build
        }
        try {
            val json = JSONObject(raw)
            val version = json.optString("version", "1")
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_APPLIED_VERSION, null) == version) return
            apply(context, json)
            prefs.edit().putString(KEY_APPLIED_VERSION, version).apply()
            Log.i(TAG, "Applied personal defaults version $version")
        } catch (e: Exception) {
            // A malformed asset must never keep the app from starting.
            Log.e(TAG, "Could not apply personal defaults", e)
        }
    }

    internal fun apply(context: Context, json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()

        val personal = ArrayList<ProviderChainEntry>()
        json.optJSONObject("transcription")?.let { t ->
            val entry = transcriptionEntry(t)
            personal += entry
            ProviderCredentialStore.set(context, entry.id, t.optString("apiKey"))
            edit.putBoolean("use_local", false)
            if (t.has("retries") || t.has("fallbackModel")) {
                TranscriptionRetrySettings.set(prefs, t.optInt("retries", 0), t.optString("fallbackModel"))
            }
        }
        json.optJSONObject("cleanup")?.let { c ->
            val entry = cleanupEntry(c)
            personal += entry
            ProviderCredentialStore.set(context, entry.id, c.optString("apiKey"))
            edit.putBoolean(PostProcessingToggle.KEY, true)
            CloudFeatureToggle.setCleanupEnabled(context, true)
            if (c.has("timeoutMs")) CleanupTimeout.set(prefs, c.optLong("timeoutMs"))
        }
        if (personal.isNotEmpty()) {
            ProviderChainStore.save(context, mergeChain(ProviderChainStore.load(context), personal))
        }

        if (json.has("language")) {
            DictationLanguage.setLanguage(prefs, json.optString("language").ifBlank { null })
        }

        json.optJSONArray("vocabulary")?.let { array ->
            val terms = (0 until array.length()).map { array.optString(it) }
            // Through parse(), so blank and repeated terms are dropped the way the editor drops them.
            edit.putString(
                "custom_vocabulary_terms",
                VocabularyTerms.serialize(VocabularyTerms.parse(terms.joinToString("\n"))),
            )
        }

        json.optJSONObject("replacements")?.let { replacements ->
            val pairs = replacements.keys().asSequence().map { it to replacements.optString(it) }.toList()
            SnippetsStore.save(prefs, mergeSnippets(SnippetsStore.load(prefs), pairs))
            edit.putBoolean(SnippetsToggle.KEY, true)
            edit.putBoolean(SnippetsBeforeCleanupToggle.KEY, json.optBoolean("replacementsBeforeCleanup", true))
        }

        json.optJSONObject("cleanupStyle")?.let { style ->
            val prompt = style.optString("prompt")
            if (prompt.isNotBlank()) {
                val persona = CleanupPersona(
                    key = STYLE_KEY,
                    title = style.optString("title", "Personal"),
                    subtitle = style.optString("subtitle", "From this build's personal defaults"),
                    prompt = prompt,
                    isBuiltIn = false,
                )
                CustomPersonaStore.save(context, CustomPersonaStore.load(context).filterNot { it.key == STYLE_KEY } + persona)
                edit.putString("cleanup_style", STYLE_KEY)
                edit.putString("post_processing_prompt", prompt)
            }
        }

        // Any other setting of the "ramblr" preferences file, by its key: a boolean, a string or a
        // whole number (stored as an int when it fits one, like the app's own writers do).
        json.optJSONObject("prefs")?.let { extra ->
            for (key in extra.keys()) {
                when (val value = extra.get(key)) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    else -> Log.w(TAG, "Ignoring pref $key: unsupported value type")
                }
            }
        }

        if (json.optBoolean("skipOnboarding", false)) {
            // The floating icon is set up from outside (accessibility service, permissions), so the
            // first-run wizard and the one-time "text leaves the device" question have no job left.
            edit.putBoolean("onboarding_complete", true)
            edit.putString("onboarding_setup_mode", OnboardingSetupMode.FLOATING_BUTTON.name)
            edit.putBoolean(KeyboardTranslationReadiness.KEY_CONSENT, true)
        }

        edit.apply()
    }

    /** An OpenAI-compatible entry that only transcribes. */
    internal fun transcriptionEntry(t: JSONObject): ProviderChainEntry = ProviderChainEntry(
        kind = ProviderKind.OPENAI,
        model = PostProcessor.DEFAULT_MODEL, // never used: this entry does no cleanup
        baseUrlOverride = t.optString("baseUrl").ifBlank { null },
        transcriptionModel = t.optString("model").ifBlank { null },
        id = TRANSCRIPTION_ENTRY_ID,
        useForTranscription = true,
        useForCleanup = false,
        presetId = t.optString("preset").ifBlank { null },
    )

    /** An OpenAI-compatible entry that only cleans up. */
    internal fun cleanupEntry(c: JSONObject): ProviderChainEntry = ProviderChainEntry(
        kind = ProviderKind.OPENAI,
        model = c.optString("model"),
        baseUrlOverride = c.optString("baseUrl").ifBlank { null },
        transcriptionModel = TranscriberClient.DEFAULT_MODEL, // never used: this entry does not transcribe
        id = CLEANUP_ENTRY_ID,
        useForTranscription = false,
        useForCleanup = true,
        presetId = c.optString("preset").ifBlank { null },
        extraBody = c.optJSONObject("extraBody")?.toString(),
    )

    /**
     * [personal] first, then what the user added themselves. Dropped from [existing]: the earlier
     * copies of the personal entries, and entries without an id, which are the never-configured
     * default a fresh install loads.
     */
    internal fun mergeChain(existing: ProviderChain, personal: List<ProviderChainEntry>): ProviderChain {
        val personalIds = personal.map { it.id }.toSet()
        val kept = existing.entries.filter { it.id.isNotBlank() && it.id !in personalIds }
        return ProviderChainStore.normalizeLocalPosition(ProviderChain(personal + kept))
    }

    /**
     * The user's own snippets, then one per replacement. Earlier personal snippets are replaced,
     * and a replacement whose trigger the user already has a snippet for is skipped.
     */
    internal fun mergeSnippets(existing: List<SnippetEntry>, replacements: List<Pair<String, String>>): List<SnippetEntry> {
        val kept = existing.filterNot { it.key.startsWith(SNIPPET_KEY_PREFIX) }
        val taken = kept.map { SnippetExpander.normalizeWords(it.trigger) }.toMutableSet()
        val added = ArrayList<SnippetEntry>()
        replacements.forEachIndexed { index, (trigger, expansion) ->
            val words = SnippetExpander.normalizeWords(trigger)
            if (words.isEmpty() || expansion.isBlank() || !taken.add(words)) return@forEachIndexed
            added += SnippetEntry(key = "$SNIPPET_KEY_PREFIX$index", trigger = trigger.trim(), expansion = expansion.trim())
        }
        return kept + added
    }
}
