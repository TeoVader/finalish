package dev.finalish.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.finalish.ui.theme.Palette

/**
 * The Final(ish) mark: nested parentheses (past versions, fading outward) around a check (the current one).
 * Drawn on a 100×100 grid; [pairs] = 3 for large sizes, 2 below ~72 dp so it stays crisp.
 */
@Composable
fun BrandMark(
    size: Dp,
    modifier: Modifier = Modifier,
    pairs: Int = if (size >= 72.dp) 3 else 2,
    arcColor: Color = Palette.Accent,
    checkColor: Color = Palette.Text,
) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 100f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val arcs = if (pairs >= 3) listOf(
                Triple(paren(32f, 30f, 8f, 40f), 7f, 1f),
                Triple(paren(23f, 24f, 11f, 52f), 6f, 0.55f),
                Triple(paren(14f, 18f, 14f, 64f), 5f, 0.25f),
            ) else listOf(
                Triple(paren(34f, 31f, 8f, 38f), 8.5f, 1f),
                Triple(paren(22f, 23f, 12f, 54f), 7.5f, 0.5f),
            )
            arcs.forEach { (p, w, a) ->
                drawPath(p, arcColor.copy(alpha = arcColor.alpha * a), style = Stroke(w, cap = StrokeCap.Round))
            }
            val check = Path().apply { moveTo(40f, 51f); relativeLineTo(7f, 7f); relativeLineTo(13f, -15f) }
            drawPath(check, checkColor, style = Stroke(if (pairs >= 3) 7f else 8.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A "(" at [x] and its mirrored ")" on the 100-wide grid; [bulge] is how far the curve bows out. */
private fun paren(x: Float, top: Float, bulge: Float, height: Float): Path = Path().apply {
    val k = height * 0.3f
    moveTo(x, top)
    cubicTo(x - bulge, top + k, x - bulge, top + height - k, x, top + height)
    val rx = 100f - x
    moveTo(rx, top)
    cubicTo(rx + bulge, top + k, rx + bulge, top + height - k, rx, top + height)
}
