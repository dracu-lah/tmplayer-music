package com.tmplayer.music.i18n

/** One language the app can be shown in. [name] is the endonym, the same in every UI language. */
data class AppLanguage(val tag: String, val name: String, val rtl: Boolean = false)

/**
 * The languages TMPlayer Music is meant to speak, and how a device's own language list picks one.
 * Adapted from TMPlayer's `Languages` (GPL-3.0).
 *
 * The registry knows every language so that Android's per-app language list (`locales_config.xml`)
 * and the site list stay whole. What the app offers and reads is narrower: a language counts only
 * while its catalog (`i18n/<tag>.json`) is on the classpath, which the `available` parameters below
 * and [LanguagePicker] take care of. English is always there.
 *
 * Resolution, on every platform: the saved choice in Settings (empty means "follow the system"),
 * else the first system language that matches an available one by language and then region, else
 * English.
 */
object Languages {
    const val ENGLISH = "en"

    val all: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("es-419", "Español (Latinoamérica)"),
        AppLanguage("pt-BR", "Português (Brasil)"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("it", "Italiano"),
        AppLanguage("pl", "Polski"),
        AppLanguage("cs", "Čeština"),
        AppLanguage("ru", "Русский"),
        AppLanguage("uk", "Українська"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("ar", "العربية", rtl = true),
        AppLanguage("fa", "فارسی", rtl = true),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("bn", "বাংলা"),
        AppLanguage("ml", "മലയാളം"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
        AppLanguage("zh-CN", "简体中文"),
        AppLanguage("zh-TW", "繁體中文"),
    )

    val tags: List<String> = all.map { it.tag }

    /** The registry entry for [tag] (any case), or null for a tag it does not know, such as `""` or `und`. */
    fun find(tag: String): AppLanguage? = all.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /** True for a language written right to left, so a screen can mirror its layout. */
    fun isRtl(tag: String): Boolean = find(tag)?.rtl ?: false

    /**
     * The tag to show the app in. [saved] is the Settings value ("" for the system), [system] the
     * device's preferred languages as BCP 47 tags (`es-CL`, `zh-Hans-CN`, Android's `in-ID`), in
     * order. [available] says whether a language's catalog ships: a saved choice or a system match
     * without one is passed over, so the app never claims a language it reads in English.
     */
    fun resolve(saved: String?, system: List<String>, available: (String) -> Boolean = { true }): String {
        if (!saved.isNullOrBlank()) {
            find(saved)?.tag?.takeIf { it == ENGLISH || available(it) }?.let { return it }
        }
        for (tag in system) {
            match(tag)?.takeIf { it == ENGLISH || available(it) }?.let { return it }
        }
        return ENGLISH
    }

    /** The registry tag for one system tag, or null when none fits. Never throws, whatever [systemTag] is. */
    fun match(systemTag: String): String? {
        val parts = normalise(systemTag).split('-')
        val language = parts.first().lowercase()
        val rest = parts.drop(1)
        val script = rest.firstOrNull { it.length == 4 && it.all(Char::isLetter) }?.lowercase()
        val region = rest.firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }?.uppercase()
        return when (language) {
            // Spanish everywhere gets the Latin American catalog, Portuguese the Brazilian one:
            // the only ones there are, and far closer than English.
            "es" -> "es-419"
            "pt" -> "pt-BR"
            // Simplified for the mainland and the diaspora that writes it, Traditional for Taiwan,
            // Hong Kong and Macau. A bare `zh` is Simplified, the larger readership.
            "zh" -> when {
                script == "hans" -> "zh-CN"
                script == "hant" -> "zh-TW"
                region == null || region in setOf("CN", "SG", "MY") -> "zh-CN"
                region in setOf("TW", "HK", "MO") -> "zh-TW"
                else -> null
            }
            else -> all.firstOrNull { it.tag == language }?.tag
        }
    }

    /** Underscores to hyphens and Android's legacy codes to today's (`in` to `id`). */
    private fun normalise(tag: String): String {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val language = when (val l = parts.first().lowercase()) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> l
        }
        return (listOf(language) + parts.drop(1)).joinToString("-")
    }
}
