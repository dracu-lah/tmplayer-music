package com.tmplayer.music.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.tmplayer.music.i18n.LanguagePicker
import com.tmplayer.music.i18n.LanguageSetting
import com.tmplayer.music.i18n.Languages
import com.tmplayer.music.i18n.Translator
import com.tmplayer.music.platform.Logger
import java.util.Locale

/**
 * The in-app language and Android's own per-app language (Android 13 and later: Settings, Apps,
 * TMPlayer Music, Language) kept as one setting. Adapted from TMPlayer's `AppLocales` (GPL-3.0).
 *
 * The in-app choice ([LanguageSetting]) is the one the app reads. Each change is handed to
 * [LocaleManager], so Android's page shows it and the window lays out in its direction; a change
 * made on Android's page comes back the other way through [adopt]. Older Android (the floor is 6.0)
 * has no per-app language: there the choice is applied to the activity's own configuration by
 * [wrap] and the screen is recreated on a change.
 *
 * A language whose catalog is not shipped is never handed to Android or read: it reads as the
 * system default, so Android's page and the app never disagree about it.
 */
object AppLocales {

    private const val TAG = "AppLocales"
    private const val PREFS = "app_locales"
    private const val APPLIED = "applied"

    /** At process start: reads the saved choice, takes what Android's page holds, loads the language. */
    fun start(context: Context) {
        LanguageSetting.start(Prefs(context))
        refresh(context)
    }

    /**
     * Brings the loaded language in step with the saved choice, the device's languages and Android's
     * page. Cheap when nothing changed; run at start and each time the app comes back to the screen.
     */
    fun refresh(context: Context) {
        runCatching {
            adopt(context, LanguageSetting.saved.value)?.let { adopted ->
                LanguageSetting.set(adopted.takeIf { it.isEmpty() || Translator.hasCatalog(it) } ?: "")
            }
            val saved = LanguageSetting.saved.value
            Translator.select(saved, system(context))
            apply(context, saved)
        }.onFailure { Logger.w(TAG, "could not set the language", it) }
    }

    /**
     * Settings' picker: saves [tag] ("" for the system's language), loads it and makes the screen
     * show it. Android 13 and later recreate the activities themselves when the per-app language
     * changes; otherwise this recreates [activity] when the language in use changed.
     */
    fun change(activity: Activity, tag: String) {
        LanguageSetting.set(tag)
        val before = Translator.messages.tag
        val saved = LanguageSetting.saved.value
        val now = Translator.select(saved, system(activity))
        val androidRecreates = apply(activity, saved)
        if (!androidRecreates && now != before) activity.recreate()
    }

    /** The device's own languages, as BCP 47 tags, not this app's override of them. */
    @Suppress("DEPRECATION")
    fun system(context: Context): List<String> {
        val list: LocaleList? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> context.getSystemService(LocaleManager::class.java)?.systemLocales
            // The system's configuration, which nothing the app sets (Locale.setDefault) reaches.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> Resources.getSystem().configuration.locales
            else -> null
        }
        // Android 6 has one language, not a list of them.
        if (list == null) return listOf(Resources.getSystem().configuration.locale.toLanguageTag())
        return list.toLanguageTags().split(',').filter { it.isNotBlank() }
    }

    /**
     * Android 12 and older: [base] with the chosen language as its locale (and its layout direction),
     * for the activity's resources, dates and the views outside Compose. Newer Android does this
     * itself from the per-app language, so [base] comes back unchanged.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        return runCatching {
            val saved = Prefs(base).getString(LanguageSetting.KEY)
            val tag = Languages.resolve(saved, system(base), Translator::hasCatalog)
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.setLocales(LocaleList(locale)) else config.setLocale(locale)
            config.setLayoutDirection(locale)
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }

    /**
     * Hands the in-app choice to Android ("" is the system's language). Returns true when Android's
     * language changed, which makes it recreate the app's activities by itself.
     */
    private fun apply(context: Context, saved: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        val tag = LanguagePicker.usable(saved, Translator::hasCatalog)
        val wanted = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        var changed = false
        runCatching {
            if (manager.applicationLocales.toLanguageTags() != wanted.toLanguageTags()) {
                manager.applicationLocales = wanted
                changed = true
            }
        }.onFailure { Logger.w(TAG, "could not hand the language to Android", it) }
        prefs(context).edit().putString(APPLIED, tag).apply()
        return changed
    }

    /** A change made on Android's language page since the app last handed one over, or null. */
    private fun adopt(context: Context, saved: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(LocaleManager::class.java) ?: return null
        val current = runCatching { manager.applicationLocales }.getOrNull() ?: return null
        val applied = prefs(context).getString(APPLIED, null) ?: return null
        return LanguagePicker.adopted(applied, saved, if (current.isEmpty) "" else current[0].toLanguageTag())
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
