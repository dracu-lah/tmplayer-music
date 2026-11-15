package com.tmplayer.music.i18n

/** One row of the language picker. [tag] is "" for System default, whose [name] is null (the screen words it). */
data class LanguageOption(val tag: String, val name: String?, val selected: Boolean) {
    val isSystem: Boolean get() = tag.isEmpty()
}

/**
 * What the Settings language picker lists and which row is marked, as plain functions so it is
 * tested away from a device. Only English and the languages whose catalog ships ([available]) are
 * offered, so an untranslated language is never a choice; the registry ([Languages.all]) still
 * knows all of them for Android's per-app list.
 */
object LanguagePicker {
    /** System default first, then the offered languages in registry order. The saved one is marked. */
    fun options(saved: String?, available: (String) -> Boolean): List<LanguageOption> {
        val current = usable(saved, available)
        val rows = Languages.all
            .filter { it.tag == Languages.ENGLISH || available(it.tag) }
            .map { LanguageOption(it.tag, it.name, selected = it.tag == current) }
        return listOf(LanguageOption("", null, selected = current.isEmpty())) + rows
    }

    /**
     * The saved choice as the picker reads it: the registry's spelling of a language that is
     * offered, else "" for System default (blank, garbage, or a language whose catalog is gone).
     */
    fun usable(saved: String?, available: (String) -> Boolean): String {
        val tag = saved?.let { Languages.find(it)?.tag } ?: return ""
        return if (tag == Languages.ENGLISH || available(tag)) tag else ""
    }

    /**
     * The in-app choice to keep after Android's own language page changed. [applied] is what the
     * app last handed Android, [saved] the in-app choice and [android] what Android holds now ("" for
     * the system's language). Null when nothing changed there or it is not a language we know.
     */
    fun adopted(applied: String, saved: String, android: String): String? {
        val now = if (android.isBlank()) "" else Languages.match(android) ?: return null
        if (now.equals(Languages.match(applied) ?: applied, ignoreCase = true)) return null
        return now.takeUnless { it.equals(saved, ignoreCase = true) }
    }
}
