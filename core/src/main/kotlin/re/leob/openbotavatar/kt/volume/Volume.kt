package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.edgeDistance
import re.leob.openbotavatar.kt.geometry.forEachEdge
import re.leob.openbotavatar.kt.geometry.insideRow
import re.leob.openbotavatar.kt.geometry.isInside
import re.leob.openbotavatar.kt.geometry.loopsToPath
import re.leob.openbotavatar.kt.geometry.numericOrder
import re.leob.openbotavatar.kt.geometry.sampleRange
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class SliceAxis(val letter: Char) { Z('z'), X('x') }

internal data class Vec3(val x: Double, val y: Double, val z: Double)

internal class VolumeSlice(
    val at: Double,
    val d: String,
    /** Contour paths use evenodd; the core slice reuses the original nonzero outline. */
    val evenOdd: Boolean,
    val bounds: Bounds,
    /** z-slices: smallest distance from this cross-section to the outline (0 for the core and walls). */
    val inset: Double,
)

internal class VolumePart(
    val center: Point,
    val zSlices: List<VolumeSlice>,
    val xSlices: List<VolumeSlice>,
    /** Front surface height at a point, 0 outside the part. */
    val height: (Double, Double) -> Double,
)

internal class Volume(
    val parts: List<VolumePart>,
    val bounds: Bounds,
    val eyePart: Int,
    /** Depth of each eye's plane: the highest surface under the eye and its gaze travel. */
    val eyeZ: List<Double>,
    val eyeScale: Double,
    /** Host surface normals under each eye; an eye is hidden once any of them turns away. */
    val eyeNormals: List<List<Vec3>>,
)

private const val MAX_PART_DEPTH = 30.0
private const val MAX_X_SLICES = 40.0
private const val FINE_Z = 0.6
private const val COARSE_STEP = 0.6

private class Solid(
    val inflated: Boolean,
    val depth: Double,
    val rz: Double,
    val heightAt: (Double, Double, Double) -> Double,
)

private class FilletShape(val depth: Double, val rxy: Double, val rz: Double)

private fun surfaceZ(part: FilletShape, dist: Double): Double {
    val half = part.depth / 2
    if (part.rxy <= 0 || dist >= part.rxy) return half
    val k = 1 - max(0.0, dist) / part.rxy
    return half - part.rz + sqrt(max(0.0, 1 - k * k)) * part.rz
}

private fun partDepth(thickness: Double, b: Bounds, volume: Double): Double {
    val long = max(b.maxX - b.minX, b.maxY - b.minY)
    val raw = 0.8 * max(thickness, min(0.4 * long, 2.4 * thickness))
    return min(MAX_PART_DEPTH, max(3.0, raw)) * volume
}

private fun crossings(polys: Polygons, rows: Boolean, s: Double): DoubleArray {
    val out = ArrayList<Double>()
    forEachEdge(polys) { p, q ->
        val pa = if (rows) p.y else p.x
        val qa = if (rows) q.y else q.x
        val pb = if (rows) p.x else p.y
        val qb = if (rows) q.x else q.y
        if ((pa <= s && qa > s) || (qa <= s && pa > s)) out.add(pb + ((s - pa) * (qb - pb)) / (qa - pa))
    }
    return out.toDoubleArray().also { it.sort() }
}

private class Profile(val start: Double, val step: Double, val r: DoubleArray)

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

private fun levels(flat: Double, round: Double, steps: Int): List<Double> =
    (1 until steps).map { flat + round * sin((it.toDouble() / steps) * (PI / 2)) }

private fun inflatedSolid(polys: Polygons, b: Bounds, v: Double): Solid {
    val coarse = sampleGrid(polys, b, COARSE_STEP) { _, _, _ -> 0.0 }
    val thickness = 2 * coarse.sd.fold(0.0) { m, d -> max(m, d) }
    val depth = partDepth(thickness, b, v)
    val rz = (depth / 2) * 0.85
    val shape = FilletShape(depth, min(thickness / 2, depth / 2) * 0.9, rz)
    return Solid(true, depth, rz) { _, _, sd -> surfaceZ(shape, sd) }
}

private fun revolvedAboutX(p: Profile, axis: Double, v: Double) = Solid(false, 2 * p.r.max() * v, 0.0) { x, y, _ ->
    v * sqrt(max(0.0, radiusAt(p, x).let { it * it } - (y - axis).let { it * it }))
}

private fun revolvedAboutY(p: Profile, axis: Double, v: Double) = Solid(false, 2 * p.r.max() * v, 0.0) { x, y, _ ->
    v * sqrt(max(0.0, radiusAt(p, y).let { it * it } - (x - axis).let { it * it }))
}

private fun solidOf(polys: Polygons, b: Bounds, center: Point, xAxis: Double?, canRevolve: Boolean, v: Double): Solid {
    val revolved = if (xAxis != null) {
        revolveProfile(polys, false, b.minX, b.maxX, xAxis)?.let { revolvedAboutX(it, xAxis, v) }
    } else if (canRevolve) {
        revolveProfile(polys, true, b.minY, b.maxY, center.x)?.let { revolvedAboutY(it, center.x, v) }
    } else {
        null
    }
    return revolved ?: inflatedSolid(polys, b, v)
}

private fun levelSlices(g: Grid, polys: Polygons, solid: Solid, half: Double): List<VolumeSlice> {
    val rz = solid.rz
    val inflated = solid.inflated
    val out = ArrayList<VolumeSlice>()
    val steps = if (inflated) max(4, min(14, Math.round(rz / 0.8).toInt())) else max(5, min(18, Math.round(half / 1.1).toInt()))
    val ls = if (inflated) listOf(half - rz) + levels(half - rz, rz, steps) else levels(0.0, half, steps)
    fun addLevel(level: Double, box: ThinBox?): Boolean {
        val loops = levelLoops(g, level, box)
        if (loops.isEmpty()) return false
        val inset = loops.flatten().filterIndexed { k, _ -> k % 3 == 0 }.minOf { edgeDistance(polys, it.x, it.y) }
        val d = loopsToPath(loops)
        val boxed = bounds(loops)
        out.add(VolumeSlice(level, d, true, boxed, inset))
        out.add(VolumeSlice(-level, d, true, boxed, inset))
        return true
    }
    val used = arrayListOf(0.0)
    for (level in ls) if (level >= 0.02 && addLevel(level, null)) used.add(level)
    for (box in if (inflated) thinBoxes(g) else emptyList()) {
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
    return out
}

private fun sideSlices(g: Grid, b: Bounds, solid: Solid): List<VolumeSlice> {
    val out = ArrayList<VolumeSlice>()
    val every = max(1, Math.round(max(1.1, (b.maxX - b.minX) / MAX_X_SLICES) / g.step).toInt())
    val edgeHeight = { x: Double, y: Double -> solid.heightAt(x, y, 0.0) }
    val before = floor((every - 1) / 2.0).toInt()
    var i = 1
    while (i < g.nx - 1) {
        val loops = columnLoops(g, max(0, i - before), min(g.nx - 1, i - before + every - 1), edgeHeight)
        if (loops.isNotEmpty()) {
            out.add(VolumeSlice(g.x0 + i * g.step, loopsToPath(loops), true, bounds(loops), 0.0))
        }
        i += every
    }
    return out
}

private fun buildPart(outline: BodySubpath, holes: List<BodySubpath>, partCount: Int, xAxis: Double?, v: Double): VolumePart {
    val cuts = holes.filter { h -> h.holeProbe != null && isInside(outline.polys, h.holeProbe.x, h.holeProbe.y) }
    val polys = outline.polys + cuts.flatMap { it.polys }
    val b = bounds(outline.polys)
    val center = Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2)
    val step = max(0.25, min(0.45, min(b.maxX - b.minX, b.maxY - b.minY) / 24))
    val solid = solidOf(polys, b, center, xAxis, partCount > 1 && cuts.isEmpty(), v)
    val height = { x: Double, y: Double ->
        if (isInside(polys, x, y)) solid.heightAt(x, y, if (solid.inflated) edgeDistance(polys, x, y) else 0.0) else 0.0
    }

    val core = VolumeSlice(0.0, outline.d + cuts.joinToString("") { it.d }, false, b, 0.0)
    val half = solid.depth / 2
    if (half <= 0.05) return VolumePart(center, listOf(core), emptyList(), height)
    val g = sampleGrid(polys, b, step, solid.heightAt)
    val zSlices = (listOf(core) + levelSlices(g, polys, solid, half)).sortedWith(compareBy(numericOrder) { it.at })
    return VolumePart(center, zSlices, sideSlices(g, b, solid), height)
}

/** Build the implied volume. [volume] scales every depth, finite and not negative; 0 is flat. Pure, but too slow for a draw pass. */
internal fun buildVolume(eyes: AvatarEyes, volume: Double, body: BodyAnalysis): Volume {
    val outlines = body.subpaths.filter { !it.hole }
    val holes = body.subpaths.filter { it.hole }
    val xAxis = revolveAxis(body)
    val parts = outlines.map { buildPart(it, holes, outlines.size, xAxis, volume) }

    val eyeScale = boostedEyeScale(body.polys, eyes)
    val centers = eyeCenters(eyes)
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
