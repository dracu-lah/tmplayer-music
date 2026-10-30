// From TMPlayer (GPL-3.0), core/src/pureMain/kotlin/com/tmplayer/i18n/Decimal.kt.
package com.tmplayer.music.i18n

import kotlin.math.abs

/**
 * An exact decimal, for the plural rules: [unscaled] times ten to the minus [scale], so `1.50`
 * keeps its two visible fraction digits (CLDR's `v`), which a Double cannot say. The small part of
 * `java.math.BigDecimal` the catalog needs, in pure Kotlin so the browser build has it too.
 */
class Decimal private constructor(val unscaled: Long, val scale: Int) : Number(), Comparable<Decimal> {

    fun abs(): Decimal = if (unscaled < 0) Decimal(-unscaled, scale) else this

    /** This minus a whole number, keeping the scale. */
    fun minus(whole: Long): Decimal = Decimal(unscaled - whole * pow10(scale), scale)

    /** Whether the value is whole, whatever fraction digits it is written with. */
    val isWhole: Boolean get() = scale <= 0 || unscaled % pow10(scale) == 0L

    override fun compareTo(other: Decimal): Int {
        val s = maxOf(scale, other.scale)
        return (unscaled * pow10(s - scale)).compareTo(other.unscaled * pow10(s - other.scale))
    }

    override fun equals(other: Any?): Boolean = other is Decimal && compareTo(other) == 0
    override fun hashCode(): Int = toDouble().hashCode()

    /** The integer part, truncated toward zero. */
    override fun toLong(): Long = if (scale <= 0) unscaled * pow10(-scale) else unscaled / pow10(scale)
    override fun toInt(): Int = toLong().toInt()
    override fun toShort(): Short = toLong().toShort()
    override fun toByte(): Byte = toLong().toInt().toByte()
    override fun toDouble(): Double = if (scale <= 0) unscaled.toDouble() * pow10(-scale) else unscaled.toDouble() / pow10(scale)
    override fun toFloat(): Float = toDouble().toFloat()

    override fun toString(): String {
        if (scale <= 0) return toLong().toString()
        val digits = abs(unscaled).toString().padStart(scale + 1, '0')
        val sign = if (unscaled < 0) "-" else ""
        return sign + digits.dropLast(scale) + "." + digits.takeLast(scale)
    }

    companion object {
        val ONE = Decimal(1, 0)

        private fun pow10(n: Int): Long {
            var r = 1L
            repeat(n.coerceAtLeast(0)) { r *= 10 }
            return r
        }

        /** [number] exactly: whole types as they are, a Double by its shortest decimal form. */
        fun of(number: Number): Decimal = when (number) {
            is Decimal -> number
            is Long -> Decimal(number, 0)
            // On Kotlin/JS every number but a Long passes `is Int`, 1.5 too: whole only when it is.
            is Int, is Short, is Byte -> if (number.toDouble() % 1.0 == 0.0) Decimal(number.toLong(), 0) else parse(number.toDouble().toString()) ?: Decimal(number.toLong(), 0)
            else -> parse(number.toDouble().toString()) ?: Decimal(number.toLong(), 0)
        }

        /** `"12"`, `"-1.50"`, `"1.5E3"`; null for anything else. */
        fun parse(text: String): Decimal? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val e = t.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (e >= 0) t.substring(0, e) else t
            val exponent = if (e >= 0) t.substring(e + 1).removePrefix("+").toIntOrNull() ?: return null else 0
            val negative = mantissa.startsWith('-')
            val body = mantissa.removePrefix("-").removePrefix("+")
            val dot = body.indexOf('.')
            val whole = if (dot >= 0) body.substring(0, dot) else body
            val fraction = if (dot >= 0) body.substring(dot + 1) else ""
            val digits = (whole + fraction).ifEmpty { return null }
            if (!digits.all { it.isDigit() } || digits.length > 18) return null
            var unscaled = digits.toLong()
            var scale = fraction.length - exponent
            if (scale < 0) {
                unscaled *= pow10(-scale)
                scale = 0
            }
            return Decimal(if (negative) -unscaled else unscaled, scale)
        }
    }
}
