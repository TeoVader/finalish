package dev.finalish.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.graphics.toColorInt
import dev.finalish.core.network.AppJson
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class DrawTool(val wire: String, @androidx.annotation.StringRes val labelRes: Int) {
    Pen("pen", dev.finalish.R.string.viewer_tool_pen),
    Line("line", dev.finalish.R.string.viewer_tool_line),
    Arrow("arrow", dev.finalish.R.string.viewer_tool_arrow),
    Rect("rect", dev.finalish.R.string.viewer_tool_rect),
}

/** One annotation shape. Coordinates are normalized to the media frame (0..1). */
@Immutable
data class Shape(
    val tool: DrawTool,
    val color: Color,
    val size: Float,
    val points: List<Offset>, // pen: all points; others: [start, end]
)

/**
 * Frame.io stores on-screen drawings as a stringified JSON array, e.g.
 * `[{"tool":"rect","color":"#F22237","size":8,"x":0.27,"y":0.12,"w":0.31,"h":0.53,...}]`.
 * The parser is deliberately tolerant so drawings made in other clients still render.
 */
object AnnotationCodec {

    fun decode(raw: String?): List<Shape> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> runCatching { decodeShape(el.jsonObject) }.getOrNull() }
    }

    private fun decodeShape(o: JsonObject): Shape? {
        val toolName = o["tool"]?.jsonPrimitive?.content?.lowercase() ?: return null
        val tool = when (toolName) {
            "rect", "rectangle", "box" -> DrawTool.Rect
            "arrow" -> DrawTool.Arrow
            "line" -> DrawTool.Line
            "pen", "freehand", "brush", "draw", "path" -> DrawTool.Pen
            else -> return null
        }
        val color = parseColor(o["color"]?.jsonPrimitive?.content)
        val size = o["size"]?.jsonPrimitive?.floatOrNull ?: 8f
        fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull
        val points: List<Offset> = when (tool) {
            DrawTool.Pen -> penPoints(o)
            DrawTool.Line -> if (f("x1") != null) {
                listOf(
                    Offset(f("x1")!!, f("y1") ?: return null),
                    Offset(f("x2") ?: return null, f("y2") ?: return null),
                )
            } else {
                val x = f("x") ?: return null
                val y = f("y") ?: return null
                listOf(Offset(x, y), Offset(x + (f("w") ?: 0f), y + (f("h") ?: 0f)))
            }
            else -> {
                val x = f("x") ?: return null
                val y = f("y") ?: return null
                val w = f("w") ?: 0f
                val h = f("h") ?: 0f
                listOf(Offset(x, y), Offset(x + w, y + h))
            }
        }
        if (points.isEmpty()) return null
        return Shape(tool, color, size, points)
    }

    private fun penPoints(o: JsonObject): List<Offset> {
        o["points"]?.let { p ->
            return p.jsonArray.mapNotNull { pt ->
                when (pt) {
                    is JsonObject -> {
                        val x = pt["x"]?.jsonPrimitive?.floatOrNull
                        val y = pt["y"]?.jsonPrimitive?.floatOrNull
                        if (x != null && y != null) Offset(x, y) else null
                    }
                    is JsonArray -> {
                        val x = pt.getOrNull(0)?.jsonPrimitive?.floatOrNull
                        val y = pt.getOrNull(1)?.jsonPrimitive?.floatOrNull
                        if (x != null && y != null) Offset(x, y) else null
                    }
                    else -> null
                }
            }
        }
        val xs = o["xs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }
        val ys = o["ys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull }
        if (xs != null && ys != null) return xs.zip(ys) { x, y -> Offset(x, y) }
        return emptyList()
    }

    fun encode(shapes: List<Shape>): String? {
        if (shapes.isEmpty()) return null
        val arr = buildJsonArray {
            shapes.forEach { s ->
                val minX = s.points.minOf { it.x }
                val minY = s.points.minOf { it.y }
                val maxX = s.points.maxOf { it.x }
                val maxY = s.points.maxOf { it.y }
                add(buildJsonObject {
                    put("tool", s.tool.wire)
                    put("color", hex(s.color))
                    put("size", s.size)
                    when (s.tool) {
                        DrawTool.Pen -> {
                            put("x", minX); put("y", minY); put("w", maxX - minX); put("h", maxY - minY)
                            put("ix", s.points.first().x); put("iy", s.points.first().y)
                            // Frame.io stores freehand strokes as parallel coordinate arrays.
                            put("xs", buildJsonArray { s.points.forEach { add(JsonPrimitive(it.x)) } })
                            put("ys", buildJsonArray { s.points.forEach { add(JsonPrimitive(it.y)) } })
                        }
                        DrawTool.Rect -> {
                            put("x", minX); put("y", minY); put("w", maxX - minX); put("h", maxY - minY)
                            put("ix", s.points[0].x); put("iy", s.points[0].y)
                            put("radius", 8)
                        }
                        DrawTool.Line -> {
                            // Lines use endpoint coordinates (validated by the V4 API).
                            put("x1", s.points.first().x); put("y1", s.points.first().y)
                            put("x2", s.points.last().x); put("y2", s.points.last().y)
                        }
                        DrawTool.Arrow -> {
                            val a = s.points.first()
                            val b = s.points.last()
                            put("x", a.x); put("y", a.y); put("w", b.x - a.x); put("h", b.y - a.y)
                            put("ix", a.x); put("iy", a.y)
                        }
                    }
                })
            }
        }
        return arr.toString()
    }

    private fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

    private fun parseColor(s: String?): Color = runCatching {
        Color((s ?: "#F22237").toColorInt())
    }.getOrDefault(Color(0xFFF22237))
}

/** Stroke width scales with the rendered frame so drawings look the same at any size. */
private fun DrawScope.strokeWidth(s: Shape) = (s.size / 1000f * size.width).coerceIn(2f, 18f)

fun DrawScope.drawShape(s: Shape) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = strokeWidth(s), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val pts = s.points.map { Offset(it.x * w, it.y * h) }
    when (s.tool) {
        DrawTool.Pen -> {
            if (pts.size == 1) {
                drawCircle(s.color, stroke.width / 2, pts[0])
                return
            }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    // Quadratic smoothing between midpoints for silky strokes.
                    val mid = (pts[i - 1] + pts[i]) / 2f
                    quadraticTo(pts[i - 1].x, pts[i - 1].y, mid.x, mid.y)
                }
                lineTo(pts.last().x, pts.last().y)
            }
            drawPath(path, s.color, style = stroke)
        }
        DrawTool.Line -> drawLine(s.color, pts.first(), pts.last(), stroke.width, StrokeCap.Round)
        DrawTool.Arrow -> {
            val a = pts.first()
            val b = pts.last()
            drawLine(s.color, a, b, stroke.width, StrokeCap.Round)
            val angle = atan2(b.y - a.y, b.x - a.x)
            val head = stroke.width * 4.2f
            val spread = 0.5f
            val p1 = Offset(b.x - head * cos(angle - spread), b.y - head * sin(angle - spread))
            val p2 = Offset(b.x - head * cos(angle + spread), b.y - head * sin(angle + spread))
            drawLine(s.color, b, p1, stroke.width, StrokeCap.Round)
            drawLine(s.color, b, p2, stroke.width, StrokeCap.Round)
        }
        DrawTool.Rect -> {
            val a = pts.first()
            val b = pts.last()
            val tl = Offset(min(a.x, b.x), min(a.y, b.y))
            val sz = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
            drawRoundRect(s.color, tl, sz, androidx.compose.ui.geometry.CornerRadius(stroke.width * 1.2f), style = stroke)
        }
    }
}

/** Read-only rendering of saved annotations. */
@Composable
fun AnnotationLayer(shapes: List<Shape>, modifier: Modifier = Modifier) {
    if (shapes.isEmpty()) return
    Canvas(modifier.fillMaxSize()) { shapes.forEach { drawShape(it) } }
}

/** Interactive drawing surface. [shapes] is hoisted so the composer owns the drawing. */
@Composable
fun DrawingLayer(
    shapes: List<Shape>,
    tool: DrawTool,
    color: Color,
    onShapeAdded: (Shape) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<Shape?>(null) }
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(tool, color) {
                fun norm(o: Offset) = Offset(
                    (o.x / size.width).coerceIn(0f, 1f),
                    (o.y / size.height).coerceIn(0f, 1f),
                )
                detectDragGestures(
                    onDragStart = { start ->
                        val p = norm(start)
                        current = Shape(tool, color, 8f, listOf(p, p))
                    },
                    onDragEnd = {
                        current?.let { s ->
                            val shape = if (s.tool == DrawTool.Pen) s.copy(points = s.points.drop(1)) else s
                            onShapeAdded(shape)
                        }
                        current = null
                    },
                    onDragCancel = { current = null },
                ) { change, _ ->
                    change.consume()
                    val p = norm(change.position)
                    current = current?.let { s ->
                        if (s.tool == DrawTool.Pen) s.copy(points = s.points + p)
                        else s.copy(points = listOf(s.points.first(), p))
                    }
                }
            },
    ) {
        shapes.forEach { drawShape(it) }
        current?.let { drawShape(it) }
    }
}
