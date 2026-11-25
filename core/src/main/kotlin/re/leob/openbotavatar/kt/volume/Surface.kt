package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.ExperimentalOpenBotAvatarApi
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.forEachEdge
import re.leob.openbotavatar.kt.geometry.sampleRange
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What gives a part its depth. */
@ExperimentalOpenBotAvatarApi
public enum class PartKind {
    /** A rounded slab: an elliptical fillet of the distance to the outline. */
    INFLATE,

    /** A solid of revolution about a horizontal axis. */
    REVOLVE_X,

    /** A solid of revolution about a vertical axis. */
    REVOLVE_Y,
}

private const val MAX_PART_DEPTH = 30.0

/** Half-widths along an axis of revolution. */
internal class Profile(val start: Double, val step: Double, val r: DoubleArray) {
    fun radiusAt(s: Double): Double {
        val t = (s - start) / step
        if (t < 0 || t > r.size - 1) return 0.0
        val k = min(r.size - 2, floor(t).toInt())
        return r[k] + (r[k + 1] - r[k]) * (t - k)
    }
}

/** A part's front surface: an inflated part's edge fillet, or a revolved part's radius profile about its axis. */
internal class Surface private constructor(
    val kind: PartKind,
    val thickness: Double,
    val depth: Double,
    /** Inflated parts: fillet radii, in-plane and in depth. */
    val rxy: Double,
    val rz: Double,
    /** Revolved parts: radius along the axis, the axis itself and the volume scale. */
    val profile: Profile?,
    val axis: Double,
    val v: Double,
) {
    /** Height at a point inside the part, [sd] from its outline (which only inflated parts use). */
    fun height(x: Double, y: Double, sd: Double): Double {
        if (profile == null) return filletHeight(sd)
        val alongX = kind == PartKind.REVOLVE_X
        val r = profile.radiusAt(if (alongX) x else y)
        val t = if (alongX) y - axis else x - axis
        return v * sqrt(max(0.0, r * r - t * t))
    }

    private fun filletHeight(dist: Double): Double {
        val half = depth / 2
        if (rxy <= 0 || dist >= rxy) return half
        val k = 1 - max(0.0, dist) / rxy
        return half - rz + sqrt(max(0.0, 1 - k * k)) * rz
    }

    /** Inverse of the fillet: how far inside the outline an inflated part reaches this level. */
    fun insetAt(level: Double): Double {
        val half = depth / 2
        if (rxy <= 0 || rz <= 0 || level <= half - rz) return 0.0
        if (level >= half) return rxy
        val s = (level - (half - rz)) / rz
        return rxy * (1 - sqrt(max(0.0, 1 - s * s)))
    }

    companion object {
        fun inflated(thickness: Double, b: Bounds, v: Double): Surface {
            val long = max(b.maxX - b.minX, b.maxY - b.minY)
            val raw = 0.8 * max(thickness, min(0.4 * long, 2.4 * thickness))
            val depth = min(MAX_PART_DEPTH, max(3.0, raw)) * v
            return Surface(PartKind.INFLATE, thickness, depth, min(thickness / 2, depth / 2) * 0.9, (depth / 2) * 0.85, null, 0.0, v)
        }

        fun revolved(kind: PartKind, profile: Profile, axis: Double, v: Double): Surface {
            val thickness = 2 * profile.r.max()
            return Surface(kind, thickness, thickness * v, 0.0, 0.0, profile, axis, v)
        }
    }
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

/** Half-widths about [center] if every line crosses the part as one interval centered on it, else null. */
internal fun revolveProfile(polys: Polygons, rows: Boolean, lo: Double, hi: Double, center: Double): Profile? {
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

/** Horizontal axis of revolution shared by the whole body, if it looks like a turned object seen from the side. */
internal fun revolveAxis(body: BodyAnalysis): Double? {
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
        body.rows.insideRow(y, xs, top)
        body.rows.insideRow(2 * axis - y, xs, bottom)
        for (k in xs.indices) {
            if (top[k] || bottom[k]) filled++
            if (top[k] != bottom[k]) off++
        }
    }
    return if (filled > 0 && off.toDouble() / filled < 0.02) axis else null
}
