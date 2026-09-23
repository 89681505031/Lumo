package app.lumo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Original vector interpretation of the approved Lumo planet-and-orbit logo. */
@Composable
fun LumoPlanetIcon(size: Dp = 132.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * .27f)
    Box(
        modifier = modifier.size(size)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF48D8FF), Color(0xFF384CE8), Color(0xFFEF69DA))
                ), shape
            )
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFFD1FFFF), Color(0xFF8BEAFF), Color(0xFFFFC4FA))
                ),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * .84f)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension * .33f
            // Planet: deep-blue sphere with a cyan-lit upper edge.
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF64E6FF), Color(0xFF0865DC), Color(0xFF17257F))
                ),
                radius = r,
                center = c
            )
            // The ring is deliberately visible outside the sphere.
            rotate(-24f, c) {
                drawOval(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF7BEAFF), Color.White, Color(0xFFFF91F0))
                    ),
                    topLeft = Offset(c.x - r * 1.48f, c.y - r * .49f),
                    size = Size(r * 2.96f, r * .98f),
                    style = Stroke(width = this.size.minDimension * .065f, cap = StrokeCap.Round)
                )
            }
            drawCircle(Color(0xFFE7F9FF), radius = r * .14f, center = Offset(c.x + r * 1.33f, c.y - r * .78f))
            drawCircle(Color.White.copy(alpha = .34f), radius = r * .44f, center = Offset(c.x - r * .34f, c.y - r * .37f))
        }
    }
}
