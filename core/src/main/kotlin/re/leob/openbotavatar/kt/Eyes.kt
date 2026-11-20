package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.clamp
import re.leob.openbotavatar.kt.geometry.fixed
import re.leob.openbotavatar.kt.volume.HappyArc
import kotlin.math.*

internal data class EyeSpot(val x: Double, val y: Double, val w: Double, val h: Double, val open: Double, val shift: Double)

private fun resample(pts: List<Point>, count: Int): List<Point> {
    val n = pts.size
    val at = DoubleArray(n + 1)
    for (i in 0 until n) { val a = pts[i]; val b = pts[(i + 1) % n]; at[i + 1] = at[i] + hypot(b.x - a.x, b.y - a.y) }
    var i = 0
    return List(count) { k ->
        val s = k.toDouble() / count * at[n]
        while (i < n - 1 && at[i + 1] < s) i++
        val a = pts[i]; val b = pts[(i + 1) % n]
        val u = if (at[i + 1] > at[i]) (s - at[i]) / (at[i + 1] - at[i]) else 0.0
        Point(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
    }
}

private fun sweep(cx: Double, cy: Double, r: Double, from: Double, to: Double, steps: Int) =
    List(steps + 1) { i -> val a = from + (to - from) * i / steps; Point(cx + cos(a) * r, cy + sin(a) * r) }

private fun pill(cx: Double, cy: Double, w: Double, h: Double): List<Point> {
    val r = min(w, h) / 2; val fx = w / 2 - r; val fy = h / 2 - r; val q = PI / 2
    return listOf(Point(cx, cy - h / 2)) + sweep(cx + fx, cy - fy, r, -q, 0.0, 6) +
        sweep(cx + fx, cy + fy, r, 0.0, q, 6) + sweep(cx - fx, cy + fy, r, q, 2 * q, 6) +
        sweep(cx - fx, cy - fy, r, 2 * q, 3 * q, 6)
}

private fun arc(e: EyeSpot): List<Point> {
    val hw = e.w * HappyArc.HALF_WIDTH; val base = e.y + e.h * HappyArc.BASE; val peak = e.y - e.h * HappyArc.PEAK
    val r = e.w * HappyArc.STROKE / 2
    fun at(s: Double): Pair<Point, Point> {
        val m = 1 - s
        val x = e.x + hw * (2 * s - 1); val y = (m * m + s * s) * base + 2 * m * s * peak
        val tx = 2 * hw; val ty = 2 * (2 * s - 1) * (base - peak); val len = hypot(tx, ty)
        return Point(x, y) to Point(ty / len, -tx / len)
    }
    fun edge(from: Double, to: Double, side: Double) = List(9) { i ->
        val (p, n) = at(from + (to - from) * i / 8)
        Point(p.x + n.x * r * side, p.y + n.y * r * side)
    }
    fun end(s: Double): List<Point> {
        val (p, n) = at(s); val up = atan2(n.y, n.x)
        return if (s == 1.0) sweep(p.x, p.y, r, up, up + PI, 8) else sweep(p.x, p.y, r, up + PI, up + 2 * PI, 8)
    }
    return edge(0.5, 1.0, 1.0) + end(1.0) + edge(1.0, 0.0, -1.0) + end(0.0) + edge(0.0, 0.5, 1.0)
}

private fun slit(e: EyeSpot): List<Point> {
    val thick = e.w * 0.5 * clamp(e.open / e.h, 0.3, 1.0)
    val c = cos(0.2); val s = sin(0.2)
    return pill(0.0, 0.0, e.w * 1.9, thick).map { (x, y) -> Point(e.x + x * c - y * s, e.y + x * s + y * c) }
}

internal fun eyeOutline(look: EyeLook, e: EyeSpot): List<Point> {
    val a = clamp(look.arc, 0.0, 1.0); val s = clamp(look.slit, 0.0, 1 - a)
    val shapes = listOf((1 - a - s) to pill(e.x, e.y + e.shift, e.w, e.open), a to arc(e), s to slit(e))
    val xs = DoubleArray(40); val ys = DoubleArray(40)
    for ((weight, shape) in shapes) if (weight > 0) resample(shape, 40).forEachIndexed { i, p -> xs[i] += p.x * weight; ys[i] += p.y * weight }
    return List(40) { Point(xs[it], ys[it]) }
}

internal fun eyePath(look: EyeLook, e: EyeSpot): String {
    val pts = eyeOutline(look, e); val n = pts.size
    fun mid(i: Int): Point { val a = pts[i % n]; val b = pts[(i + 1) % n]; return Point((a.x + b.x) / 2, (a.y + b.y) / 2) }
    fun f(v: Double) = fixed(v, 2)
    val start = mid(n - 1)
    return buildString {
        append("M${f(start.x)} ${f(start.y)}")
        for (i in pts.indices) { val m = mid(i); append("Q${f(pts[i].x)} ${f(pts[i].y)} ${f(m.x)} ${f(m.y)}") }
        append('Z')
    }
}
