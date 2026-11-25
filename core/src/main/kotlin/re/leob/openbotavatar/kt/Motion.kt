package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.clamp
import re.leob.openbotavatar.kt.geometry.smoothstep
import kotlin.math.*

/** Numeric eye shapes, blended point by point by the renderer. */
public data class EyeLook(val arc: Double = 0.0, val slit: Double = 0.0)

@Deprecated("Use Pose.eyes for numeric eye morphs")
public enum class EyeShape { PILL, HAPPY }

/** A deterministic pose, with no clock or randomness owned by the model. */
public data class Pose(
    val yaw: Double, val pitch: Double, val roll: Double, val lift: Double,
    val sx: Double, val sy: Double, val gazeX: Double, val gazeY: Double,
    val blink: Double, val squintL: Double, val squintR: Double,
    val eyes: EyeLook,
    val ribbons: Double = 0.0, val cage: Double = 0.0, val sweep: Double = 0.0,
    /**
     * The marks the body gives way to, each with how far it has, 0 to 1: a state shows one, and a
     * pose on the way from one state to another may show two. In the order of their ids.
     */
    val marks: List<PoseMark> = emptyList(),
) {
    @Suppress("DEPRECATION")
    @Deprecated("Use the numeric EyeLook constructor")
    public constructor(
        yaw: Double, pitch: Double, roll: Double, lift: Double,
        sx: Double, sy: Double, gazeX: Double, gazeY: Double,
        blink: Double, squintL: Double, squintR: Double, eyeShape: EyeShape,
    ) : this(yaw, pitch, roll, lift, sx, sy, gazeX, gazeY, blink, squintL, squintR,
        EyeLook(arc = if (eyeShape == EyeShape.HAPPY) 1.0 else 0.0))

    @Suppress("DEPRECATION")
    @Deprecated("Use eyes.arc and eyes.slit")
    public val eyeShape: EyeShape get() = if (eyes.arc >= 1) EyeShape.HAPPY else EyeShape.PILL

    @Deprecated("Use marks")
    public val bang: Double get() = weightOf(BANG)

    @Deprecated("Use marks")
    public val dots: Double get() = weightOf(DOTS)

    private fun weightOf(mark: AvatarMark) = marks.firstOrNull { it.mark.id == mark.id }?.weight ?: 0.0
}

public data class PoseInput(
    val seed: Int,
    val time: Double,
    val mood: AvatarMood? = null,
    val gaze: AvatarGaze? = null,
    val reducedMotion: Boolean = false,
    val view: AvatarView? = null,
    val blend: AvatarMoodBlend? = null,
    val state: AvatarStateDefinition? = null,
    val stateAge: Double? = null,
    val motion: AvatarMotion? = null,
)

public const val BASE_YAW: Double = -0.3
public const val BASE_PITCH: Double = 0.2
public const val DONE_TURN_S: Double = 3.87
public const val DONE_CYCLE_S: Double = 6.2

public fun hashString(s: String): Int {
    var h = 2166136261u.toInt()
    for (c in s) h = (h xor c.code) * 16777619
    return h
}

internal fun noise(seed: Int, n: Int): Double {
    var h = seed xor (n * 0x9e3779b1u.toInt())
    h = (h xor (h ushr 16)) * 0x85ebca6bu.toInt()
    h = (h xor (h ushr 13)) * 0xc2b2ae35u.toInt()
    h = h xor (h ushr 16)
    return h.toUInt().toDouble() / 4294967296.0
}

internal fun blinkAt(seed: Int, t: Double): Double {
    val slot = floor(t / 4.2).toInt()
    var closure = 0.0
    for (s in intArrayOf(slot - 1, slot)) {
        val start = s * 4.2 + 0.5 + noise(seed, s * 2) * 2.9
        val starts = if (noise(seed, s * 2 + 1) > 0.8) doubleArrayOf(start, start + 0.34) else doubleArrayOf(start)
        for (b in starts) {
            val u = (t - b) / 0.19
            if (u > 0 && u < 1) closure = max(closure, sin(PI * u))
        }
    }
    return closure
}

internal fun wanderAt(seed: Int, t: Double): Pair<Double, Double> {
    val s = floor(t / 1.9).toInt()
    fun target(k: Int): Pair<Double, Double> {
        if (noise(seed, 1000 + k * 3) < 0.4) return 0.0 to 0.0
        return (noise(seed, 1001 + k * 3) * 2 - 1) * 0.6 to (noise(seed, 1002 + k * 3) * 2 - 1) * 0.35
    }
    val a = target(s - 1)
    val b = target(s)
    val e = smoothstep((t - s * 1.9) / 0.16)
    return a.first + (b.first - a.first) * e to a.second + (b.second - a.second) * e
}

internal fun dartAt(seed: Int, t: Double): Pair<Double, Double> {
    val s = floor(t / 0.62).toInt()
    fun target(k: Int) = (noise(seed, 2001 + k * 3) * 2 - 1) * 0.8 to 0.1 + noise(seed, 2002 + k * 3) * 0.5
    val a = target(s - 1)
    val b = target(s)
    val e = smoothstep((t - s * 0.62) / 0.12)
    return a.first + (b.first - a.first) * e to a.second + (b.second - a.second) * e
}

public fun blendPoses(a: Pose, b: Pose, t: Double): Pose {
    val k = clamp(t, 0.0, 1.0)
    fun m(x: Double, y: Double) = x + (y - x) * k
    val sweep = if (a.ribbons > 0 && b.ribbons > 0) m(a.sweep, b.sweep) else if (a.ribbons > 0) a.sweep else b.sweep
    val cage = if (a.ribbons > 0 && b.ribbons > 0) m(a.cage, b.cage) else if (a.ribbons > 0) a.cage else b.cage
    return Pose(
        m(a.yaw, b.yaw), m(a.pitch, b.pitch), m(a.roll, b.roll), m(a.lift, b.lift),
        m(a.sx, b.sx), m(a.sy, b.sy), m(a.gazeX, b.gazeX), m(a.gazeY, b.gazeY),
        m(a.blink, b.blink), m(a.squintL, b.squintL), m(a.squintR, b.squintR),
        EyeLook(m(a.eyes.arc, b.eyes.arc), m(a.eyes.slit, b.eyes.slit)),
        m(a.ribbons, b.ribbons), cage, sweep, blendMarks(a.marks, b.marks, ::m),
    )
}

public fun computePose(input: PoseInput): Pose {
    val pose = statePose(input)
    val blend = input.blend
    if (blend == null || !(blend.t < 1)) return pose
    val from = blend.from
    // A change of mood alone keeps the state's clock running on both sides, so a turn under way stays
    // one turn; a state that was left is held at the age it had.
    val same = resolveLook(from.state, from.mood, from.motion).state.id == resolveLook(input.state, input.mood, input.motion).state.id
    val before = computePose(input.copy(state = from.state, mood = from.mood, motion = from.motion,
        stateAge = if (same) input.stateAge else from.age, blend = from.blend))
    return blendPoses(before, pose, if (blend.t.isFinite()) blend.t else 1.0)
}

/**
 * What a state's pose returned, held to what can be drawn. A definition is a host's code: a number
 * that is not finite keeps the base's, so a slip in one field cannot empty the frame.
 */
private fun laid(base: Pose, given: Pose) = Pose(
    given.yaw.finiteOr(base.yaw), given.pitch.finiteOr(base.pitch), given.roll.finiteOr(base.roll), given.lift.finiteOr(base.lift),
    given.sx.finiteOr(base.sx), given.sy.finiteOr(base.sy), given.gazeX.finiteOr(base.gazeX), given.gazeY.finiteOr(base.gazeY),
    given.blink.finiteOr(base.blink), given.squintL.finiteOr(base.squintL), given.squintR.finiteOr(base.squintR),
    EyeLook(given.eyes.arc.finiteOr(base.eyes.arc), given.eyes.slit.finiteOr(base.eyes.slit)),
    given.ribbons.finiteOr(base.ribbons), given.cage.finiteOr(base.cage), given.sweep.finiteOr(base.sweep), poseMarks(given.marks),
)

private fun statePose(input: PoseInput): Pose {
    val seed = input.seed
    val reduced = input.reducedMotion
    val (state, mood) = resolveLook(input.state, input.mood, input.motion)
    val gaze = input.gaze?.takeIf { it.x.isFinite() && it.y.isFinite() }
    val weight = if (gaze == null) 0.0 else clamp((gaze.weight ?: 1.0).finiteOr(1.0), 0.0, 1.0)
    val base = Pose(
        yaw = BASE_YAW, pitch = BASE_PITCH, roll = 0.0, lift = 0.0, sx = 1.0, sy = 1.0,
        gazeX = 0.0, gazeY = 0.0, blink = 0.0, squintL = 0.0, squintR = 0.0,
        eyes = EyeLook(arc = if (mood == AvatarMood.HAPPY) 1.0 else 0.0),
    )
    val ctx = StateContext(
        seed = seed,
        time = input.time,
        // Per-avatar phase offset so several avatars on a screen do not move in lockstep.
        t = if (reduced) 0.0 else input.time + noise(seed, 7) * 10,
        stateAge = input.stateAge,
        mood = mood,
        reducedMotion = reduced,
        // While it attends to a target the body holds its aim: idle sway of the turn settles to a trace.
        sway = 1 - 0.7 * weight,
        base = base,
    )
    val pose = laid(base, state.pose(ctx))

    var yaw = pose.yaw
    var pitch = pose.pitch
    var gazeX = pose.gazeX
    var gazeY = pose.gazeY
    if (gaze != null && weight > 0) {
        val gx = clamp(gaze.x, -1.0, 1.0)
        val gy = clamp(gaze.y, -1.0, 1.0)
        val share = if (reduced) 1.0 else 0.7
        val follow = weight * (1 - pose.eyes.arc)
        gazeX += (gx * share - gazeX) * follow
        gazeY += (gy * share - gazeY) * follow
        if (!reduced) {
            yaw += weight * (gx * 0.32 - BASE_YAW)
            pitch += weight * (-gy * 0.18 - BASE_PITCH / 2)
        }
    }
    val view = input.view
    if (view != null && view.yaw.isFinite() && view.pitch.isFinite()) {
        yaw += view.yaw
        pitch += view.pitch
    }
    return pose.copy(yaw = yaw, pitch = pitch, gazeX = gazeX, gazeY = gazeY)
}
