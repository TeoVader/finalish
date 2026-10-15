package dev.finalish.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReinsertTest {
    private fun put(list: List<String>, before: List<String>, item: String) = reinsert(list, before, item) { it }

    @Test fun backInItsPlace() = assertEquals(listOf("a", "b", "c"), put(listOf("a", "c"), listOf("a", "b", "c"), "b"))

    @Test fun keepsAnotherRemovalThatWorked() =
        // "a" was removed for good meanwhile; undoing "c" must not bring "a" back.
        assertEquals(listOf("b", "c", "d"), put(listOf("b", "d"), listOf("a", "b", "c", "d"), "c"))

    @Test fun firstAndLast() {
        assertEquals(listOf("a", "b"), put(listOf("b"), listOf("a", "b"), "a"))
        assertEquals(listOf("a", "b"), put(listOf("a"), listOf("a", "b"), "b"))
    }

    @Test fun alreadyThere() = assertEquals(listOf("a", "b"), put(listOf("a", "b"), listOf("a", "b"), "b"))
}
