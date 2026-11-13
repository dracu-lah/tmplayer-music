package com.tmplayer.music.desktop.ui.common

/**
 * Which rows of a list are selected, as a desktop list does it: a click selects one row, Ctrl (or
 * Cmd) adds or removes a row, Shift takes everything from the row last clicked (the anchor) to this
 * one, Ctrl+A takes every row read so far. Plain values, so the rules are unit tests; the rows are
 * identified by [K] (a song's key), never by position, because a list that is paged and refreshed
 * moves under the selection.
 */
data class ListSelection<K>(
    val selected: Set<K> = emptySet(),
    val anchor: K? = null,
) {
    val isEmpty: Boolean get() = selected.isEmpty()
    val count: Int get() = selected.size

    operator fun contains(key: K): Boolean = key in selected

    /** A click on [key] in a list showing [order], with the modifier keys held. */
    fun click(key: K, order: List<K>, ctrl: Boolean = false, shift: Boolean = false): ListSelection<K> = when {
        shift -> {
            val from = anchor?.let(order::indexOf)?.takeIf { it >= 0 }
            val to = order.indexOf(key)
            if (from == null || to < 0) {
                ListSelection(setOf(key), key)
            } else {
                val range = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
                // Ctrl+Shift adds the range to what is selected; Shift alone replaces it. The anchor stays.
                ListSelection(if (ctrl) selected + range else range, anchor)
            }
        }
        ctrl -> ListSelection(if (key in selected) selected - key else selected + key, key)
        else -> ListSelection(setOf(key), key)
    }

    /** Ctrl+A: every row [order] holds. */
    fun selectAll(order: List<K>): ListSelection<K> =
        if (order.isEmpty()) this else ListSelection(order.toSet(), anchor?.takeIf { it in order } ?: order.first())

    fun clear(): ListSelection<K> = ListSelection()

    /** The selection without the rows [order] no longer holds (the list changed under it). */
    fun prune(order: List<K>): ListSelection<K> {
        if (selected.isEmpty()) return this
        val present = order.toHashSet()
        val kept = selected.filterTo(HashSet()) { it in present }
        if (kept.size == selected.size) return this
        return ListSelection(kept, anchor?.takeIf { it in present })
    }

    /** The selected rows in list order, for what Play and Add to queue do with them. */
    fun inOrder(order: List<K>): List<K> = order.filter { it in selected }
}
