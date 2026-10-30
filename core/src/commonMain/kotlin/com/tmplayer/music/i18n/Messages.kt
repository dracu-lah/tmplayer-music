package com.tmplayer.music.i18n

import java.text.NumberFormat
import java.util.Locale

/**
 * One UI language's text: its own catalog over the English one, key by key. A key the language
 * has not translated yet, or translated into something that does not parse, reads in English.
 *
 * Adapted from TMPlayer's `Messages` (GPL-3.0), without the locale formatter and the pseudo-locale,
 * which this app does not need yet. It is the generated [Strings], so `messages.commonRetry` is
 * typed; [text] and [format] are the untyped way in, for the tests.
 */
class Messages internal constructor(
    val tag: String,
    private val own: Map<String, Icu.Pattern>,
    private val english: Map<String, Icu.Pattern>,
) : Strings {

    override val messages: Messages get() = this

    val locale: Locale = Locale.forLanguageTag(tag)

    fun text(key: String): String = format(key, emptyMap())

    fun format(key: String, vararg args: Pair<String, Any?>): String = format(key, args.toMap())

    fun format(key: String, args: Map<String, Any?>): String {
        val pattern = own[key] ?: english[key] ?: return key
        val out = StringBuilder()
        render(pattern.parts, args, null, out)
        return out.toString()
    }

    private fun number(value: Number): String = NumberFormat.getInstance(locale).format(value)

    private fun render(parts: List<Icu.Part>, args: Map<String, Any?>, pound: Number?, out: StringBuilder) {
        for (part in parts) when (part) {
            is Icu.Text -> out.append(part.text)
            is Icu.Value -> when (val value = args[part.name]) {
                null -> out.append('{').append(part.name).append('}')
                is Number -> out.append(number(value))
                else -> out.append(value.toString())
            }
            Icu.Pound -> out.append(pound?.let(::number) ?: "#")
            is Icu.Plural -> {
                val value = asNumber(args[part.name])
                val shifted: Number = if (part.offset == 0) value else Decimal.of(value).minus(part.offset.toLong())
                val operands = PluralRules.Operands(value)
                val exact = operands.whole?.let { "=$it" }
                val case = exact?.let(part.cases::get)
                    ?: part.cases[PluralRules.select(tag, shifted)]
                    ?: part.cases.getValue("other")
                render(case, args, shifted, out)
            }
            is Icu.Select -> {
                val case = part.cases[args[part.name]?.toString()] ?: part.cases.getValue("other")
                render(case, args, pound, out)
            }
        }
    }

    private fun asNumber(value: Any?): Number = when (value) {
        is Number -> value
        null -> 0
        else -> Decimal.parse(value.toString()) ?: 0
    }

    override fun toString(): String = "Messages($tag, ${own.size} of ${english.size} keys)" // i18n-ok: debug toString
}
