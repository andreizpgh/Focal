package app.focal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

enum class Symbol { Back, Settings, More, Mic, Pause, Stop, Play, Copy, Check, Close, Arrow, Logo, Share, Trash }

@Composable fun Glyph(symbol: Symbol, modifier: Modifier = Modifier.size(24.dp), color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val s = Stroke(1.6f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x,y), Offset(xx,yy), 1.6f, StrokeCap.Round)
            when (symbol) {
                Symbol.Back -> { line(19f,12f,5f,12f); line(5f,12f,11f,6f); line(5f,12f,11f,18f) }
                Symbol.Arrow -> { line(5f,12f,19f,12f); line(19f,12f,13f,6f); line(19f,12f,13f,18f) }
                Symbol.More -> listOf(5f,12f,19f).forEach { drawCircle(color,1.7f,Offset(it,12f)) }
                Symbol.Close -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                Symbol.Check -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
                Symbol.Pause -> { drawRoundRect(color,Offset(6f,4f),Size(4f,16f),CornerRadius(1f)); drawRoundRect(color,Offset(14f,4f),Size(4f,16f),CornerRadius(1f)) }
                Symbol.Stop -> drawRoundRect(color,Offset(5f,5f),Size(14f,14f),CornerRadius(3f))
                Symbol.Play -> drawPath(Path().apply { moveTo(8f,4f); lineTo(20f,12f); lineTo(8f,20f); close() }, color)
                Symbol.Mic -> {
                    drawRoundRect(color,Offset(9f,3f),Size(6f,12f),CornerRadius(3f),style=s)
                    drawArc(color,0f,180f,false,Offset(5f,6f),Size(14f,13f),style=s)
                    line(12f,19f,12f,22f)
                }
                Symbol.Settings -> {
                    line(3f,7f,21f,7f); line(3f,17f,21f,17f)
                    drawCircle(color,3f,Offset(9f,7f)); drawCircle(color,3f,Offset(16f,17f))
                }
                Symbol.Copy -> { drawRoundRect(color,Offset(8f,7f),Size(12f,14f),CornerRadius(2f),style=s); line(4f,17f,4f,3f); line(4f,3f,16f,3f) }
                Symbol.Share -> { line(12f,16f,12f,3f); line(12f,3f,7f,8f); line(12f,3f,17f,8f); line(4f,13f,4f,21f); line(4f,21f,20f,21f); line(20f,21f,20f,13f) }
                Symbol.Trash -> { line(4f,6f,20f,6f); line(7f,6f,8f,21f); line(8f,21f,16f,21f); line(16f,21f,17f,6f); line(9f,3f,15f,3f) }
                Symbol.Logo -> drawPath(Path().apply {
                    moveTo(4f,2f); lineTo(22f,2f); lineTo(22f,6f); lineTo(9f,6f)
                    lineTo(9f,8f); lineTo(4f,11f); close()
                    moveTo(4f,14f); lineTo(9f,11f); lineTo(18f,11f); lineTo(18f,15f)
                    lineTo(9f,15f); lineTo(9f,23f); lineTo(4f,23f); close()
                },color)
            }
        }
    }
}
