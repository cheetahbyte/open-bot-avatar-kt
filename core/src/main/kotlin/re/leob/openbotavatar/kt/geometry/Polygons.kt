package re.leob.openbotavatar.kt.geometry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** A point, viewBox units. */
public data class Point(val x: Double, val y: Double)

internal typealias Polygons = List<List<Point>>

/** An axis-aligned box, viewBox units. */
public data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** JavaScript's scaled two-coordinate norm; its last bit decides ties in contour simplification. */
internal fun hypot2(x: Double, y: Double): Double {
    if (x.isInfinite() || y.isInfinite()) return Double.POSITIVE_INFINITY
    val scale = max(abs(x), abs(y))
    if (scale == 0.0) return 0.0
    val a = abs(x) / scale
    val b = abs(y) / scale
    return sqrt(a * a + b * b) * scale
}

internal inline fun forEachEdge(polys: Polygons, action: (Point, Point) -> Unit) {
    for (poly in polys) {
        var previous = poly[poly.size - 1]
        for (point in poly) {
            action(previous, point)
            previous = point
        }
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

internal fun filledArea(rows: EdgeBands, b: Bounds): Int {
    var n = 0
    val xs = sampleRange(floor(b.minX) + 0.5, b.maxX, 1.0)
    val row = BooleanArray(xs.size)
    for (y in sampleRange(floor(b.minY) + 0.5, b.maxY, 1.0)) {
        rows.insideRow(y, xs, row)
        for (inside in row) if (inside) n++
    }
    return n
}
