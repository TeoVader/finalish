package dev.finalish.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldDefinitionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun selectFieldListsItsOptions() {
        val f = json.decodeFromString(
            FieldDefinition.serializer(),
            """{"id":"1","name":"Scene","field_type":"select","mutable":true,
               "field_configuration":{"enable_add_new":true,"options":[{"id":"a","display_name":"Day","color":null},{"id":"b","display_name":"Night"}]}}""",
        )
        assertEquals(listOf("Day", "Night"), f.options)
        assertEquals("Single select · 2 options", f.summary)
    }

    @Test fun ratingAndPlainSummaries() {
        val r = json.decodeFromString(
            FieldDefinition.serializer(),
            """{"id":"2","name":"Score","field_type":"rating","mutable":true,"field_configuration":{"max_value":5,"style":"heart"}}""",
        )
        assertEquals("Rating · 5 hearts", r.summary)
        assertEquals("Long text", FieldDefinition("3", "Notes", "long_text").summary)
    }
}
