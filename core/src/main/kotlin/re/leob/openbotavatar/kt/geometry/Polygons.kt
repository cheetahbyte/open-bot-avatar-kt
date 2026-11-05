package re.leob.openbotavatar.kt.geometry

import kotlin.math.floor
import kotlin.math.sqrt

internal data class Point(val x: Double, val y: Double)

internal typealias Polygons = List<List<Point>>

internal data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

internal inline fun forEachEdge(polys: Polygons, action: (Point, Point) -> Unit) {
    for (poly in polys) {
        var previous = poly[poly.size - 1]
        for (point in poly) {
            action(previous, point)
            previous = point
        }
    }
}

internal fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len = dx * dx + dy * dy
    var t = if (len > 0) ((px - ax) * dx + (py - ay) * dy) / len else 0.0
    t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
    val ex = ax + t * dx - px
    val ey = ay + t * dy - py
    return sqrt(ex * ex + ey * ey)
}

internal fun edgeDistance(polys: Polygons, x: Double, y: Double): Double {
    var best = Double.POSITIVE_INFINITY
    forEachEdge(polys) { a, b ->
        val d = segDist(x, y, a.x, a.y, b.x, b.y)
        if (d < best) best = d
    }
    return best
}

private fun winding(polys: Polygons, x: Double, y: Double): Int {
    var w = 0
    forEachEdge(polys) { a, b ->
        val cross = (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y)
        if (a.y <= y) {
            if (b.y > y && cross > 0) w++
        } else if (b.y <= y && cross < 0) {
            w--
        }
    }
    return w
}

internal fun isInside(polys: Polygons, x: Double, y: Double): Boolean = winding(polys, x, y) != 0

internal fun insideRow(polys: Polygons, y: Double, xs: DoubleArray, out: BooleanArray) {
    val hitX = ArrayList<Double>()
    val hitW = ArrayList<Int>()
    forEachEdge(polys) { a, b ->
        if (a.y <= y && b.y > y) {
            hitX.add(a.x + ((y - a.y) * (b.x - a.x)) / (b.y - a.y))
            hitW.add(1)
        } else if (b.y <= y && a.y > y) {
            hitX.add(a.x + ((y - a.y) * (b.x - a.x)) / (b.y - a.y))
            hitW.add(-1)
        }
    }
    val order = hitX.indices.sortedBy { hitX[it] }
    var w = 0
    var h = 0
    for (k in xs.indices) {
        while (h < order.size && hitX[order[h]] < xs[k]) w += hitW[order[h++]]
        out[k] = w != 0
    }
}

internal fun bounds(polys: Polygons): Bounds {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (poly in polys) for (p in poly) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    return Bounds(minX, minY, maxX, maxY)
}

/** Sample positions from [start] by [step] while below [end], added up as upstream does. */
internal fun sampleRange(start: Double, end: Double, step: Double): DoubleArray {
    val out = ArrayList<Double>()
    var v = start
    while (v < end) {
        out.add(v)
        v += step
    }
    return out.toDoubleArray()
}

internal fun filledArea(polys: Polygons, b: Bounds): Int {
    var n = 0
    val xs = sampleRange(floor(b.minX) + 0.5, b.maxX, 1.0)
    val row = BooleanArray(xs.size)
    for (y in sampleRange(floor(b.minY) + 0.5, b.maxY, 1.0)) {
        insideRow(polys, y, xs, row)
        for (inside in row) if (inside) n++
    }
    return n
}
