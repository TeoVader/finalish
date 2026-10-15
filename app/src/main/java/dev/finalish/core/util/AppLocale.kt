package dev.finalish.core.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The app's own language, independent of the device's. Android 13+ keeps it as the per-app
 * language (also editable in the system's App languages settings); older versions keep it here
 * and apply it to each Activity and to the Application context.
 */
object AppLocale {
    /** Languages the app is translated into, as BCP-47 tags; null = follow the device. */
    val supported = listOf("en", "it", "es", "fr", "de", "pt-BR")

    /**
     * The Application context, for text built where no Context is passed (error messages, relative
     * times). Its resources follow the app's language; null in plain unit tests, which keep English.
     */
    @Volatile var appContext: Context? = null

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    /** The chosen language tag, or null when the app follows the device. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }

    /** Switches language (null = follow the device); before Android 13 the screen is recreated to apply it. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            // commit, not apply: the recreated screen reads it right away.
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                if (tag == null) remove(KEY) else putString(KEY, tag)
            }
            apply(activity.applicationContext)
            activity.recreate()
        }
    }

    /** Before Android 13: a context whose resources use the chosen language (attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    /** Before Android 13: makes the already-created Application context follow a new choice too. */
    @Suppress("DEPRECATION")
    private fun apply(app: Context) {
        val tag = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val locale = tag?.let { Locale.forLanguageTag(it) } ?: android.content.res.Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) }
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }
}
