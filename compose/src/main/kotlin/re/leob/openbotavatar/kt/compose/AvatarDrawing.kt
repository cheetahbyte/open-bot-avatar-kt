package re.leob.openbotavatar.kt.compose

import androidx.collection.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.LinearGradientShader
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
import re.leob.openbotavatar.kt.FramePath
import re.leob.openbotavatar.kt.FrameShape

private const val MAX_SECTIONS = 4096

/** A body at this share of its size or less is gone. */
private const val GONE = 0.001

private class Section(val d: String, val path: Path)

/** Parsed cross-sections, by the frame's path ids: equal geometry and depth share them. Main thread only. */
private val sections = LruCache<String, Section>(MAX_SECTIONS)

/** A mark's part may come with another outline under its id (a host's mark is its own code): then it is made anew. */
private fun section(part: FramePath): Path {
    sections[part.id]?.takeIf { it.d == part.d }?.let { return it.path }
    val path = parsed(part.d)
    path.fillType = if (part.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
    sections.put(part.id, Section(part.d, path))
    return path
}

/** An outline that is no path data, which only a host's mark can be, draws nothing. */
private fun parsed(d: String): Path = try {
    PathParser().parsePathString(d).toPath()
} catch (_: IllegalArgumentException) {
    Path()
}

/** The marks, which a frame lists as slices in place of the body. */
private val FrameShape.Slice.isMark get() = key.startsWith("mark-")

/** Where an avatar draws inside its layout bounds. */
internal fun Rect.centeredSquare(): Rect = Rect(center, minDimension / 2)

/** Scratch objects for one avatar's draws, reused so a frame allocates no paint or matrix. */
internal class AvatarPainter {
    private val fill = Paint()
    private val stroke = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
    }
    private val imagePaint = Paint()
    private val arc = Path()
    private val dynamicParser = PathParser()
    private val matrix = Matrix()

    fun paint(scope: DrawScope, frame: AvatarFrame, color: Color, eyeColor: Color, material: AvatarMaterial?) {
        val square = Rect(Offset.Zero, scope.size).centeredSquare()
        if (square.isEmpty) return
        val scale = square.width / 100f
        val paths = frame.paths.associateBy { it.id }
        val body = material?.let { body(it, frame, color, eyeColor, scale) }
        var bodyPainted = false
        scope.drawIntoCanvas { canvas ->
            for (shape in frame.shapes) {
                if (body != null && shape is FrameShape.Eye) continue
                // The body's slices follow one another: its image takes the place of the first, under the root's motion.
                val inBody = body != null && shape is FrameShape.Slice && !shape.isMark
                if (inBody && bodyPainted) continue
                canvas.save()
                place(if (inBody) frame.root else frame.root * shape.matrix, square.topLeft, scale)
                canvas.concat(matrix)
                when (shape) {
                    is FrameShape.Slice -> if (inBody) {
                        bodyPainted = true
                        image(canvas, body, body.cx, body.cy, body.radius * frame.surface.scale)
                    } else {
                        slice(canvas, paths.getValue(shape.path), color, material.takeIf { shape.isMark }, scale)
                    }
                    is FrameShape.Eye -> eye(canvas, shape.mark, eyeColor)
                    is FrameShape.Ribbon -> {
                        val g = shape.gradient
                        fill.color = Color.White
                        fill.shader = LinearGradientShader(
                            Offset(g.x1.toFloat(), g.y1.toFloat()), Offset(g.x2.toFloat(), g.y2.toFloat()),
                            g.stops.map { Color(android.graphics.Color.parseColor(it)).copy(alpha = color.alpha) },
                        )
                        canvas.drawPath(dynamicParser.parsePathString(shape.d).toPath(), fill)
                        fill.shader = null
                    }
                }
                canvas.restore()
            }
        }
    }

    /** The whole body and its eyes as one image, unless it has shrunk away into a mark or has no surface. */
    private fun body(material: AvatarMaterial, frame: AvatarFrame, color: Color, ink: Color, scale: Float): MaterialImage? {
        val surface = frame.surface
        val volume = surface.volume?.takeIf { surface.scale > GONE } ?: return null
        return material.render(MaterialShape.Volume(volume), surface.view, MaterialLook(color, ink, surface.eyes), scale.toDouble())
    }

    /** One outline: in the material, where that can draw it, else as a plain fill. */
    private fun slice(canvas: Canvas, path: FramePath, color: Color, material: AvatarMaterial?, scale: Float) {
        val sprite = material?.let { markSprite(it, path, scale.toDouble(), color) }
        if (sprite != null) {
            image(canvas, sprite.image, sprite.cx, sprite.cy, sprite.radius)
            return
        }
        fill.shader = null
        fill.color = color
        canvas.drawPath(section(path), fill)
    }

    /** Draw an image over the square [radius] to each side of (cx, cy). */
    private fun image(canvas: Canvas, image: MaterialImage, cx: Double, cy: Double, radius: Double) {
        val unit = (2 * radius / image.size).toFloat()
        canvas.translate((cx - radius).toFloat(), (cy - radius).toFloat())
        canvas.scale(unit, unit)
        canvas.drawImage(image.tile, Offset.Zero, imagePaint)
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
        fill.shader = null
        when (mark) {
            is EyeMark.Path -> {
                fill.color = ink
                canvas.drawPath(dynamicParser.parsePathString(mark.d).toPath(), fill)
            }
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
 * themselves. Pass the same body color into `FrameOptions.color` when computing the frame:
 * ribbon gradients are derived there, before painting. These Compose colors also preserve alpha.
 * A [material] paints the body and the marks; without one, or where it returns null, they are flat.
 */
public fun DrawScope.drawAvatarFrame(frame: AvatarFrame, color: Color, eyeColor: Color, material: AvatarMaterial? = null) {
    AvatarPainter().paint(this, frame, color, eyeColor, material)
}
