package re.leob.openbotavatar.kt

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

/** Closest on-screen spacing, in density-independent units, between cross-sections a small live avatar keeps. */
private const val SLICE_GAP_PX = 0.75

private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

private val PLAIN_NAME = Regex("[A-Za-z][A-Za-z0-9]*")

/**
 * A mark's part in a path id, after the avatar's own prefix. A body's slices are numbered, so a
 * part's name must not be a number, and it goes into markup, so it must be plain: a name of letters
 * and digits that starts with a letter is kept (the built-in parts' are), and any other is replaced
 * by a hash of it, set apart by a hyphen no kept name has.
 */
private fun markId(shape: String): String =
    if (PLAIN_NAME.matches(shape)) shape else "m-${hashString(shape).toUInt().toString(36)}"

private fun depthOption(depth: Double): Double {
    val d = clamp(if (depth.isFinite()) depth else DEFAULT_DEPTH, 0.0, MAX_DEPTH)
    return Math.round(d * 2) / 2.0
}

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

    /** One eye: where it rests, its size, its gaze offset, and how far it is open. */
    private class Eye(
        val x: Double, val y: Double, val w: Double, val h: Double,
        val dx: Double, val dy: Double, val open: Double, val look: EyeLook,
    ) {
        private val shift get() = (h - open) * 0.18
        private val arc get() = look.arc >= 1
        private val pill get() = look.arc <= 0 && look.slit <= 0

        /** Half the width of its mark, for the depth it is painted at. */
        val halfWidth get() = w * if (pill) 0.5 else HappyArc.HALF_WIDTH + 0.5 * HappyArc.STROKE

        fun mark(gaze: Boolean): EyeMark {
            val ex = if (gaze) x + dx else x
            val ey = if (gaze) y + dy else y
            if (arc) {
                val hw = w * HappyArc.HALF_WIDTH
                val base = ey + h * HappyArc.BASE
                return EyeMark.Arc(ex - hw, base, ex, ey - h * HappyArc.PEAK, ex + hw, base, w * HappyArc.STROKE)
            }
            if (!pill) return EyeMark.Path(eyePath(look, EyeSpot(ex, ey, w, h, open, shift)))
            return EyeMark.Pill(ex - w / 2, ey + shift - open / 2, w, open, min(w, open) / 2)
        }
    }

    private val volume = buildVolume(definition.eyes, depth / DEFAULT_DEPTH, body)
    private val range = gazeRange(body.rows, definition.eyes.copy(scale = volume.eyeScale))
    private val seed = hashString(definition.id)
    private val prefix = "oba-${hashString("${definition.id}|${definition.path}|${plain(depth)}").toUInt().toString(36)}"
    private val rims by lazy { ribbonRims(body.polys, volume.bounds) }
    private val framePaths: Map<String, FramePath> = buildMap {
        for (part in volume.parts) for (s in part.zSlices + part.xSlices) {
            if (s.d !in this) put(s.d, FramePath("$prefix-$size", s.d, s.evenOdd, s.box))
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
                state = options.state,
                stateAge = options.stateAge,
                motion = options.motion,
            ),
        )
        val pivot = pivotOf(volume.bounds)
        val v = viewBasis(pose.yaw, pose.pitch)
        val body = bodyScale(pose.marks)
        val slices = if (body > 0) sliceLayers(v, pivot, renderedSize) else emptyList()
        val eyes = if (body > 0) eyes(pose) else emptyList()
        val layers = if (body > 0) (slices + eyeLayers(v, pivot, eyes)).sortedWith(compareBy(numericOrder) { it.depth }) else emptyList()
        val shrink = AvatarMatrix.about(pivot.x, pivot.y, AvatarMatrix.scale(body))
        val shapes = layers.map { layer ->
            when (val shape = layer.shape) {
                is FrameShape.Slice -> shape.copy(matrix = if (body < 1) shrink * shape.matrix else shape.matrix)
                is FrameShape.Eye -> shape.copy(matrix = if (body < 1) shrink * shape.matrix else shape.matrix)
                is FrameShape.Ribbon -> shape
            }
        }
        val paths = slices.mapNotNull { it.path }.distinctBy { it.id }.toMutableList()
        val root = rootMatrix(pose, volume.bounds)
        val fixed = root.inverse()
        val time = if (options.time.isFinite()) options.time else 0.0
        val ribbons = if (options.reducedMotion || pose.ribbons <= 0.01) emptyList() else {
            val rim = if (pose.cage >= 0.5) rims.second else rims.first
            val palette = ribbonPalette(options.color, seed)
            ribbonPieces(pose.ribbons, seed, pose.sweep, pose.cage, ribbonsFollow(rim, pose, volume.bounds)).mapIndexed { i, r ->
                val run = palette[r.ribbon]
                val (s0, s1) = r.along
                val stops = listOf(s0, (s0 + s1) / 2, s1).map { runAt(run, it) }
                val long = hypot(r.to.x - r.from.x, r.to.y - r.from.y) > 0.01
                val gradient = if (long) RibbonGradient(r.from.x, r.from.y, r.to.x, r.to.y, stops)
                    else RibbonGradient(r.from.x, r.from.y, r.from.x + 0.01, r.from.y, List(3) { stops[1] })
                FrameShape.Ribbon("ribbon-$i", r.d, r.box, fixed, r.front, stops[1], gradient)
            }
        }
        val marks = markParts(volume.bounds, pose.marks, seed, time, options.reducedMotion).mapIndexed { i, part ->
            // Two parts of one name share a path; given different outlines, the later one gets a path of its own.
            val name = "$prefix-${markId(part.shape)}"
            val taken = paths.firstOrNull { it.id == name }
            val id = if (taken != null && taken.d != part.d) "$name-${hashString(part.d).toUInt().toString(36)}" else name
            if (paths.none { it.id == id }) paths.add(FramePath(id, part.d, false, part.box, part.round))
            val rotation = AvatarMatrix(kotlin.math.cos(part.turn), kotlin.math.sin(part.turn), -kotlin.math.sin(part.turn), kotlin.math.cos(part.turn), 0.0, 0.0)
            val matrix = AvatarMatrix.about(pivot.x, pivot.y, rotation) * (AvatarMatrix.translate(part.x, part.y) * AvatarMatrix.scale(part.scale))
            FrameShape.Slice("mark-$i", id, fixed * matrix)
        }
        return AvatarFrame(
            name = definition.name,
            color = normalizeHex(options.color, DEFAULT_COLOR),
            ink = normalizeHex(options.eyeColor, DEFAULT_EYE_COLOR),
            paths = paths,
            root = root,
            shapes = ribbons.filter { !it.front } + shapes + ribbons.filter { it.front } + marks,
            surface = FrameSurface(v, body, eyes.map { SurfaceEye(it.mark(gaze = false), it.dx, it.dy) }, volume.takeIf { depth > 0 }),
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
            // Parallel slices have the same order along every viewing ray.
            val depth = (if (stack == SliceAxis.Z) v.facing else v.sideFacing) * s.at
            val matrix = if (stack == SliceAxis.Z) v.plane(s.at, pivot) else v.sidePlane(s.at, pivot)
            layers.add(Layer(depth, FrameShape.Slice("${kept.part}${stack.letter}${kept.index}", kept.path.id, matrix), kept.path))
        }
        return layers
    }

    /** Painted on the surface; each eye goes once the surface under it turns away. */
    private fun eyeLayers(v: ViewBasis, pivot: Point, eyes: List<Eye>): List<Layer> = eyes.mapIndexedNotNull { i, eye ->
        val plane = volume.eyePlanes[i]
        if (!v.sees(plane)) return@mapIndexedNotNull null
        // A slice through the middle of a mark must paint before the entire mark: sort at the near
        // edge of its footprint on the active stack.
        val depth = if (v.activeStack == SliceAxis.Z) {
            val z = plane.z + eye.dx * plane.dzdx + eye.dy * plane.dzdy
            v.facing * z + abs(v.facing) * (abs(plane.dzdx) * eye.halfWidth + (abs(plane.dzdy) * eye.h) / 2)
        } else {
            v.sideFacing * (eye.x + eye.dx) + abs(v.sideFacing) * eye.halfWidth
        }
        Layer(depth + 1e-6, FrameShape.Eye("eye-$i", v.eyePlane(plane, pivot), eye.mark(gaze = true)))
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

    private fun eyes(pose: Pose): List<Eye> {
        val s = volume.eyeScale
        val w = EYE_W * s
        val h = EYE_H * s
        val dx = pose.gazeX * (if (pose.gazeX >= 0) range.right else range.left) * GAZE_X * s
        val dy = pose.gazeY * (if (pose.gazeY >= 0) range.down else range.up) * GAZE_Y * s
        return eyeCenters(definition.eyes).mapIndexed { i, c ->
            val squint = if (i == 0) pose.squintL else pose.squintR
            val open = max(h * max(0.09, (1 - pose.blink) * (1 - squint * 0.4)), w * 0.55)
            Eye(c.x, c.y, w, h, dx, dy, open, pose.eyes)
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
