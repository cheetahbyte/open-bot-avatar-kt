package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Contours
import re.leob.openbotavatar.kt.geometry.EdgeBands
import re.leob.openbotavatar.kt.geometry.EdgeIndex
import re.leob.openbotavatar.kt.geometry.Field
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.RUN
import re.leob.openbotavatar.kt.geometry.RunRange
import re.leob.openbotavatar.kt.geometry.SampleGrid
import re.leob.openbotavatar.kt.geometry.runsOf
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Thin regions, where no inscribed circle within THIN_REACH reaches radius THIN, get refinement slices. */
private const val THIN = 4.0
private const val THIN_REACH = 5.0
private const val THIN_MARGIN = 2.0

/** How far a simplified outline may stray from the traced one. */
private const val TOLERANCE = 0.04

/** Sampled signed distance (positive inside) and surface height (0 outside) over a part's bounds. */
internal class Grid(
    val x0: Double,
    val y0: Double,
    val step: Double,
    val nx: Int,
    val ny: Int,
    val sd: DoubleArray,
    val h: DoubleArray,
)

private fun gridOver(b: Bounds, step: Double): SampleGrid {
    val pad = 1.0
    return SampleGrid(
        b.minX - pad,
        b.minY - pad,
        step,
        ceil((b.maxX - b.minX + 2 * pad) / step).toInt() + 1,
        ceil((b.maxY - b.minY + 2 * pad) / step).toInt() + 1,
    )
}

internal fun sampleGrid(rows: EdgeBands, index: EdgeIndex, b: Bounds, step: Double, surface: Surface): Grid {
    val g = gridOver(b, step)
    val sd = index.distances(g)
    val h = DoubleArray(sd.size)
    val xs = DoubleArray(g.nx) { g.x0 + it * step }
    val inside = BooleanArray(g.nx)
    for (j in 0 until g.ny) {
        val y = g.y0 + j * step
        rows.insideRow(y, xs, inside)
        for (i in 0 until g.nx) {
            val k = j * g.nx + i
            if (inside[i]) h[k] = surface.height(xs[i], y, sd[k]) else sd[k] = -sd[k]
        }
    }
    return Grid(g.x0, g.y0, step, g.nx, g.ny, sd, h)
}

/** Twice the largest inward distance over the samples of a grid like [sampleGrid]'s. */
internal fun gridThickness(rows: EdgeBands, index: EdgeIndex, b: Bounds, step: Double): Double =
    2 * index.widest(gridOver(b, step), rows)

internal data class ThinBox(val i0: Int, val i1: Int, val j0: Int, val j1: Int, val top: Double)

/**
 * A part's cross-section at a level, clipped to the outline (and to a box, if given), as loops.
 *
 * It is the zero contour of a field that is positive inside the cross-section, zero on its edge and
 * roughly a distance in viewBox units: smooth across the rim, so contours interpolated between grid
 * samples follow the real edge instead of the grid. The field is capped by the signed distance to
 * the outline; both are continuous across it, so the smaller is too.
 */
internal fun interface LevelCut {
    fun loops(level: Double, box: ThinBox?): List<List<Point>>
}

internal fun levelCuts(g: Grid, surface: Surface, contours: Contours): LevelCut =
    if (surface.profile == null) insetCuts(g, surface, contours) else revolveCuts(g, surface, surface.profile, contours)

/**
 * Cross-sections of an inflated part. Height grows with distance from the outline, so a level is
 * reached at a fixed inset: the field is the signed distance less that inset, which is the distance
 * grid itself contoured at the inset.
 */
private fun insetCuts(g: Grid, surface: Surface, contours: Contours): LevelCut {
    val whole = Field(g.sd, g.nx, g.ny, g.x0, g.y0, g.step)
    val range = RunRange(g.sd, g.nx, g.ny)
    val flat = BooleanArray(range.min.size)
    return LevelCut { level, box ->
        val drop = surface.insetAt(level)
        if (box != null) return@LevelCut contours.outlines(boxField(g, drop, box), 0.0, TOLERANCE)
        // Subtracting keeps order, so a run whose extremes are on one side of the inset is all on it.
        for (k in flat.indices) flat[k] = range.min[k] - drop >= 0 || range.max[k] - drop < 0
        contours.outlines(whole, drop, TOLERANCE, flat)
    }
}

/** The inset field on a box's own samples plus one ring outside it, where the clip is negative. */
private fun boxField(g: Grid, drop: Double, box: ThinBox): Field {
    val i0 = max(0, box.i0 - 1)
    val i1 = min(g.nx - 1, box.i1 + 1)
    val j0 = max(0, box.j0 - 1)
    val j1 = min(g.ny - 1, box.j1 + 1)
    val nx = i1 - i0 + 1
    val ny = j1 - j0 + 1
    val values = DoubleArray(nx * ny)
    for (j in 0 until ny) for (i in 0 until nx) {
        val sd = g.sd[(j + j0) * g.nx + i + i0]
        val edge = minOf(minOf(i + i0 - box.i0, box.i1 - i - i0), minOf(j + j0 - box.j0, box.j1 - j - j0))
        values[j * nx + i] = min(min(sd - drop, sd), g.step * edge)
    }
    return Field(values, nx, ny, g.x0 + i0 * g.step, g.y0 + j0 * g.step, g.step)
}

/**
 * Cross-sections of a revolved part, which takes no boxes. With r the radius at a sample and t its
 * offset from the axis, height >= level where t² <= r² - (level / v)²: that difference over twice
 * the radius is about a distance, and it is the field.
 */
private fun revolveCuts(g: Grid, surface: Surface, profile: Profile, contours: Contours): LevelCut {
    val nx = g.nx
    val ny = g.ny
    val alongX = surface.kind == PartKind.REVOLVE_X
    // The field is (base - (level / v)²) / over: only the middle term changes with the level.
    val base = DoubleArray(nx * ny)
    val over = DoubleArray(nx * ny)
    for (j in 0 until ny) {
        val y = g.y0 + j * g.step
        for (i in 0 until nx) {
            val x = g.x0 + i * g.step
            val r = profile.radiusAt(if (alongX) x else y)
            val t = if (alongX) y - surface.axis else x - surface.axis
            base[j * nx + i] = r * r - t * t
            over[j * nx + i] = 2 * max(r, 0.5)
        }
    }
    val values = DoubleArray(nx * ny)
    val field = Field(values, nx, ny, g.x0, g.y0, g.step)
    val rise = RunRange(base, nx, ny)
    val edge = RunRange(g.sd, nx, ny)
    val runs = runsOf(nx)
    val flat = BooleanArray(rise.min.size)
    return LevelCut { level, _ ->
        val z = level / max(surface.v, 1e-6)
        val drop = z * z
        for (k in flat.indices) {
            // Subtracting keeps order and `over` is positive, so the extremes of a run tell its sign.
            val out = rise.max[k] - drop < 0 || edge.max[k] < 0
            val within = rise.min[k] - drop >= 0 && edge.min[k] >= 0
            flat[k] = out || within
            if (flat[k]) continue
            // Only runs the contour may cross are read, so only they are filled in.
            val r = k % runs
            val j = (k - r) / runs
            for (q in j * nx + r * RUN..j * nx + min(nx - 1, (r + 1) * RUN)) {
                values[q] = min((base[q] - drop) / over[q], g.sd[q])
                values[q + nx] = min((base[q + nx] - drop) / over[q + nx], g.sd[q + nx])
            }
        }
        contours.outlines(field, 0.0, TOLERANCE, flat)
    }
}

internal fun thinBoxes(g: Grid): List<ThinBox> {
    val nx = g.nx
    val ny = g.ny
    val reach = ceil(THIN_REACH / g.step).toInt()
    // Whether a sample THIN or more inside the outline lies within `reach` samples (a square window,
    // rows then columns), from running counts.
    val wide = BooleanArray(nx * ny)
    val near = BooleanArray(nx * ny)
    val count = IntArray(max(nx, ny) + 1)
    for (j in 0 until ny) {
        for (i in 0 until nx) count[i + 1] = count[i] + if (g.sd[j * nx + i] >= THIN) 1 else 0
        for (i in 0 until nx) wide[j * nx + i] = count[min(nx, i + reach + 1)] > count[max(0, i - reach)]
    }
    for (i in 0 until nx) {
        for (j in 0 until ny) count[j + 1] = count[j] + if (wide[j * nx + i]) 1 else 0
        for (j in 0 until ny) near[j * nx + i] = count[min(ny, j + reach + 1)] > count[max(0, j - reach)]
    }
    fun thin(k: Int) = g.sd[k] > 0 && !near[k]
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

/**
 * The silhouette, seen from the side, of the slab of columns [c0]..[c1]: per run of rows inside the
 * outline, its largest height, mirrored. Where a run meets the outline the profile ends at the
 * surface height right there, not at the last sample's.
 */
internal fun columnLoops(g: Grid, c0: Int, c1: Int, surface: Surface, contours: Contours): List<List<Point>> {
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
    // One run of rows at a time: its right side (u, y in turn) from top to bottom, then the mirror image back up.
    val xy = DoubleArray(4 * (g.ny + 2))
    var n = 0
    fun add(u: Double, y: Double) {
        xy[n++] = u
        xy[n++] = y
    }
    /** The end of a run: where the outline crosses between its last sample and the neighbor outside. */
    fun addEnd(inside: Int, outside: Int) {
        val yIn = g.y0 + inside * g.step
        if (outside < 0 || outside >= g.ny) {
            add(h[inside], yIn)
            return
        }
        val y = yIn + ((g.y0 + outside * g.step - yIn) * sd[inside]) / (sd[inside] - sd[outside])
        var top = 0.0
        for (c in c0..c1) top = max(top, surface.height(g.x0 + c * g.step, y, 0.0))
        add(min(h[inside], top), y)
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
        n = 0
        addEnd(j0, j0 - 1)
        for (k in j0..j1) add(h[k], g.y0 + k * g.step)
        addEnd(j1, j1 + 1)
        val points = n / 2
        if ((0 until points).maxOf { xy[2 * it] } < 0.05) continue
        for (k in points - 1 downTo 0) add(-xy[2 * k], xy[2 * k + 1])
        loops.add(contours.simplified(xy, 0, 2 * points, TOLERANCE))
    }
    return loops
}
