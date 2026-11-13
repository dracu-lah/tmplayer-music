package com.tmplayer.music.cast

/** What the receiver's queue becomes after a reorder: the [window] to send, where playback is in it, and the [upcoming] rest in play order. */
data class CastLayout(val window: List<String>, val startIndex: Int, val upcoming: List<String>)

/**
 * Shuffle for a cast queue, pure. The receiver has no shuffle of its own, so turning shuffle on or off
 * while casting re-orders the queue the phone keeps ([CastRecord] on the phone's side) and sends the
 * receiver a new window of it. The order is a function of the queue, the playing track and a seed, so
 * the same choice always gives the same order and a test can pin it. Items are named by their ids,
 * which must be unique in the queue: with a repeated id there is no order to compute and every
 * function here answers null.
 */
object CastReorder {

    /** A seed that depends only on the queue ([original] ids in queue order), the same every time. */
    fun seedOf(original: List<String>): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (id in original) {
            for (ch in id) {
                hash = (hash xor ch.code.toLong()) * 0x100000001b3L
            }
            hash = (hash xor 0x1f) * 0x100000001b3L
        }
        return hash
    }

    /** [ids] in a seeded random order (Fisher-Yates over SplitMix64), the same for the same input. */
    fun shuffled(ids: List<String>, seed: Long): List<String> {
        val out = ids.toMutableList()
        var state = seed
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        for (i in out.lastIndex downTo 1) {
            val j = ((next() ushr 1) % (i + 1)).toInt()
            val t = out[i]
            out[i] = out[j]
            out[j] = t
        }
        return out
    }

    /**
     * [ids] in the order of [original]; ids the original does not have (added while casting) keep
     * their relative order and go after the others.
     */
    fun inQueueOrder(ids: List<String>, original: List<String>): List<String> {
        val at = HashMap<String, Int>(original.size * 2)
        original.forEachIndexed { index, id -> at.putIfAbsent(id, index) }
        return ids.withIndex().sortedWith(compareBy({ at[it.value] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /**
     * The play order of the cast queue [all] (what is on the receiver plus what waits to be sent) with
     * [currentId] playing: with [shuffle] on, the playing track first and the rest in the seeded order;
     * off, the queue's own order ([original]). Null when [currentId] is not in [all] or an id repeats.
     */
    fun order(all: List<String>, currentId: String, shuffle: Boolean, original: List<String>, seed: Long): List<String>? {
        if (currentId !in all || all.toSet().size != all.size) return null
        val plain = inQueueOrder(all, original)
        if (!shuffle) return plain
        return listOf(currentId) + shuffled(plain.filter { it != currentId }, seed)
    }

    /** Cuts [order] into the window around [currentId] (as [CastQueue.plan] does) and the rest. Null when it is not in [order]. */
    fun layout(order: List<String>, currentId: String, window: Int = CastQueue.WINDOW, before: Int = CastQueue.BEFORE): CastLayout? {
        val at = order.indexOf(currentId)
        if (at < 0) return null
        val from = (at - before.coerceAtLeast(0)).coerceAtLeast(0)
        val to = minOf(order.size, from + window.coerceAtLeast(1))
        return CastLayout(order.subList(from, to).toList(), at - from, order.subList(to, order.size).toList())
    }

    /**
     * The ids the phone counts as sent after a reorder: the old [sent] set minus every id still in the
     * queue ([all]) that is not in the new [window]. Something the user removed (in [sent], not in
     * [all]) stays removed; something that left the receiver but is still in the queue folds back
     * at the end like any track that was never sent.
     */
    fun sentAfter(sent: Set<String>, all: List<String>, window: List<String>): Set<String> {
        val keep = window.toHashSet()
        val inQueue = all.toHashSet()
        return sent.filterTo(HashSet()) { it in keep || it !in inQueue }.also { it += keep }
    }
}
