package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Field
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.contourLoops
import re.leob.openbotavatar.kt.geometry.forEachEdge
import re.leob.openbotavatar.kt.geometry.insideRow
import re.leob.openbotavatar.kt.geometry.segDist
import re.leob.openbotavatar.kt.geometry.simplifyLoop
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Steepness of the outline term in the contour field, so cross-sections hug the outline. */
private const val OUTLINE_SLOPE = 4.0

/** Thin regions, where no inscribed circle within THIN_REACH reaches radius THIN, get refinement slices. */
private const val THIN = 4.0
private const val THIN_REACH = 5.0
private const val THIN_MARGIN = 2.0

/** Sampled signed distance (positive inside) and height over a part's bounds. */
internal class Grid(
    val x0: Double,
    val y0: Double,
    val step: Double,
    val nx: Int,
    val ny: Int,
    val sd: DoubleArray,
    val h: DoubleArray,
)

private class EdgeIndex(polys: Polygons, b: Bounds) {
    private val cell = 3.0
    private val buckets = HashMap<Int, MutableList<Int>>()
    private val edges = ArrayList<Double>()
    private val ox = b.minX - 4
    private val oy = b.minY - 4
    private val span = ceil((max(b.maxX - b.minX, b.maxY - b.minY) + 8) / cell).toInt() + 1

    init {
        forEachEdge(polys) { p, q ->
            val k = edges.size
            edges.add(p.x)
            edges.add(p.y)
            edges.add(q.x)
            edges.add(q.y)
            for (cy in cellY(min(p.y, q.y))..cellY(max(p.y, q.y))) {
                for (cx in cellX(min(p.x, q.x))..cellX(max(p.x, q.x))) {
                    buckets.getOrPut(cy * span + cx) { ArrayList() }.add(k)
                }
            }
        }
    }

    private fun cellX(x: Double) = max(0, min(span - 1, floor((x - ox) / cell).toInt()))
    private fun cellY(y: Double) = max(0, min(span - 1, floor((y - oy) / cell).toInt()))

    fun distance(x: Double, y: Double): Double {
        val bx = cellX(x)
        val by = cellY(y)
        var best = Double.POSITIVE_INFINITY
        for (r in 0 until span) {
            for (cy in by - r..by + r) {
                if (cy < 0 || cy >= span) continue
                val edgeRow = cy == by - r || cy == by + r
                val stride = if (edgeRow) 1 else 2 * r
                var cx = bx - r
                while (cx <= bx + r) {
                    if (cx in 0 until span) {
                        buckets[cy * span + cx]?.forEach { k ->
                            val d = segDist(x, y, edges[k], edges[k + 1], edges[k + 2], edges[k + 3])
                            if (d < best) best = d
                        }
                    }
                    cx += stride
                }
            }
            if (best <= r * cell) break
        }
        return best
    }
}

internal fun sampleGrid(polys: Polygons, b: Bounds, step: Double, height: (Double, Double, Double) -> Double): Grid {
    val pad = 1.0
    val x0 = b.minX - pad
    val y0 = b.minY - pad
    val nx = ceil((b.maxX - b.minX + 2 * pad) / step).toInt() + 1
    val ny = ceil((b.maxY - b.minY + 2 * pad) / step).toInt() + 1
    val sd = DoubleArray(nx * ny)
    val h = DoubleArray(nx * ny)
    val index = EdgeIndex(polys, b)
    val xs = DoubleArray(nx) { x0 + it * step }
    val inside = BooleanArray(nx)
    for (j in 0 until ny) {
        val y = y0 + j * step
        insideRow(polys, y, xs, inside)
        for (i in 0 until nx) {
            val d = index.distance(xs[i], y)
            sd[j * nx + i] = if (inside[i]) d else -d
            h[j * nx + i] = if (inside[i]) height(xs[i], y, d) else 0.0
        }
    }
    return Grid(x0, y0, step, nx, ny, sd, h)
}

internal data class ThinBox(val i0: Int, val i1: Int, val j0: Int, val j1: Int, val top: Double)

internal fun levelLoops(g: Grid, level: Double, box: ThinBox?): List<List<Point>> {
    val i0 = if (box != null) max(0, box.i0 - 1) else 0
    val i1 = if (box != null) min(g.nx - 1, box.i1 + 1) else g.nx - 1
    val j0 = if (box != null) max(0, box.j0 - 1) else 0
    val j1 = if (box != null) min(g.ny - 1, box.j1 + 1) else g.ny - 1
    val nx = i1 - i0 + 1
    val ny = j1 - j0 + 1
    val values = DoubleArray(nx * ny)
    for (j in 0 until ny) {
        for (i in 0 until nx) {
            val k = (j + j0) * g.nx + i + i0
            var v = min(g.h[k] - level, OUTLINE_SLOPE * g.sd[k])
            if (box != null) {
                val edge = minOf(minOf(i + i0 - box.i0, box.i1 - i - i0), minOf(j + j0 - box.j0, box.j1 - j - j0))
                v = min(v, OUTLINE_SLOPE * g.step * edge)
            }
            values[j * nx + i] = v
        }
    }
    return contourLoops(Field(values, nx, ny, g.x0 + i0 * g.step, g.y0 + j0 * g.step, g.step), 0.0)
        .map { simplifyLoop(it, 0.04) }
        .filter { it.size >= 3 }
}

internal fun thinBoxes(g: Grid): List<ThinBox> {
    val nx = g.nx
    val ny = g.ny
    val reach = ceil(THIN_REACH / g.step).toInt()
    val rows = DoubleArray(nx * ny)
    val widest = DoubleArray(nx * ny)
    for (j in 0 until ny) {
        for (i in 0 until nx) {
            var m = 0.0
            for (k in max(0, i - reach)..min(nx - 1, i + reach)) m = max(m, g.sd[j * nx + k])
            rows[j * nx + i] = m
        }
    }
    for (i in 0 until nx) {
        for (j in 0 until ny) {
            var m = 0.0
            for (k in max(0, j - reach)..min(ny - 1, j + reach)) m = max(m, rows[k * nx + i])
            widest[j * nx + i] = m
        }
    }
    fun thin(k: Int) = g.sd[k] > 0 && widest[k] < THIN
    val seen = BooleanArray(nx * ny)
    val grow = ceil(THIN_MARGIN / g.step).toInt()
    val boxes = ArrayList<ThinBox>()
    for (start in 0 until nx * ny) {
        if (seen[start] || !thin(start)) continue
        var bi0 = nx
        var bi1 = -1
        var bj0 = ny
        var bj1 = -1
        var top = 0.0
        var cells = 0
        val stack = ArrayDeque<Int>()
        stack.addLast(start)
        seen[start] = true
        while (stack.isNotEmpty()) {
            val k = stack.removeLast()
            val i = k % nx
            val j = (k - i) / nx
            cells++
            top = max(top, g.h[k])
            bi0 = min(bi0, i)
            bi1 = max(bi1, i)
            bj0 = min(bj0, j)
            bj1 = max(bj1, j)
            val neighbors = intArrayOf(
                if (i > 0) k - 1 else -1,
                if (i < nx - 1) k + 1 else -1,
                if (j > 0) k - nx else -1,
                if (j < ny - 1) k + nx else -1,
            )
            for (n in neighbors) {
                if (n >= 0 && !seen[n] && thin(n)) {
                    seen[n] = true
                    stack.addLast(n)
                }
            }
        }
        if (cells * g.step * g.step < 2) continue
        boxes.add(ThinBox(max(0, bi0 - grow), min(nx - 1, bi1 + grow), max(0, bj0 - grow), min(ny - 1, bj1 + grow), top))
    }
    var a = 0
    while (a < boxes.size) {
        var b = a + 1
        while (b < boxes.size) {
            val p = boxes[a]
            val q = boxes[b]
            if (p.i0 > q.i1 || q.i0 > p.i1 || p.j0 > q.j1 || q.j0 > p.j1) {
                b++
                continue
            }
            boxes[a] = ThinBox(min(p.i0, q.i0), max(p.i1, q.i1), min(p.j0, q.j0), max(p.j1, q.j1), max(p.top, q.top))
            boxes.removeAt(b)
            b = a + 1
        }
        a++
    }
    return boxes
}

internal fun columnLoops(g: Grid, c0: Int, c1: Int, edgeHeight: (Double, Double) -> Double): List<List<Point>> {
    val loops = ArrayList<List<Point>>()
    val sd = DoubleArray(g.ny) { Double.NEGATIVE_INFINITY }
    val h = DoubleArray(g.ny)
    for (j in 0 until g.ny) {
        for (c in c0..c1) {
            val k = j * g.nx + c
            if (g.sd[k] > sd[j]) sd[j] = g.sd[k]
            if (g.sd[k] > 0 && g.h[k] > h[j]) h[j] = g.h[k]
        }
    }
    fun yAt(j: Int) = g.y0 + j * g.step
    fun edge(inside: Int, outside: Int) = yAt(inside) + ((yAt(outside) - yAt(inside)) * sd[inside]) / (sd[inside] - sd[outside])
    fun rim(y: Double, j: Int): Double {
        var top = 0.0
        for (c in c0..c1) top = max(top, edgeHeight(g.x0 + c * g.step, y))
        return min(h[j], top)
    }
    var j = 0
    while (j < g.ny) {
        if (sd[j] <= 0) {
            j++
            continue
        }
        val j0 = j
        while (j < g.ny && sd[j] > 0) j++
        val j1 = j - 1
        val yTop = if (j0 > 0) edge(j0, j0 - 1) else yAt(j0)
        val yBot = if (j1 < g.ny - 1) edge(j1, j1 + 1) else yAt(j1)
        val right = ArrayList<Point>()
        right.add(Point(if (j0 > 0) rim(yTop, j0) else h[j0], yTop))
        for (k in j0..j1) right.add(Point(h[k], yAt(k)))
        right.add(Point(if (j1 < g.ny - 1) rim(yBot, j1) else h[j1], yBot))
        if (right.maxOf { it.x } < 0.05) continue
        val left = right.map { Point(-it.x, it.y) }.reversed()
        loops.add(simplifyLoop(right + left, 0.04))
    }
    return loops
}
