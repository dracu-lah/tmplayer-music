package com.tmplayer.music.i18n

import com.tmplayer.music.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The UI language for the whole process. Catalogs are `i18n/<tag>.json` on the classpath (the
 * resources of `:core`), nested objects whose keys join with dots: `{"songs": {"count": "..."}}` is
 * `songs.count`. Adapted from TMPlayer's `Translator` (GPL-3.0).
 *
 * Only English ships for the demo; [use] already reads any other catalog that is added later, and
 * falls back to English key by key.
 */
object Translator {
    private const val TAG = "Translator"
    const val ENGLISH = "en"

    private val englishPatterns: Map<String, Icu.Pattern> by lazy {
        val source = resource(ENGLISH) ?: error("i18n/en.json is missing")
        parse(source).mapValues { Icu.parse(it.value) }
    }

    private val state: MutableStateFlow<Messages> by lazy { MutableStateFlow(english()) }

    /** The current language's text, as a flow for Compose. */
    val active: StateFlow<Messages> get() = state.asStateFlow()

    /** The current language's text. */
    val messages: Messages get() = state.value

    /** Switches to [tag]. A language with no catalog reads all in English. */
    fun use(tag: String) {
        if (state.value.tag == tag) return
        state.value = if (tag == ENGLISH) english() else build(tag, resource(tag), englishPatterns)
        Logger.i(TAG, "UI language $tag")
    }

    fun english(): Messages = Messages(ENGLISH, englishPatterns, englishPatterns)

    /**
     * [tag]'s messages from its catalog JSON over [english]. A translation that does not parse, or
     * that uses an argument the English does not pass, is dropped so that key reads in English.
     */
    internal fun build(tag: String, json: String?, english: Map<String, Icu.Pattern>): Messages {
        val own = HashMap<String, Icu.Pattern>()
        if (json != null) {
            val entries = runCatching { parse(json) }
                .onFailure { Logger.w(TAG, "i18n/$tag.json does not parse", it) }
                .getOrDefault(emptyMap())
            for ((key, text) in entries) {
                val reference = english[key] ?: continue
                val pattern = runCatching { Icu.parse(text) }.getOrNull()
                if (pattern == null || !reference.args.keys.containsAll(pattern.args.keys)) {
                    Logger.w(TAG, "$tag: $key does not match the English, using English")
                    continue
                }
                own[key] = pattern
            }
        }
        return Messages(tag, own, english)
    }

    /** A catalog's JSON as dotted keys to message text. */
    fun parse(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, node: JsonObject) {
            for ((name, value) in node) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when {
                    value is JsonObject -> walk(key, value)
                    value is JsonPrimitive && value.isString -> out[key] = value.content
                    else -> throw IllegalArgumentException("$key is not text")
                }
            }
        }
        walk("", Json.parseToJsonElement(json).jsonObject)
        return out
    }

    /** `i18n/<tag>.json` from the classpath, or null when that language has no catalog yet. */
    fun resource(tag: String): String? =
        Translator::class.java.classLoader?.getResourceAsStream("i18n/$tag.json")?.use { it.readBytes().decodeToString() }
}
