package dev.finalish.ui.browser

import dev.finalish.core.model.Asset
import dev.finalish.core.model.MetadataField
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderQueryTest {
    private fun status(name: String) = MetadataField(
        fieldType = "select", name = "Status",
        value = buildJsonArray { add(buildJsonObject { put("id", name); put("display_name", name) }) },
    )
    private fun file(name: String, bitrate: Double?, status: String? = null) = Asset(
        id = name, type = "file", name = name,
        metadata = listOfNotNull(
            bitrate?.let { MetadataField(fieldType = "number", name = "Bit Rate", value = JsonPrimitive(it)) },
            status?.let(::status),
        ),
    )

    private val a = file("a.mp4", 30_000_000.0, "Approved")
    private val b = file("b.mp4", 5_000_000.0, "Needs Review")
    private val c = file("c.mp4", null)

    @Test fun numberFilters() {
        val f = FieldFilter("Bit Rate", FilterOp.Greater, "10000000")
        assertEquals(listOf(a), listOf(a, b, c).filter { f.matches(it) })
        assertEquals(listOf(c), listOf(a, b, c).filter { FieldFilter("Bit Rate", FilterOp.Empty).matches(it) })
    }

    @Test fun choiceFilters() {
        assertEquals(listOf(a), listOf(a, b, c).filter { FieldFilter("Status", FilterOp.Is, "approved").matches(it) })
        assertEquals(listOf(b, c), listOf(a, b, c).filter { FieldFilter("Status", FilterOp.IsNot, "Approved").matches(it) })
        assertEquals(listOf(b), listOf(a, b, c).filter { FieldFilter(NAME_FIELD, FilterOp.Contains, "B.MP").matches(it) })
    }

    @Test fun sortByFieldKeepsEmptiesLast() {
        assertEquals(listOf(b, a, c), listOf(c, a, b).sortedWith(fieldComparator("Bit Rate", descending = false)))
        assertEquals(listOf(a, b, c), listOf(c, b, a).sortedWith(fieldComparator("Bit Rate", descending = true)))
    }

    @Test fun fieldsListed() {
        val fields = fieldsIn(listOf(a, b, c))
        assertEquals(listOf(NAME_FIELD, "Bit Rate", "Status"), fields.map { it.name })
        assertEquals(listOf("Approved", "Needs Review"), fields.first { it.name == "Status" }.options)
    }
}
