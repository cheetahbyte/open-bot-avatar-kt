package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.clamp
import re.leob.openbotavatar.kt.geometry.numericOrder
import re.leob.openbotavatar.kt.geometry.plain
import re.leob.openbotavatar.kt.volume.BodyAnalysis
import re.leob.openbotavatar.kt.volume.EYE_H
import re.leob.openbotavatar.kt.volume.EYE_W
import re.leob.openbotavatar.kt.volume.GAZE_X
import re.leob.openbotavatar.kt.volume.GAZE_Y
import re.leob.openbotavatar.kt.volume.HappyArc
import re.leob.openbotavatar.kt.volume.SliceAxis
import re.leob.openbotavatar.kt.volume.VolumeSlice
import re.leob.openbotavatar.kt.volume.buildVolume
import re.leob.openbotavatar.kt.volume.eyeCenters
import re.leob.openbotavatar.kt.volume.gazeRange
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MAX_DEPTH = 14.0

/** Eyes sit this far above the surface so they never touch the body planes. */
private const val EYE_LIFT = 1.5

/** Minimum toward-viewer component of the surface normal for an eye to be drawn. */
private const val EYE_FACING = 0.22

/** Closest on-screen spacing, in density-independent units, between cross-sections a small live avatar keeps. */
private const val SLICE_GAP_PX = 0.75

private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

private fun depthOption(depth: Double): Double {
    val d = clamp(if (depth.isFinite()) depth else DEFAULT_DEPTH, 0.0, MAX_DEPTH)
    return Math.round(d * 2) / 2.0
}

private fun Bounds.toBox() = PathBox(minX, minY, maxX - minX, maxY - minY)

private fun normalizeHex(input: String?, fallback: String): String {
    val raw = input?.trim()?.takeIf { HEX.matches(it) } ?: fallback
    val hex = raw.substring(1).lowercase()
    return if (hex.length == 3) "#" + hex.map { "$it$it" }.joinToString("") else "#$hex"
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
    body: BodyAnalysis,
) {
    private class Kept(val part: Int, val index: Int, val slice: VolumeSlice, val path: FramePath)

    private class Layer(val depth: Double, val shape: FrameShape, val path: FramePath? = null)

    private val volume = buildVolume(definition.eyes, depth / DEFAULT_DEPTH, body)
    private val range = gazeRange(body.polys, definition.eyes.copy(scale = volume.eyeScale))
    private val seed = hashString(definition.id)
    private val framePaths: Map<String, FramePath> = buildMap {
        val prefix = "oba-${hashString("${definition.id}|${definition.path}|${plain(depth)}").toUInt().toString(36)}"
        for (part in volume.parts) for (s in part.zSlices + part.xSlices) {
            if (s.d !in this) put(s.d, FramePath("$prefix-$size", s.d, s.evenOdd, s.bounds.toBox()))
        }
    }
    private val detail = ConcurrentHashMap<Int, List<Kept>>()

    /**
     * Compute one frame. With [renderedSize], the width in density-independent units, cross-sections
     * that would sit too close to show apart are skipped; live renderers pass it, exports leave it out.
     */
    public fun frame(options: FrameOptions = FrameOptions(), renderedSize: Double? = null): AvatarFrame {
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
        val pivot = pivotOf(volume.bounds)
        val v = ViewBasis(pose.yaw, pose.pitch)
        val slices = sliceLayers(v, pivot, renderedSize)
        val layers = (slices + eyeLayers(v, pivot, pose)).sortedWith(compareBy(numericOrder) { it.depth })
        return AvatarFrame(
            name = definition.name,
            color = normalizeHex(options.color, DEFAULT_COLOR),
            ink = normalizeHex(options.eyeColor, DEFAULT_EYE_COLOR),
            paths = slices.mapNotNull { it.path }.distinctBy { it.id },
            root = rootMatrix(pose, volume.bounds),
            shapes = layers.map { it.shape },
        )
    }

    /** Render to a standalone SVG string; [size] sets its width and height, else it is fluid. */
    public fun svg(options: FrameOptions = FrameOptions(), size: Double? = null): String =
        frame(options).toSvg(size)

    private fun sliceLayers(v: ViewBasis, pivot: Point, renderedSize: Double?): List<Layer> {
        val stack = v.activeStack
        val sweep = hypot(v.sY, v.cY * v.sP)
        val insetScale = min(abs(v.cY), abs(v.cP))
        val layers = ArrayList<Layer>()
        for (kept in keptSlices(stack, renderedSize)) {
            val s = kept.slice
            if (s.inset > 0 && abs(s.at) * sweep <= s.inset * insetScale) continue
            val onEyePart = kept.part == volume.eyePart
            val center = volume.parts[kept.part].center
            val anchorX = if (onEyePart) definition.eyes.x else center.x
            val anchorY = if (onEyePart) definition.eyes.y else center.y
            val depth = if (stack == SliceAxis.Z) v.depthOf(anchorX, anchorY, s.at, pivot) else v.depthOf(s.at, anchorY, 0.0, pivot)
            val matrix = if (stack == SliceAxis.Z) v.plane(s.at, pivot) else v.sidePlane(s.at, pivot)
            layers.add(Layer(depth, FrameShape.Slice("${kept.part}${stack.letter}${kept.index}", kept.path.id, matrix), kept.path))
        }
        return layers
    }

    private fun eyeLayers(v: ViewBasis, pivot: Point, pose: Pose): List<Layer> =
        eyeMarks(pose).mapIndexedNotNull { index, mark ->
            if (volume.eyeNormals[index].any { v.facing(it) <= EYE_FACING }) return@mapIndexedNotNull null
            val z = volume.eyeZ[index] + EYE_LIFT
            Layer(v.depthOf(definition.eyes.x, definition.eyes.y, z, pivot) + 1e-6, FrameShape.Eye("eye-$index", v.plane(z, pivot), mark))
        }

    private fun keptSlices(stack: SliceAxis, size: Double?): List<Kept> {
        val px = if (size != null && size.isFinite() && size > 0) Math.round(size).toInt() else 0
        val key = px * 2 + stack.ordinal
        detail[key]?.let { return it }
        val gap = if (px != 0) (SLICE_GAP_PX * 100) / px else 0.0
        val kept = ArrayList<Kept>()
        volume.parts.forEachIndexed { partIndex, part ->
            val slices = if (stack == SliceAxis.Z) part.zSlices else part.xSlices
            var last = Double.NEGATIVE_INFINITY
            slices.forEachIndexed { j, s ->
                val core = stack == SliceAxis.Z && s.at == 0.0 && s.inset == 0.0
                if (core || j == 0 || j == slices.size - 1 || s.at - last >= gap) {
                    last = s.at
                    kept.add(Kept(partIndex, j, s, framePaths.getValue(s.d)))
                }
            }
        }
        if (detail.size >= 32) detail.clear()
        detail[key] = kept
        return kept
    }

    private fun eyeMarks(pose: Pose): List<EyeMark> {
        val s = volume.eyeScale
        val w = EYE_W * s
        val h = EYE_H * s
        val dx = pose.gazeX * (if (pose.gazeX >= 0) range.right else range.left) * GAZE_X * s
        val dy = pose.gazeY * (if (pose.gazeY >= 0) range.down else range.up) * GAZE_Y * s
        return eyeCenters(definition.eyes).mapIndexed { i, c ->
            val ex = c.x + dx
            val ey = c.y + dy
            if (pose.eyeShape == EyeShape.HAPPY) {
                val hw = w * HappyArc.HALF_WIDTH
                val base = ey + h * HappyArc.BASE
                EyeMark.Arc(ex - hw, base, ex, ey - h * HappyArc.PEAK, ex + hw, base, w * HappyArc.STROKE)
            } else {
                val squint = if (i == 0) pose.squintL else pose.squintR
                val open = max(h * max(0.09, (1 - pose.blink) * (1 - squint * 0.4)), w * 0.55)
                val cy = ey + (h - open) * 0.18
                EyeMark.Pill(ex - w / 2, cy - open / 2, w, open, min(w, open) / 2)
            }
        }
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
            val errors = ArrayList<String>()
            val checked = checkAvatar(definition.toJson(), errors) ?: throw InvalidAvatarException(errors)
            val prepared = PreparedAvatar(checked.definition, rounded, checked.body)
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
