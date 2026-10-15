package dev.finalish.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.finalish.core.network.AppJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire format here is what Frame.io's V4 API validates (see the 422s it returns). */
class AnnotationCodecTest {

    private val red = Color(0xFFF22237)

    @Test fun emptyEncodesToNull() = assertNull(AnnotationCodec.encode(emptyList()))

    @Test fun penUsesParallelCoordinateArrays() {
        val pen = Shape(DrawTool.Pen, red, 6f, listOf(Offset(0.1f, 0.2f), Offset(0.3f, 0.4f), Offset(0.5f, 0.1f)))
        val o = AppJson.parseToJsonElement(AnnotationCodec.encode(listOf(pen))!!).jsonArray[0].jsonObject
        assertEquals("pen", o["tool"]!!.jsonPrimitive.content)
        assertEquals("#F22237", o["color"]!!.jsonPrimitive.content)
        assertEquals(3, o["xs"]!!.jsonArray.size)
        assertEquals(3, o["ys"]!!.jsonArray.size)
        assertEquals(0.1, o["x"]!!.jsonPrimitive.content.toDouble(), 1e-6) // bounding box
    }

    @Test fun lineUsesEndpoints() {
        val line = Shape(DrawTool.Line, red, 6f, listOf(Offset(0.1f, 0.1f), Offset(0.9f, 0.5f)))
        val o = AppJson.parseToJsonElement(AnnotationCodec.encode(listOf(line))!!).jsonArray[0].jsonObject
        assertTrue(listOf("x1", "y1", "x2", "y2").all { it in o })
        assertEquals(0.9, o["x2"]!!.jsonPrimitive.content.toDouble(), 1e-6)
    }

    @Test fun roundTripKeepsToolsAndGeometry() {
        val shapes = listOf(
            Shape(DrawTool.Pen, red, 6f, listOf(Offset(0.1f, 0.2f), Offset(0.3f, 0.4f))),
            Shape(DrawTool.Rect, red, 4f, listOf(Offset(0.2f, 0.2f), Offset(0.6f, 0.7f))),
            Shape(DrawTool.Line, red, 4f, listOf(Offset(0.1f, 0.1f), Offset(0.9f, 0.5f))),
            Shape(DrawTool.Arrow, red, 4f, listOf(Offset(0.3f, 0.3f), Offset(0.8f, 0.6f))),
        )
        val back = AnnotationCodec.decode(AnnotationCodec.encode(shapes))
        assertEquals(shapes.map { it.tool }, back.map { it.tool })
        back.zip(shapes).forEach { (b, s) ->
            assertEquals(s.points.first().x, b.points.first().x, 1e-4f)
            assertEquals(s.points.last().y, b.points.last().y, 1e-4f)
        }
    }

    @Test fun garbageDecodesToNothing() {
        assertEquals(emptyList<Shape>(), AnnotationCodec.decode("not json"))
        assertEquals(emptyList<Shape>(), AnnotationCodec.decode(null))
    }
}
