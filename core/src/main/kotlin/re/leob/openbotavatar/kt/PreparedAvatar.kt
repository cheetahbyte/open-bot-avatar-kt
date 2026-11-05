package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MAX_DEPTH = 14.0

/** Eyes sit this far above the surface so they never touch the body planes. */
private const val EYE_LIFT = 1.5

/** Minimum toward-viewer component of the surface normal for an eye to be drawn. */
private const val EYE_FACING = 0.22

/** Closest on-screen spacing, in density-independent units, between cross-sections a small live avatar keeps. */
private const val SLICE_GAP_PX = 0.75

private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

private class ViewBasis(yaw: Double, pitch: Double) {
    val cY = cos(yaw)
    val sY = sin(yaw)
    val cP = cos(pitch)
    val sP = sin(pitch)

    val activeStack: SliceAxis get() = if (abs(cY) >= abs(sY)) SliceAxis.Z else SliceAxis.X

    fun facing(n: Vec3) = sP * n.y + cP * (-sY * n.x + cY * n.z)

    /** Orthographic projection of the plane at depth [z]; yaw turns about the vertical, then pitch tips. */
    fun plane(z: Double, pivot: Point) = AvatarMatrix(
        cY,
        sY * sP,
        0.0,
        cP,
        pivot.x - cY * pivot.x + sY * z,
        pivot.y - cP * pivot.y - sY * sP * pivot.x - cY * sP * z,
    )

    /** Projection of the side plane x = [x0], whose local coordinates are (u = -z, y). */
    fun sidePlane(x0: Double, pivot: Point) = AvatarMatrix(
        -sY,
        cY * sP,
        0.0,
        cP,
        pivot.x + cY * (x0 - pivot.x),
        pivot.y - cP * pivot.y + sY * sP * (x0 - pivot.x),
    )

    /** Depth toward the viewer of a 3D point. */
    fun depthOf(x: Double, y: Double, z: Double, pivot: Point) =
        sP * (y - pivot.y) + cP * (-sY * (x - pivot.x) + cY * z)
}

private class EyeState(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val dx: Double,
    val dy: Double,
    /** Drawn pill height after blink and squint, and its downward shift. */
    val open: Double,
    val shift: Double,
    val happy: Boolean,
)

private fun depthOption(depth: Double): Double {
    val d = clamp(if (depth.isFinite()) depth else DEFAULT_DEPTH, 0.0, MAX_DEPTH)
    return Math.round(d * 2) / 2.0
}

private fun normalizeHex(input: String?, fallback: String): String {
    val raw = input?.trim()?.takeIf { HEX.matches(it) } ?: fallback
    val hex = raw.substring(1).lowercase()
    return if (hex.length == 3) "#" + hex.map { "$it$it" }.joinToString("") else "#$hex"
}

private fun pivotOf(b: Bounds) = Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2)

private fun rootMatrix(pose: Pose, b: Bounds): AvatarMatrix {
    val bx = (b.minX + b.maxX) / 2
    val by = b.maxY
    val r = (pose.roll * PI) / 180
    val rotation = AvatarMatrix(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
    val squash = AvatarMatrix(pose.sx, 0.0, 0.0, pose.sy, 0.0, 0.0)
    val about = AvatarMatrix.translate(bx, by) * ((rotation * squash) * AvatarMatrix.translate(-bx, -by))
    return AvatarMatrix.translate(0.0, pose.lift) * about
}

/**
 * A validated definition with its volume built. Building is the expensive step, so call [of] off
 * the main thread; frames afterward are cheap and [frame] is safe from any thread.
 */
public class PreparedAvatar private constructor(
    /** The normalized definition. */
    public val definition: AvatarDefinition,
    /** The depth in use: clamped to 0..14 and rounded to a half. */
    public val depth: Double,
) {
    private val body = analyzeBody(definition.path)
    private val volume = buildVolume(definition, depth / DEFAULT_DEPTH, body)
    private val range = gazeRange(body.polys, definition.eyes.copy(scale = volume.eyeScale))
    private val seed = hashString(definition.id)
    private val idPrefix = "oba-${hashString("${definition.id}|${definition.path}|${plain(depth)}").toUInt().toString(36)}"
    private val pathIds: Map<String, String> = LinkedHashMap<String, String>().also { ids ->
        for (part in volume.parts) for (s in part.zSlices + part.xSlices) {
            if (s.d !in ids) ids[s.d] = "$idPrefix-${ids.size}"
        }
    }
    private val detail = ConcurrentHashMap<Int, BooleanArray>()

    /**
     * Compute one frame. With [renderedSize], the width in density-independent units, cross-sections
     * that would sit less than a unit apart are skipped; live renderers pass it, exports leave it out.
     */
    public fun frame(options: FrameOptions = FrameOptions(), renderedSize: Double? = null): AvatarFrame {
        val b = volume.bounds
        val pose = computePose(
            PoseInput(
                seed = seed,
                time = if (options.time.isFinite()) options.time else 0.0,
                mood = options.mood,
                gaze = options.gaze,
                reducedMotion = options.reducedMotion,
                view = options.view,
                blend = options.moodBlend,
            ),
        )
        val pivot = pivotOf(b)
        val v = ViewBasis(pose.yaw, pose.pitch)
        val stack = v.activeStack
        val eyesX = definition.eyes.x
        val eyesY = definition.eyes.y

        val paths = LinkedHashMap<String, FramePath>()
        val depths = ArrayList<Double>()
        val shapes = ArrayList<FrameShape>()

        // Screen shift per unit of depth, and the smallest on-screen size of an in-plane inset:
        // z-slices hidden behind the core add nothing to the silhouette or the paint.
        val sweep = hypot(v.sY, v.cY * v.sP)
        val insetScale = min(abs(v.cY), abs(v.cP))
        val kept = sliceDetail(stack, renderedSize)
        var k = 0
        volume.parts.forEachIndexed { i, part ->
            val rx = if (i == volume.eyePart) eyesX else part.center.x
            val ry = if (i == volume.eyePart) eyesY else part.center.y
            val slices = if (stack == SliceAxis.Z) part.zSlices else part.xSlices
            slices.forEachIndexed { j, s ->
                val keep = kept[k++]
                val culled = s.axis == SliceAxis.Z && s.inset > 0 && abs(s.at) * sweep <= s.inset * insetScale
                if (keep && !culled) {
                    val path = paths.getOrPut(s.d) { FramePath(pathIds.getValue(s.d), s.d, s.evenOdd, s.box) }
                    val matrix = if (stack == SliceAxis.Z) v.plane(s.at, pivot) else v.sidePlane(s.at, pivot)
                    // Depth keys are taken under the eyes for the eye part, so only depth decides eye-vs-body order.
                    depths.add(if (stack == SliceAxis.Z) v.depthOf(rx, ry, s.at, pivot) else v.depthOf(s.at, ry, 0.0, pivot))
                    shapes.add(FrameShape.Slice("$i${stack.letter}$j", path.id, matrix))
                }
            }
        }
        // Painted on the surface; each eye goes once the surface under it turns away.
        eyeStates(pose).forEachIndexed { index, e ->
            if (volume.eyeNormals[index].all { v.facing(it) > EYE_FACING }) {
                val z = volume.eyeZ[index] + EYE_LIFT
                depths.add(v.depthOf(eyesX, eyesY, z, pivot) + 1e-6)
                shapes.add(FrameShape.Eye("eye-$index", v.plane(z, pivot), eyeMark(e)))
            }
        }
        val order = shapes.indices.sortedWith(compareBy(numericOrder) { depths[it] })
        return AvatarFrame(
            name = definition.name,
            color = normalizeHex(options.color, DEFAULT_COLOR),
            ink = normalizeHex(options.eyeColor, DEFAULT_EYE_COLOR),
            paths = paths.values.toList(),
            root = rootMatrix(pose, b),
            shapes = order.map { shapes[it] },
        )
    }

    /** Render to a standalone SVG string; [size] sets its width and height, else it is fluid. */
    public fun svg(options: FrameOptions = FrameOptions(), size: Double? = null): String =
        frame(options).toSvg(size)

    /**
     * Per slice of a stack, flattened across parts: whether it is kept at this size. Within each
     * part, slices closer than SLICE_GAP_PX to the last kept one drop; the outermost and the core stay.
     */
    private fun sliceDetail(stack: SliceAxis, size: Double?): BooleanArray {
        val px = if (size != null && size.isFinite() && size > 0) Math.round(size).toInt() else 0
        val key = px * 2 + stack.ordinal
        detail[key]?.let { return it }
        val gap = if (px != 0) (SLICE_GAP_PX * 100) / px else 0.0
        val keep = volume.parts.flatMap { part ->
            val slices = if (stack == SliceAxis.Z) part.zSlices else part.xSlices
            var last = Double.NEGATIVE_INFINITY
            slices.mapIndexed { j, s ->
                val core = s.axis == SliceAxis.Z && s.at == 0.0 && s.inset == 0.0
                val kept = core || j == 0 || j == slices.size - 1 || s.at - last >= gap
                if (kept) last = s.at
                kept
            }
        }.toBooleanArray()
        if (detail.size >= 32) detail.clear()
        detail[key] = keep
        return keep
    }

    private fun eyeStates(pose: Pose): List<EyeState> {
        val s = volume.eyeScale
        val w = EYE_W * s
        val h = EYE_H * s
        val dx = pose.gazeX * (if (pose.gazeX >= 0) range.right else range.left) * GAZE_X * s
        val dy = pose.gazeY * (if (pose.gazeY >= 0) range.down else range.up) * GAZE_Y * s
        return eyeCenters(definition.eyes).mapIndexed { i, c ->
            val squint = if (i == 0) pose.squintL else pose.squintR
            val open = max(h * max(0.09, (1 - pose.blink) * (1 - squint * 0.4)), w * 0.55)
            EyeState(c.x, c.y, w, h, dx, dy, open, (h - open) * 0.18, pose.eyeShape == EyeShape.HAPPY)
        }
    }

    private fun eyeMark(e: EyeState): EyeMark {
        if (e.happy) {
            val ex = e.x + e.dx
            val ey = e.y + e.dy
            val hw = e.w * HappyArc.HALF_WIDTH
            val base = ey + e.h * HappyArc.BASE
            return EyeMark.Arc(ex - hw, base, ex, ey - e.h * HappyArc.PEAK, ex + hw, base, e.w * HappyArc.STROKE)
        }
        val cx = e.x + e.dx
        val cy = e.y + e.dy + e.shift
        return EyeMark.Pill(cx - e.w / 2, cy - e.open / 2, e.w, e.open, min(e.w, e.open) / 2)
    }

    public companion object {
        private const val CACHE_SIZE = 64
        private val cache = object : LinkedHashMap<Pair<AvatarDefinition, Double>, PreparedAvatar>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Pair<AvatarDefinition, Double>, PreparedAvatar>) =
                size > CACHE_SIZE
        }

        /** Validate and build, or reuse a recent build. Throws [InvalidAvatarException]. Call off the main thread. */
        public fun of(definition: AvatarDefinition, depth: Double = DEFAULT_DEPTH): PreparedAvatar {
            val rounded = depthOption(depth)
            cached(definition, rounded)?.let { return it }
            val prepared = when (val result = validateAvatar(definition)) {
                is ValidationResult.Valid -> PreparedAvatar(result.value, rounded)
                is ValidationResult.Invalid -> throw InvalidAvatarException(result.errors)
            }
            synchronized(cache) { cache[definition to rounded] = prepared }
            return prepared
        }

        /** A recent build, if there is one. Cheap enough for the main thread. */
        public fun cached(definition: AvatarDefinition, depth: Double = DEFAULT_DEPTH): PreparedAvatar? =
            synchronized(cache) { cache[definition to depthOption(depth)] }
    }
}

public class InvalidAvatarException(public val errors: List<String>) :
    IllegalArgumentException("Invalid avatar definition: ${errors.joinToString("; ")}")

/** Compute one flat frame of a definition. Throws [InvalidAvatarException]. Builds on first use. */
public fun avatarFrame(
    definition: AvatarDefinition,
    options: FrameOptions = FrameOptions(),
    depth: Double = DEFAULT_DEPTH,
): AvatarFrame = PreparedAvatar.of(definition, depth).frame(options)

/** Render a definition to a standalone SVG string. Throws [InvalidAvatarException]. Builds on first use. */
public fun renderAvatarSvg(
    definition: AvatarDefinition,
    options: FrameOptions = FrameOptions(),
    depth: Double = DEFAULT_DEPTH,
    size: Double? = null,
): String = PreparedAvatar.of(definition, depth).svg(options, size)
