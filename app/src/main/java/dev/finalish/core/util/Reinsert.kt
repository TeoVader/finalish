package dev.finalish.core.util

/**
 * Undoes an optimistic removal of [item]: puts it back where it was in [before], among what
 * [list] holds now, so other changes made meanwhile (another removal that worked) stay.
 */
fun <T, K> reinsert(list: List<T>, before: List<T>, item: T, key: (T) -> K): List<T> {
    val k = key(item)
    if (list.any { key(it) == k }) return list
    val still = list.map(key).toSet()
    val at = before.takeWhile { key(it) != k }.count { key(it) in still }
    return list.toMutableList().apply { add(at.coerceAtMost(size), item) }
}
