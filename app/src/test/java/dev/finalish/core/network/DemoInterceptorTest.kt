package dev.finalish.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo mode answers the people, groups, fields and template screens, and keeps edits. */
class DemoInterceptorTest {
    private val api = Network.api(OkHttpClient.Builder().addInterceptor(DemoInterceptor { true }).build())

    @Test fun peopleAndGroups() = runBlocking {
        assertEquals(6, api.accountUsers("acc-demo").data.size)
        assertEquals(4, api.members("acc-demo", "projects", "p1").data.size)
        val groups = api.groups("acc-demo").data
        assertEquals(listOf("Client", "Editors", "Sound team"), groups.map { it.name })
        assertEquals(3, api.groupUsers("acc-demo", "g1").data.size)
        assertEquals("editor", api.scopeGroups("acc-demo", "projects", "p1").data.first().role)

        val g = api.createGroup("acc-demo", buildJsonObject { putJsonObject("data") { put("name", "Color"); put("emoji", "🎨") } }).data
        api.addGroupUsers("acc-demo", g.id, buildJsonObject { putJsonObject("data") { putJsonArray("user_ids") { add(JsonPrimitive("u2")) } } })
        assertEquals(1, api.groups("acc-demo").data.first { it.id == g.id }.memberCount)
        val renamed = api.updateGroup("acc-demo", g.id, buildJsonObject { putJsonObject("data") { put("name", "Grading"); put("emoji", "🎨") } }).data
        assertEquals("Grading", renamed.name)
        api.deleteGroup("acc-demo", g.id)
        assertTrue(api.groups("acc-demo").data.none { it.id == g.id })
    }

    @Test fun fieldsAndTemplates() = runBlocking {
        val fields = api.fieldDefinitions("acc-demo").data
        assertEquals(listOf("Exterior", "Interior", "Day", "Night"), fields.first { it.name == "Scene" }.options)
        val f = api.createFieldDefinition("acc-demo", buildJsonObject { putJsonObject("data") { put("field_type", "text"); put("name", "Lens") } }).data
        assertEquals("Lens", api.updateFieldDefinition("acc-demo", f.id, buildJsonObject { putJsonObject("data") { put("field_type", "text"); put("name", "Lens") } }).data.name)
        api.deleteFieldDefinition("acc-demo", f.id)
        assertEquals(fields.size, api.fieldDefinitions("acc-demo").data.size)
        assertEquals("Email · Time · bottom right", api.watermarkTemplates("acc-demo").data.first().summary)
        assertTrue(api.fileStatus("acc-demo", "x").data.uploadComplete)
    }
}
