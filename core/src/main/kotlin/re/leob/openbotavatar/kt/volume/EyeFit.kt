package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.isInside
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/** Neutral eye size at scale 1, viewBox units. */
internal const val EYE_W = 4.0
internal const val EYE_H = 11.0

/** Max gaze travel of the eyes at scale 1. */
internal const val GAZE_X = 1.5
internal const val GAZE_Y = 1.1

/** Gap kept between every drawn eye shape and the outline while the eyes look around. */
private const val EYE_CLEARANCE = 1.5

/** Drawn eyes are up to this much larger than the contract's 4 by 11 pill when the body has room. */
private const val EYE_BOOST = 1.45

/** Gap every drawn eye shape keeps from the outline at rest before it is enlarged. */
private const val EYE_BOOST_PAD = 3.0

/** Happy-eye arc, as fractions of the drawn eye size. */
internal object HappyArc {
    const val HALF_WIDTH = 0.72
    const val BASE = 0.1
    const val PEAK = 0.42
    const val STROKE = 0.6
}

internal data class GazeRange(val left: Double, val right: Double, val up: Double, val down: Double)

private fun eyeOutline(cx: Double, cy: Double, scale: Double, margin: Double): List<Point> {
    val hw = (EYE_W * scale) / 2 + margin
    val hh = (EYE_H * scale) / 2 + margin
    val pts = ArrayList<Point>()
    val straight = hh - hw
    for (k in 0 until 16) {
        val a = (k.toDouble() / 16) * PI * 2
        pts.add(Point(cx + cos(a) * hw, cy + sin(a) * hw + sign(sin(a)) * max(0.0, straight)))
    }
    for (f in doubleArrayOf(-0.5, 0.0, 0.5)) {
        pts.add(Point(cx - hw, cy + f * straight))
        pts.add(Point(cx + hw, cy + f * straight))
    }
    pts.add(Point(cx, cy))
    return pts
}

private fun drawnEyeOutline(cx: Double, cy: Double, scale: Double, margin: Double): List<Point> {
    val pts = ArrayList(eyeOutline(cx, cy, scale, margin))
    val w = EYE_W * scale
    val h = EYE_H * scale
    val x0 = cx - HappyArc.HALF_WIDTH * w
    val x1 = cx + HappyArc.HALF_WIDTH * w
    val yb = cy + HappyArc.BASE * h
    val yc = cy - HappyArc.PEAK * h
    val r = (HappyArc.STROKE * w) / 2 + margin
    for (i in 0..8) {
        val t = i.toDouble() / 8
        val m = 1 - t
        val x = m * m * x0 + 2 * m * t * cx + t * t * x1
        val y = (m * m + t * t) * yb + 2 * m * t * yc
        for (k in 0 until 8) {
            val a = (k.toDouble() / 8) * PI * 2
            pts.add(Point(x + cos(a) * r, y + sin(a) * r))
        }
    }
    return pts
}

internal fun eyeCenters(eyes: AvatarEyes): List<Point> =
    listOf(Point(eyes.x - eyes.spacing / 2, eyes.y), Point(eyes.x + eyes.spacing / 2, eyes.y))

internal fun eyesFit(polys: Polygons, eyes: AvatarEyes, margin: Double): Point? {
    for (c in eyeCenters(eyes)) {
        for (p in eyeOutline(c.x, c.y, eyes.scale, margin)) if (!isInside(polys, p.x, p.y)) return p
    }
    return null
}

private fun drawnEyesFit(polys: Polygons, eyes: AvatarEyes, dx: Double, dy: Double, margin: Double): Boolean =
    eyeCenters(eyes).all { c -> drawnEyeOutline(c.x + dx, c.y + dy, eyes.scale, margin).all { isInside(polys, it.x, it.y) } }

internal fun gazeRange(polys: Polygons, eyes: AvatarEyes): GazeRange {
    fun reach(ux: Double, uy: Double): Double {
        for (k in doubleArrayOf(1.0, 0.8, 0.6, 0.4, 0.2)) {
            val dx = ux * k * GAZE_X * eyes.scale
            val dy = uy * k * GAZE_Y * eyes.scale
            if (drawnEyesFit(polys, eyes, dx, dy, EYE_CLEARANCE)) return k
        }
        return 0.0
    }
    return GazeRange(reach(-1.0, 0.0), reach(1.0, 0.0), reach(0.0, -1.0), reach(0.0, 1.0))
}

internal fun boostedEyeScale(polys: Polygons, eyes: AvatarEyes): Double {
    for (k in doubleArrayOf(EYE_BOOST, 1.38, 1.3, 1.22, 1.15, 1.08)) {
        if (drawnEyesFit(polys, eyes.copy(scale = eyes.scale * k), 0.0, 0.0, EYE_BOOST_PAD)) return eyes.scale * k
    }
    return eyes.scale
}
