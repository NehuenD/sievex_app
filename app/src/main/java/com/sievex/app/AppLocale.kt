package com.sievex.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's language, chosen in Settings.
 *
 * A personal app with one switch, so it is not wired into Android's per-app locale system: the
 * activity, the two services and the LLM prompts all read the same stored tag. Contexts are
 * wrapped before anything resolves resources, which keeps `getString`/`stringResource` in the
 * chosen language without an AppCompat theme requirement.
 */
object AppLocale {

    private const val PREFS = "sievex_locale"
    private const val KEY = "language"

    const val ENGLISH = "en"
    const val SPANISH = "es"

    /** The stored choice, or the system language until the user picks one. */
    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: Locale.getDefault().language

    /** The language the model is asked to write in: Spanish or English, nothing else. */
    fun modelLanguage(context: Context): String =
        if (language(context) == SPANISH) SPANISH else ENGLISH

    /**
     * Wraps a context so its resources resolve in the chosen language. Applied in
     * `attachBaseContext` of the activity and both services; harmless when nothing is stored.
     */
    fun wrap(base: Context): Context {
        val stored = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(stored)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Stores the choice and recreates the activity so every resource re-resolves. */
    fun set(activity: Activity, language: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language)
            .apply()
        Locale.setDefault(Locale.forLanguageTag(language))
        activity.recreate()
    }
}
