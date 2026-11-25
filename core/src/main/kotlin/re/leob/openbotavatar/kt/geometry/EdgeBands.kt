package re.leob.openbotavatar.kt.geometry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The edges of a set of polygons, listed by the horizontal bands they cross, for many point and row
 * tests against one outline. An edge outside a query's band cannot straddle its y, so it adds
 * nothing to a pass over every edge either: the answers are the same, bit for bit.
 *
 * [winding] is safe from any thread. The row queries share scratch, so they belong to one build.
 */
internal class EdgeBands(polys: Polygons) {
    /** xj, yj, xi, yi per edge. */
    private val e: DoubleArray
    private val start: IntArray
    private val items: IntArray
    private val y0: Double
    private val inv: Double
    private val n: Int
    private var hitX = DoubleArray(16)
    private var hitDir = IntArray(16)

    init {
        val count = polys.sumOf { it.size }
        e = DoubleArray(4 * count)
        var k = 0
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var climb = 0.0
        forEachEdge(polys) { a, b ->
            e[k] = a.x
            e[k + 1] = a.y
            e[k + 2] = b.x
            e[k + 3] = b.y
            k += 4
            if (a.y < lo) lo = a.y
            if (a.y > hi) hi = a.y
            climb += abs(b.y - a.y)
        }
        // About four edges to a band, but fewer bands where the edges are tall: an edge is listed in
        // every band it crosses, and the lists should not outgrow the outline (a few entries an edge).
        val tall = if (climb > 0) floor((3 * count * (hi - lo)) / climb) else Double.POSITIVE_INFINITY
        n = max(1.0, minOf(512.0, ceil(count / 4.0), tall)).toInt()
        y0 = if (count > 0) lo else 0.0
        inv = if (hi > lo) n / (hi - lo) else 0.0
        // Counting sort of the edges into every band their y range touches.
        start = IntArray(n + 1)
        for (q in 0 until 4 * count step 4) {
            for (b in band(min(e[q + 1], e[q + 3]))..band(max(e[q + 1], e[q + 3]))) start[b + 1]++
        }
        for (b in 0 until n) start[b + 1] += start[b]
        items = IntArray(start[n])
        val next = start.copyOf(n)
        for (q in 0 until 4 * count step 4) {
            for (b in band(min(e[q + 1], e[q + 3]))..band(max(e[q + 1], e[q + 3]))) items[next[b]++] = q
        }
    }

    /** Monotonic in y, so an edge is listed in the band of every y between its ends. */
    private fun band(y: Double): Int {
        val q = floor((y - y0) * inv)
        return if (q >= n) n - 1 else if (q > 0) q.toInt() else 0
    }

    /** Nonzero winding number of a point (SVG default fill rule). */
    fun winding(x: Double, y: Double): Int {
        val b = band(y)
        var w = 0
        for (q in start[b] until start[b + 1]) {
            val k = items[q]
            val xj = e[k]
            val yj = e[k + 1]
            val xi = e[k + 2]
            val yi = e[k + 3]
            if (yj <= y) {
                if (yi > y && (xi - xj) * (y - yj) - (x - xj) * (yi - yj) > 0) w++
            } else if (yi <= y && (xi - xj) * (y - yj) - (x - xj) * (yi - yj) < 0) {
                w--
            }
        }
        return w
    }

    fun inside(x: Double, y: Double): Boolean = winding(x, y) != 0

    /** The row's crossings, sorted by x, left in hitX and hitDir. Returns how many. */
    private fun crossings(y: Double): Int {
        val b = band(y)
        val end = start[b + 1]
        if (end - start[b] > hitX.size) {
            hitX = DoubleArray(2 * (end - start[b]))
            hitDir = IntArray(hitX.size)
        }
        var n = 0
        for (q in start[b] until end) {
            val k = items[q]
            val xj = e[k]
            val yj = e[k + 1]
            val xi = e[k + 2]
            val yi = e[k + 3]
            val dir = if (yj <= y && yi > y) 1 else if (yi <= y && yj > y) -1 else continue
            val x = xj + ((y - yj) * (xi - xj)) / (yi - yj)
            // Insertion sort: a row has a handful of crossings. Ties may land in any order, since
            // crossings at one x are always counted together.
            var m = n++
            while (m > 0 && hitX[m - 1] > x) {
                hitX[m] = hitX[m - 1]
                hitDir[m] = hitDir[m - 1]
                m--
            }
            hitX[m] = x
            hitDir[m] = dir
        }
        return n
    }

    /** Nonzero inside flags for many points on one row, [xs] ascending. */
    fun insideRow(y: Double, xs: DoubleArray, out: BooleanArray) {
        val n = crossings(y)
        var w = 0
        var h = 0
        for (k in xs.indices) {
            while (h < n && hitX[h] < xs[k]) w += hitDir[h++]
            out[k] = w != 0
        }
    }

    /** One flag of [insideRow]: what it reports for a row at [y] with [x] among its points. */
    fun insideRowAt(x: Double, y: Double): Boolean {
        val n = crossings(y)
        var w = 0
        var h = 0
        while (h < n && hitX[h] < x) w += hitDir[h++]
        return w != 0
    }
}
