package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.ExperimentalOpenBotAvatarApi
import re.leob.openbotavatar.kt.PathBox
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Contours
import re.leob.openbotavatar.kt.geometry.EdgeBands
import re.leob.openbotavatar.kt.geometry.EdgeIndex
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.loopsToPath
import re.leob.openbotavatar.kt.geometry.numericOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which planes a stack of cross-sections lies in. */
@ExperimentalOpenBotAvatarApi
public enum class SliceAxis(internal val letter: Char) {
    /** Planes z = at that face the viewer, their paths in (x, y). */
    Z('z'),

    /** Planes x = at that face sideways, their paths in (u = -z, y). */
    X('x'),
}

/** One plain cross-section of a part, in its plane's coordinates. */
@ExperimentalOpenBotAvatarApi
public class VolumeSlice internal constructor(
    public val axis: SliceAxis,
    /** Where the plane is along the axis. */
    public val at: Double,
    public val d: String,
    /** Contour paths use evenodd; the core slice reuses the original nonzero outline. */
    public val evenOdd: Boolean,
    public val box: PathBox,
    /** z-slices: smallest distance from this cross-section to the outline (0 for the core and walls). */
    public val inset: Double,
)

/** One filled subpath of a body, as a solid mirrored about z = 0. */
@ExperimentalOpenBotAvatarApi
public class VolumePart internal constructor(
    /** Outline plus the holes that cut it, serialized. */
    public val d: String,
    /** The same outline as polygons. */
    public val polys: List<List<Point>>,
    public val bounds: Bounds,
    public val center: Point,
    public val zSlices: List<VolumeSlice>,
    public val xSlices: List<VolumeSlice>,
    private val surface: Surface,
    private val rows: EdgeBands,
    private val index: EdgeIndex,
) {
    public val kind: PartKind get() = surface.kind

    /** Twice the largest inscribed radius (inflated) or the largest diameter (revolved). */
    public val thickness: Double get() = surface.thickness

    /** Front-to-back extent, centered on z = 0. */
    public val depth: Double get() = surface.depth

    /** Inflated parts: elliptical edge fillet radii, in-plane and in depth. */
    public val rxy: Double get() = surface.rxy
    public val rz: Double get() = surface.rz

    /** Revolved parts: the axis (y for REVOLVE_X, x for REVOLVE_Y). */
    public val axis: Double? get() = if (kind == PartKind.INFLATE) null else surface.axis

    /** Front surface height at a point, 0 outside the part. */
    public fun height(x: Double, y: Double): Double =
        if (rows.inside(x, y)) surface.height(x, y, if (kind == PartKind.INFLATE) index.distance(x, y) else 0.0) else 0.0
}

/** The tangent plane of the surface at an eye's center: its height there and its slope each way. */
@ExperimentalOpenBotAvatarApi
public data class EyePlane(val x: Double, val y: Double, val z: Double, val dzdx: Double, val dzdy: Double)

/** The volume implied by a flat silhouette, viewBox units, z toward the viewer. Immutable. */
@ExperimentalOpenBotAvatarApi
public class Volume internal constructor(
    public val parts: List<VolumePart>,
    public val bounds: Bounds,
    /** Index of the part carrying the eyes. */
    public val eyePart: Int,
    /** Highest host surface under each eye and its gaze travel, for setting eyes into the surface. */
    public val eyeZ: List<Double>,
    /** Drawn eye scale: the definition's scale enlarged as far as the body allows. */
    public val eyeScale: Double,
    /** Where each flat eye lies. */
    public val eyePlanes: List<EyePlane>,
)

private const val MAX_X_SLICES = 40.0
private const val FINE_Z = 0.6
private const val COARSE_STEP = 0.6

private fun Bounds.toBox() = PathBox(minX, minY, maxX - minX, maxY - minY)

private fun levels(flat: Double, round: Double, steps: Int): List<Double> =
    (1 until steps).map { flat + round * sin((it.toDouble() / steps) * (PI / 2)) }

private fun levelSlices(g: Grid, index: EdgeIndex, surface: Surface, contours: Contours): List<VolumeSlice> {
    val half = surface.depth / 2
    val rz = surface.rz
    val inflated = surface.kind == PartKind.INFLATE
    val out = ArrayList<VolumeSlice>()
    val steps = if (inflated) max(4, min(14, Math.round(rz / 0.8).toInt())) else max(5, min(18, Math.round(half / 1.1).toInt()))
    // Inflated parts keep a straight wall up to half - rz; one slice at its end carries it.
    val ls = if (inflated) listOf(half - rz) + levels(half - rz, rz, steps) else levels(0.0, half, steps)
    val cut = levelCuts(g, surface, contours)
    fun addLevel(level: Double, box: ThinBox?): Boolean {
        val loops = cut.loops(level, box)
        if (loops.isEmpty()) return false
        // Every third point of the loops, counted across them.
        var inset = Double.POSITIVE_INFINITY
        var k = 0
        for (loop in loops) for (p in loop) if (k++ % 3 == 0) inset = min(inset, index.distance(p.x, p.y))
        val d = loopsToPath(loops)
        val extent = bounds(loops).toBox()
        out.add(VolumeSlice(SliceAxis.Z, level, d, true, extent, inset))
        out.add(VolumeSlice(SliceAxis.Z, -level, d, true, extent, inset))
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

private fun sideSlices(g: Grid, b: Bounds, surface: Surface, contours: Contours): List<VolumeSlice> {
    val out = ArrayList<VolumeSlice>()
    val every = max(1, Math.round(max(1.1, (b.maxX - b.minX) / MAX_X_SLICES) / g.step).toInt())
    // One plane per `every` grid columns, standing for the whole slab around it.
    val before = floor((every - 1) / 2.0).toInt()
    for (i in 1 until g.nx - 1 step every) {
        val loops = columnLoops(g, max(0, i - before), min(g.nx - 1, i - before + every - 1), surface, contours)
        if (loops.isNotEmpty()) {
            out.add(VolumeSlice(SliceAxis.X, g.x0 + i * g.step, loopsToPath(loops), true, bounds(loops).toBox(), 0.0))
        }
    }
    return out
}

private fun buildPart(outline: BodySubpath, holes: List<BodySubpath>, partCount: Int, xAxis: Double?, v: Double): VolumePart {
    val cuts = holes.filter { h -> h.holeProbe != null && outline.rows.inside(h.holeProbe.x, h.holeProbe.y) }
    val polys = outline.polys + cuts.flatMap { it.polys }
    val rows = if (cuts.isEmpty()) outline.rows else EdgeBands(polys)
    val b = bounds(outline.polys)
    val index = EdgeIndex(polys, b)
    val center = Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2)
    val step = max(0.25, min(0.45, min(b.maxX - b.minX, b.maxY - b.minY) / 24))

    // Classify: shared horizontal axis, own vertical axis (multi-part bodies only), or inflated.
    val revolved = if (xAxis != null) {
        revolveProfile(polys, false, b.minX, b.maxX, xAxis)?.let { Surface.revolved(PartKind.REVOLVE_X, it, xAxis, v) }
    } else if (partCount > 1 && cuts.isEmpty()) {
        revolveProfile(polys, true, b.minY, b.maxY, center.x)?.let { Surface.revolved(PartKind.REVOLVE_Y, it, center.x, v) }
    } else {
        null
    }
    val surface = revolved ?: Surface.inflated(gridThickness(rows, index, b, max(step, COARSE_STEP)), b, v)

    val core = VolumeSlice(SliceAxis.Z, 0.0, outline.d + cuts.joinToString("") { it.d }, false, b.toBox(), 0.0)
    var zSlices = listOf(core)
    var xSlices = emptyList<VolumeSlice>()
    if (surface.depth / 2 > 0.05) {
        val g = sampleGrid(rows, index, b, step, surface)
        val contours = Contours()
        zSlices = (zSlices + levelSlices(g, index, surface, contours)).sortedWith(compareBy(numericOrder) { it.at })
        xSlices = sideSlices(g, b, surface, contours)
    }
    return VolumePart(core.d, polys, b, center, zSlices, xSlices, surface, rows, index)
}

/** Build the implied volume. [volume] scales every depth, finite and not negative; 0 is flat. Pure, but too slow for a draw pass. */
internal fun buildVolume(eyes: AvatarEyes, volume: Double, body: BodyAnalysis): Volume {
    val outlines = body.subpaths.filter { !it.hole }
    val holes = body.subpaths.filter { it.hole }
    val xAxis = revolveAxis(body)
    val parts = outlines.map { buildPart(it, holes, outlines.size, xAxis, volume) }

    val eyeScale = boostedEyeScale(body.rows, eyes)
    val centers = eyeCenters(eyes)
    // The eyes ride on whichever part is highest under their centers.
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
    fun heightAt(x: Double, y: Double) = host?.height(x, y) ?: 0.0
    // Limit the socket to its host even when a taller part overlaps the face.
    val w = (EYE_W * eyeScale) / 2 + GAZE_X * eyeScale
    val hh = (EYE_H * eyeScale) / 2 + GAZE_Y * eyeScale
    val fractions = doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)
    val eyeZ = centers.map { c -> fractions.maxOf { fx -> fractions.maxOf { fy -> heightAt(c.x + fx * w, c.y + fy * hh) } } }
    val eyePlanes = centers.map { (x, y) ->
        val e = 0.75
        EyePlane(
            x, y, heightAt(x, y),
            (heightAt(x + e, y) - heightAt(x - e, y)) / (2 * e),
            (heightAt(x, y + e) - heightAt(x, y - e)) / (2 * e),
        )
    }
    return Volume(parts, body.bounds, eyePart, eyeZ, eyeScale, eyePlanes)
}
