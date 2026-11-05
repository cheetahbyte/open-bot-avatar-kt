package re.leob.openbotavatar.kt.compose

import androidx.collection.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.PathParser
import re.leob.openbotavatar.kt.AvatarFrame
import re.leob.openbotavatar.kt.AvatarMatrix
import re.leob.openbotavatar.kt.EyeMark
import re.leob.openbotavatar.kt.FrameShape

private const val MAX_SECTIONS = 4096

/** Parsed cross-sections, by the frame's path ids: equal geometry and depth share them. Main thread only. */
private val sections = LruCache<String, Path>(MAX_SECTIONS)

private fun section(id: String, d: String, evenOdd: Boolean): Path =
    sections[id] ?: PathParser().parsePathString(d).toPath().also {
        it.fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        sections.put(id, it)
    }

/** Where an avatar draws inside its layout bounds. */
internal fun Rect.centeredSquare(): Rect = Rect(center, minDimension / 2)

/** Scratch objects for one avatar's draws, reused so a frame allocates no paint or matrix. */
internal class AvatarPainter {
    private val fill = Paint()
    private val stroke = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
    }
    private val arc = Path()
    private val matrix = Matrix()

    fun paint(scope: DrawScope, frame: AvatarFrame, color: Color, eyeColor: Color) {
        val square = Rect(Offset.Zero, scope.size).centeredSquare()
        if (square.isEmpty) return
        val paths = frame.paths.associateBy { it.id }
        scope.drawIntoCanvas { canvas ->
            for (shape in frame.shapes) {
                canvas.save()
                place(frame.root * shape.matrix, square.topLeft, square.width / 100f)
                canvas.concat(matrix)
                when (shape) {
                    is FrameShape.Slice -> {
                        val path = paths.getValue(shape.path)
                        fill.color = color
                        canvas.drawPath(section(path.id, path.d, path.evenOdd), fill)
                    }
                    is FrameShape.Eye -> eye(canvas, shape.mark, eyeColor)
                }
                canvas.restore()
            }
        }
    }

    private fun place(m: AvatarMatrix, topLeft: Offset, scale: Float) {
        matrix.reset()
        matrix[0, 0] = m.a.toFloat() * scale
        matrix[0, 1] = m.b.toFloat() * scale
        matrix[1, 0] = m.c.toFloat() * scale
        matrix[1, 1] = m.d.toFloat() * scale
        matrix[3, 0] = m.e.toFloat() * scale + topLeft.x
        matrix[3, 1] = m.f.toFloat() * scale + topLeft.y
    }

    private fun eye(canvas: Canvas, mark: EyeMark, ink: Color) {
        when (mark) {
            is EyeMark.Pill -> {
                fill.color = ink
                val radius = mark.radius.toFloat()
                canvas.drawRoundRect(
                    mark.x.toFloat(),
                    mark.y.toFloat(),
                    (mark.x + mark.width).toFloat(),
                    (mark.y + mark.height).toFloat(),
                    radius,
                    radius,
                    fill,
                )
            }
            is EyeMark.Arc -> {
                stroke.color = ink
                stroke.strokeWidth = mark.strokeWidth.toFloat()
                arc.reset()
                arc.moveTo(mark.startX.toFloat(), mark.startY.toFloat())
                arc.quadraticTo(
                    mark.controlX.toFloat(),
                    mark.controlY.toFloat(),
                    mark.endX.toFloat(),
                    mark.endY.toFloat(),
                )
                canvas.drawPath(arc, stroke)
            }
        }
    }
}

/**
 * Paint [frame] in the largest centered square of this scope. For custom hosts that compute frames
 * themselves; the frame's own colors are hex, so themes pass theirs here.
 */
public fun DrawScope.drawAvatarFrame(frame: AvatarFrame, color: Color, eyeColor: Color) {
    AvatarPainter().paint(this, frame, color, eyeColor)
}
