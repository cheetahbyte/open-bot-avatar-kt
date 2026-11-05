package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Field
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.contourLoops
import re.leob.openbotavatar.kt.geometry.insideRow
import re.leob.openbotavatar.kt.geometry.isInside
import re.leob.openbotavatar.kt.geometry.loopsToPath
import re.leob.openbotavatar.kt.geometry.sampleRange
import re.leob.openbotavatar.kt.geometry.simplifyLoop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Implied volume for a flat silhouette. Every filled subpath becomes a part with a front-surface
 * height field: an inflated slab, a solid of revolution about its own vertical center line, or one
 * about a horizontal axis shared by the body. Each part is cut once into two stacks of cross-section
 * paths, z-slices facing the viewer and x-slices facing sideways; the renderer shows whichever stack
 * faces the viewer more, so no plane is ever seen edge-on. Upstream's volume.ts holds the full notes.
 */

internal enum class PartKind { INFLATE, REVOLVE_X, REVOLVE_Y }

internal enum class SliceAxis(val letter: Char) { Z('z'), X('x') }

internal data class Vec3(val x: Double, val y: Double, val z: Double)

internal class VolumeSlice(
    val axis: SliceAxis,
    val at: Double,
    val d: String,
    /** Contour paths use evenodd; the core slice reuses the original nonzero outline. */
    val evenOdd: Boolean,
    val box: PathBox,
    /** z-slices: smallest distance from this cross-section to the outline (0 for the core and walls). */
    val inset: Double,
)

internal class VolumePart(
    val center: Point,
    /** Front-to-back extent, centered on z = 0. */
    val depth: Double,
    val zSlices: List<VolumeSlice>,
    val xSlices: List<VolumeSlice>,
    /** Front surface height at a point, 0 outside the part. */
    val height: (Double, Double) -> Double,
)

internal class Volume(
    val parts: List<VolumePart>,
    val bounds: Bounds,
    /** Index of the part carrying the eyes. */
    val eyePart: Int,
    /** Depth of each eye's plane: the highest surface under the eye and its gaze travel. */
    val eyeZ: List<Double>,
    /** Drawn eye scale: the definition's scale enlarged as far as the body allows. */
    val eyeScale: Double,
    /** Host surface normals under each eye; an eye is hidden once any of them turns away. */
    val eyeNormals: List<List<Vec3>>,
)

private const val MAX_PART_DEPTH = 30.0

/** Drawn eyes are up to this much larger than the contract's 4 by 11 pill when the body has room. */
private const val EYE_BOOST = 1.45

/** Gap every drawn eye shape keeps from the outline at rest before the renderer enlarges it. */
private const val EYE_BOOST_PAD = 3.0

/** Steepness of the outline term in the contour field, so cross-sections hug the outline. */
private const val OUTLINE_SLOPE = 4.0
private const val MAX_X_SLICES = 40.0

/** Thin regions, where no inscribed circle within THIN_REACH reaches radius THIN, get refinement slices. */
private const val THIN = 4.0
private const val THIN_REACH = 5.0
private const val THIN_MARGIN = 2.0
private const val FINE_Z = 0.6

private class FilletShape(val depth: Double, val rxy: Double, val rz: Double)

private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len = dx * dx + dy * dy
    var t = if (len > 0) ((px - ax) * dx + (py - ay) * dy) / len else 0.0
    t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
    val ex = ax + t * dx - px
    val ey = ay + t * dy - py
    return sqrt(ex * ex + ey * ey)
}

/** Distance from a point to the nearest edge of the polygons. */
private fun edgeDistance(polys: Polygons, x: Double, y: Double): Double {
    var best = Double.POSITIVE_INFINITY
    for (poly in polys) {
        var j = poly.size - 1
        for (i in poly.indices) {
            val d = segDist(x, y, poly[j].x, poly[j].y, poly[i].x, poly[i].y)
            if (d < best) best = d
            j = i
        }
    }
    return best
}

/** Front surface depth of an inflated part at a point [dist] units inside the outline. */
private fun surfaceZ(part: FilletShape, dist: Double): Double {
    val half = part.depth / 2
    if (part.rxy <= 0 || dist >= part.rxy) return half
    val k = 1 - max(0.0, dist) / part.rxy
    return half - part.rz + sqrt(max(0.0, 1 - k * k)) * part.rz
}

/** As deep as the part is thick; tall slender parts get up to 2.4x their thickness so they read as discs. */
private fun partDepth(thickness: Double, b: Bounds, volume: Double): Double {
    val long = max(b.maxX - b.minX, b.maxY - b.minY)
    val raw = 0.8 * max(thickness, min(0.4 * long, 2.4 * thickness))
    return min(MAX_PART_DEPTH, max(3.0, raw)) * volume
}

/** Sorted crossings of the line (y = s if [rows], else x = s) with the polygons. */
private fun crossings(polys: Polygons, rows: Boolean, s: Double): DoubleArray {
    val out = ArrayList<Double>()
    for (poly in polys) {
        var j = poly.size - 1
        for (i in poly.indices) {
            val p = poly[j]
            val q = poly[i]
            val pa = if (rows) p.y else p.x
            val qa = if (rows) q.y else q.x
            val pb = if (rows) p.x else p.y
            val qb = if (rows) q.x else q.y
            if ((pa <= s && qa > s) || (qa <= s && pa > s)) out.add(pb + ((s - pa) * (qb - pb)) / (qa - pa))
            j = i
        }
    }
    return out.toDoubleArray().also { it.sort() }
}

private class Profile(val start: Double, val step: Double, val r: DoubleArray)

/** Half-widths about [center] if every line crosses the part as one centered interval; else not a solid of revolution. */
private fun revolveProfile(polys: Polygons, rows: Boolean, lo: Double, hi: Double, center: Double): Profile? {
    val step = 0.2
    val n = max(2, ceil((hi - lo) / step).toInt() + 1)
    val r = DoubleArray(n)
    var span = 0.0
    val mids = ArrayList<Double>()
    for (k in 0 until n) {
        val s = min(hi - 0.01, max(lo + 0.01, lo + k * step))
        val xs = crossings(polys, rows, s)
        if (xs.isEmpty()) continue
        if (xs.size != 2) return null
        r[k] = (xs[1] - xs[0]) / 2
        span = max(span, xs[1] - xs[0])
        mids.add((xs[0] + xs[1]) / 2)
    }
    val tol = max(0.8, 0.04 * span)
    if (mids.isEmpty() || mids.any { abs(it - center) > tol }) return null
    return Profile(lo, step, r)
}

private fun radiusAt(p: Profile, s: Double): Double {
    val t = (s - p.start) / p.step
    if (t < 0 || t > p.r.size - 1) return 0.0
    val k = min(p.r.size - 2, floor(t).toInt())
    return p.r[k] + (p.r[k + 1] - p.r[k]) * (t - k)
}

/** Horizontal axis of revolution, if the body looks like a turned object seen from the side. */
private fun revolveAxis(body: BodyAnalysis): Double? {
    val b = body.bounds
    val outlines = body.subpaths.filter { !it.hole }
    if (outlines.size < 2 || b.maxX - b.minX < 1.1 * (b.maxY - b.minY)) return null
    val axis = (b.minY + b.maxY) / 2
    for (o in outlines) {
        val ob = bounds(o.polys)
        if (abs((ob.minY + ob.maxY) / 2 - axis) > 1.5) return null
    }
    var filled = 0
    var off = 0
    val xs = sampleRange(b.minX + 0.5, b.maxX, 1.0)
    val top = BooleanArray(xs.size)
    val bottom = BooleanArray(xs.size)
    for (y in sampleRange(b.minY + 0.5, axis, 1.0)) {
        insideRow(body.polys, y, xs, top)
        insideRow(body.polys, 2 * axis - y, xs, bottom)
        for (k in xs.indices) {
            if (top[k] || bottom[k]) filled++
            if (top[k] != bottom[k]) off++
        }
    }
    return if (filled > 0 && off.toDouble() / filled < 0.02) axis else null
}

/** Sampled signed distance (positive inside) and height over a part's bounds. */
private class Grid(
    val x0: Double,
    val y0: Double,
    val step: Double,
    val nx: Int,
    val ny: Int,
    val sd: DoubleArray,
    val h: DoubleArray,
)

/** Edges bucketed on a coarse grid, for nearest-edge queries that only visit nearby buckets. */
private class EdgeIndex(polys: Polygons, b: Bounds) {
    private val cell = 3.0
    private val buckets = HashMap<Int, MutableList<Int>>()
    private val edges = ArrayList<Double>()
    private val ox = b.minX - 4
    private val oy = b.minY - 4
    private val span = ceil((max(b.maxX - b.minX, b.maxY - b.minY) + 8) / cell).toInt() + 1

    init {
        for (poly in polys) {
            var j = poly.size - 1
            for (i in poly.indices) {
                val p = poly[j]
                val q = poly[i]
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
                j = i
            }
        }
    }

    private fun cellX(x: Double) = max(0, min(span - 1, floor((x - ox) / cell).toInt()))
    private fun cellY(y: Double) = max(0, min(span - 1, floor((y - oy) / cell).toInt()))

    /** Distance to the nearest edge, searching rings of buckets outward until no closer edge can exist. */
    fun distance(x: Double, y: Double): Double {
        val bx = cellX(x)
        val by = cellY(y)
        var best = Double.POSITIVE_INFINITY
        for (r in 0 until span) {
            for (cy in by - r..by + r) {
                if (cy < 0 || cy >= span) continue
                val edgeRow = cy == by - r || cy == by + r
                val stride = if (edgeRow) 1 else if (r == 0) 1 else 2 * r
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
            // Every bucket outside ring r is at least r cells away.
            if (best <= r * cell) break
        }
        return best
    }
}

private fun sampleGrid(polys: Polygons, b: Bounds, step: Double, height: (Double, Double, Double) -> Double): Grid {
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

private fun loopBox(loops: List<List<Point>>): PathBox {
    val b = bounds(loops)
    return PathBox(b.minX, b.minY, b.maxX - b.minX, b.maxY - b.minY)
}

/** A rectangle of grid samples, inclusive, and the highest surface over its thin samples. */
private data class ThinBox(val i0: Int, val i1: Int, val j0: Int, val j1: Int, val top: Double)

/** Cross-section at or above [level], clipped to the outline and to [box] if given, as loops. */
private fun levelLoops(g: Grid, level: Double, box: ThinBox? = null): List<List<Point>> {
    // A box is contoured on its own samples plus one ring outside it, where the clip is negative.
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

/** Boxes around the thin parts of a part's outline, grown by THIN_MARGIN and merged where they overlap. */
private fun thinBoxes(g: Grid): List<ThinBox> {
    val nx = g.nx
    val ny = g.ny
    val reach = ceil(THIN_REACH / g.step).toInt()
    // Largest inward distance within `reach` samples: a square window, rows then columns.
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
        // Specks are not worth a slice.
        if (cells * g.step * g.step < 2) continue
        boxes.add(ThinBox(max(0, bi0 - grow), min(nx - 1, bi1 + grow), max(0, bj0 - grow), min(ny - 1, bj1 + grow), top))
    }
    // Overlapping boxes become one, so no cross-section is drawn twice.
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

/**
 * Side profile for the slab of grid columns [c0]..[c1]: loops in (u = -z, y), symmetric in u. Each
 * row takes the largest height across the slab, so a stack seen side-on adds up to the solid's side
 * silhouette; where a run meets the outline the profile ends at the surface height right there.
 */
private fun columnLoops(g: Grid, c0: Int, c1: Int, edgeHeight: (Double, Double) -> Double): List<List<Point>> {
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

/** Depth levels in (flat, flat + round): even in angle over the rounded part of the profile. */
private fun levels(flat: Double, round: Double, steps: Int): List<Double> =
    (1 until steps).map { flat + round * sin((it.toDouble() / steps) * (PI / 2)) }

private fun buildPart(
    outline: BodySubpath,
    holes: List<BodySubpath>,
    partCount: Int,
    xAxis: Double?,
    v: Double,
): VolumePart {
    val cuts = holes.filter { h -> h.probe != null && isInside(outline.polys, h.probe.x, h.probe.y) }
    val polys = outline.polys + cuts.flatMap { it.polys }
    val b = bounds(outline.polys)
    val center = Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2)
    val minDim = min(b.maxX - b.minX, b.maxY - b.minY)
    val step = max(0.25, min(0.45, minDim / 24))

    // Classify: shared horizontal axis, own vertical axis (multi-part bodies only), or inflated.
    var kind = PartKind.INFLATE
    var profile: Profile? = null
    if (xAxis != null) {
        profile = revolveProfile(polys, false, b.minX, b.maxX, xAxis)
        if (profile != null) kind = PartKind.REVOLVE_X
    } else if (partCount > 1 && cuts.isEmpty()) {
        profile = revolveProfile(polys, true, b.minY, b.maxY, center.x)
        if (profile != null) kind = PartKind.REVOLVE_Y
    }

    val depth: Double
    var rz = 0.0
    val heightOf: (Double, Double, Double) -> Double
    if (profile == null) {
        val coarse = sampleGrid(polys, b, max(step, 0.6)) { _, _, _ -> 0.0 }
        val thickness = 2 * coarse.sd.fold(0.0) { m, d -> max(m, d) }
        depth = partDepth(thickness, b, v)
        val rxy = min(thickness / 2, depth / 2) * 0.9
        rz = (depth / 2) * 0.85
        val shape = FilletShape(depth, rxy, rz)
        heightOf = { _, _, sd -> surfaceZ(shape, sd) }
    } else {
        val p = profile
        depth = 2 * p.r.max() * v
        heightOf = if (kind == PartKind.REVOLVE_X) {
            val axis = xAxis!!
            { x, y, _ -> v * sqrt(max(0.0, radiusAt(p, x).let { it * it } - (y - axis).let { it * it })) }
        } else {
            val axis = center.x
            { x, y, _ -> v * sqrt(max(0.0, radiusAt(p, y).let { it * it } - (x - axis).let { it * it })) }
        }
    }
    val inflated = kind == PartKind.INFLATE
    val height = { x: Double, y: Double ->
        if (isInside(polys, x, y)) heightOf(x, y, if (inflated) edgeDistance(polys, x, y) else 0.0) else 0.0
    }

    val core = VolumeSlice(
        SliceAxis.Z,
        0.0,
        outline.d + cuts.joinToString("") { it.d },
        false,
        PathBox(b.minX, b.minY, b.maxX - b.minX, b.maxY - b.minY),
        0.0,
    )
    val zSlices = arrayListOf(core)
    val xSlices = ArrayList<VolumeSlice>()
    val half = depth / 2
    if (half > 0.05) {
        val g = sampleGrid(polys, b, step, heightOf)
        val steps = if (inflated) max(4, min(14, Math.round(rz / 0.8).toInt())) else max(5, min(18, Math.round(half / 1.1).toInt()))
        // Inflated parts keep a straight wall up to half - rz; one slice at its end carries it.
        val ls = if (inflated) listOf(half - rz) + levels(half - rz, rz, steps) else levels(0.0, half, steps)
        fun addLevel(level: Double, box: ThinBox? = null): Boolean {
            val loops = levelLoops(g, level, box)
            if (loops.isEmpty()) return false
            val inset = loops.flatten().filterIndexed { k, _ -> k % 3 == 0 }.minOf { edgeDistance(polys, it.x, it.y) }
            val d = loopsToPath(loops)
            val boxed = loopBox(loops)
            zSlices.add(VolumeSlice(SliceAxis.Z, level, d, true, boxed, inset))
            zSlices.add(VolumeSlice(SliceAxis.Z, -level, d, true, boxed, inset))
            return true
        }
        val used = arrayListOf(0.0)
        for (level in ls) if (level >= 0.02 && addLevel(level)) used.add(level)
        // Thin parts of an inflated outline: more cross-sections between the levels, the straight wall
        // included, each clipped to its box. Revolved parts' slices span their thin side.
        val thin = if (inflated) thinBoxes(g) else emptyList()
        for (box in thin) {
            var k = 1
            while (k < used.size && used[k - 1] < box.top) {
                val a = used[k - 1]
                val gap = used[k] - a
                val m = ceil(gap / FINE_Z - 1e-9).toInt()
                var q = 1
                while (q < m && a + (gap * q) / m < box.top) {
                    addLevel(a + (gap * q) / m, box)
                    q++
                }
                k++
            }
        }
        zSlices.sortWith(compareBy(numericOrder) { it.at })

        val width = b.maxX - b.minX
        val every = max(1, Math.round(max(1.1, width / MAX_X_SLICES) / g.step).toInt())
        val edgeHeight = { x: Double, y: Double -> heightOf(x, y, 0.0) }
        // One plane per `every` grid columns, standing for the whole slab around it.
        val before = floor((every - 1) / 2.0).toInt()
        var i = 1
        while (i < g.nx - 1) {
            val loops = columnLoops(g, max(0, i - before), min(g.nx - 1, i - before + every - 1), edgeHeight)
            if (loops.isNotEmpty()) {
                xSlices.add(VolumeSlice(SliceAxis.X, g.x0 + i * g.step, loopsToPath(loops), true, loopBox(loops), 0.0))
            }
            i += every
        }
    }
    return VolumePart(center, depth, zSlices, xSlices, height)
}

/** Build the implied volume. [volume] scales every depth; 0 is flat. Pure, but too slow for a draw pass. */
internal fun buildVolume(definition: AvatarDefinition, volume: Double, body: BodyAnalysis): Volume {
    val outlines = body.subpaths.filter { !it.hole }
    val holes = body.subpaths.filter { it.hole }
    val v = if (volume.isFinite()) max(0.0, volume) else 1.0
    val xAxis = revolveAxis(body)
    val parts = outlines.map { buildPart(it, holes, outlines.size, xAxis, v) }

    val eyeScale = boostedEyeScale(body, definition.eyes)
    // The eyes ride on whichever part is highest under their centers.
    val centers = eyeCenters(definition.eyes)
    var eyePart = 0
    var bestZ = Double.NEGATIVE_INFINITY
    parts.forEachIndexed { i, part ->
        val zs = centers.map { part.height(it.x, it.y) }
        if (zs.any { it <= 0 }) return@forEachIndexed
        val z = zs.min()
        if (z > bestZ) {
            bestZ = z
            eyePart = i
        }
    }
    val host = parts.getOrNull(eyePart)
    // Flat eye planes sit at the highest surface under the eye and its gaze travel, so the body never cuts them.
    val w = (EYE_W * eyeScale) / 2 + GAZE_X * eyeScale
    val hh = (EYE_H * eyeScale) / 2 + GAZE_Y * eyeScale
    val fractions = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)
    val eyeZ = centers.map { c ->
        var top = 0.0
        for (fx in fractions) for (fy in fractions) {
            top = max(top, host?.height?.invoke(c.x + fx * w, c.y + fy * hh) ?: 0.0)
        }
        top
    }

    fun normal(x: Double, y: Double): Vec3 {
        if (host == null) return Vec3(0.0, 0.0, 1.0)
        val e = 0.75
        val gx = (host.height(x + e, y) - host.height(x - e, y)) / (2 * e)
        val gy = (host.height(x, y + e) - host.height(x, y - e)) / (2 * e)
        val n = hypot(hypot(gx, gy), 1.0)
        return Vec3(-gx / n, -gy / n, 1 / n)
    }
    val half = (EYE_W * eyeScale) / 2
    val eyeNormals = centers.map { c -> listOf(normal(c.x - half, c.y), normal(c.x, c.y), normal(c.x + half, c.y)) }
    return Volume(parts, body.bounds, eyePart, eyeZ, eyeScale, eyeNormals)
}

/** Largest eye scale up to EYE_BOOST times the definition's whose drawn eyes keep EYE_BOOST_PAD from the outline. */
private fun boostedEyeScale(body: BodyAnalysis, eyes: AvatarEyes): Double {
    for (k in doubleArrayOf(EYE_BOOST, 1.38, 1.3, 1.22, 1.15, 1.08)) {
        if (drawnEyesMiss(body.polys, eyes.copy(scale = eyes.scale * k), 0.0, 0.0, EYE_BOOST_PAD) == null) return eyes.scale * k
    }
    return eyes.scale
}
