package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.clamp
import re.leob.openbotavatar.kt.geometry.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Deterministic pose model: a pure function of its input, with no wall clock and no randomness. */
public enum class EyeShape { PILL, HAPPY }

public data class Pose(
    /** View rotation, radians. */
    val yaw: Double,
    val pitch: Double,
    /** In-plane rotation, degrees. */
    val roll: Double,
    /** Vertical offset, negative is up, viewBox units. */
    val lift: Double,
    /** Squash and stretch about the base of the body. */
    val sx: Double,
    val sy: Double,
    /** Eye look direction, -1 to 1 per axis. */
    val gazeX: Double,
    val gazeY: Double,
    /** 0 is open, 1 is fully closed. */
    val blink: Double,
    /** Extra lid narrowing per eye, 0 to 1. */
    val squintL: Double,
    val squintR: Double,
    val eyeShape: EyeShape,
)

public data class PoseInput(
    /** Per-avatar seed; [hashString] of the definition's id. */
    val seed: Int,
    val time: Double,
    val mood: AvatarMood,
    val gaze: AvatarGaze? = null,
    val reducedMotion: Boolean = false,
    val view: AvatarView? = null,
    val blend: AvatarMoodBlend? = null,
)

/** Resting view: turned a little and seen a little from above, so the volume reads without dominating. */
public const val BASE_YAW: Double = -0.3
public const val BASE_PITCH: Double = 0.2

/** The whole character looks: the body turns by up to these angles, the eyes use this share of their range. */
private const val LOOK_YAW = 0.32
private const val LOOK_PITCH = 0.18
private const val LOOK_EYES = 0.7

private const val TAU = PI * 2

public fun hashString(s: String): Int {
    var h = 2166136261u.toInt()
    for (c in s) h = (h xor c.code) * 16777619
    return h
}

/** Stateless hash noise in [0, 1). */
private fun noise(seed: Int, n: Int): Double {
    var h = seed xor (n * 0x9e3779b1u.toInt())
    h = (h xor (h ushr 16)) * 0x85ebca6bu.toInt()
    h = (h xor (h ushr 13)) * 0xc2b2ae35u.toInt()
    h = h xor (h ushr 16)
    return h.toUInt().toDouble() / 4294967296.0
}

private fun blinkAt(seed: Int, t: Double): Double {
    val slotLength = 4.2
    val duration = 0.19
    val slot = floor(t / slotLength).toInt()
    var closure = 0.0
    for (s in intArrayOf(slot - 1, slot)) {
        val start = s * slotLength + 0.5 + noise(seed, s * 2) * 2.9
        val starts = if (noise(seed, s * 2 + 1) > 0.8) doubleArrayOf(start, start + 0.34) else doubleArrayOf(start)
        for (b in starts) {
            val u = (t - b) / duration
            if (u > 0 && u < 1) closure = max(closure, sin(PI * u))
        }
    }
    return closure
}

private fun wanderAt(seed: Int, t: Double): Pair<Double, Double> {
    val slotLength = 1.9
    val s = floor(t / slotLength).toInt()
    fun target(k: Int): Pair<Double, Double> {
        if (noise(seed, 1000 + k * 3) < 0.4) return 0.0 to 0.0
        return (noise(seed, 1001 + k * 3) * 2 - 1) * 0.6 to (noise(seed, 1002 + k * 3) * 2 - 1) * 0.35
    }
    val a = target(s - 1)
    val b = target(s)
    val e = smoothstep((t - s * slotLength) / 0.16)
    return a.first + (b.first - a.first) * e to a.second + (b.second - a.second) * e
}

/**
 * Pose part way from [a] to [b], both taken at the same moment so either side's motion keeps
 * running. A change of eye shape swaps halfway, inside a blink.
 */
public fun blendPoses(a: Pose, b: Pose, t: Double): Pose {
    val k = clamp(t, 0.0, 1.0)
    fun mix(x: Double, y: Double) = x + (y - x) * k
    val pose = Pose(
        yaw = mix(a.yaw, b.yaw),
        pitch = mix(a.pitch, b.pitch),
        roll = mix(a.roll, b.roll),
        lift = mix(a.lift, b.lift),
        sx = mix(a.sx, b.sx),
        sy = mix(a.sy, b.sy),
        gazeX = mix(a.gazeX, b.gazeX),
        gazeY = mix(a.gazeY, b.gazeY),
        blink = mix(a.blink, b.blink),
        squintL = mix(a.squintL, b.squintL),
        squintR = mix(a.squintR, b.squintR),
        eyeShape = b.eyeShape,
    )
    if (a.eyeShape == b.eyeShape) return pose
    return pose.copy(
        eyeShape = if (k < 0.5) a.eyeShape else b.eyeShape,
        blink = max(pose.blink, 1 - abs(2 * k - 1)),
    )
}

public fun computePose(input: PoseInput): Pose {
    val pose = moodPose(input)
    val blend = input.blend
    if (blend == null || !(blend.t < 1)) return pose
    val from = computePose(input.copy(mood = blend.from.mood, blend = blend.from.blend))
    return blendPoses(from, pose, blend.t)
}

private fun moodPose(input: PoseInput): Pose {
    val seed = input.seed
    val reduced = input.reducedMotion
    val gaze = input.gaze
    val t = if (reduced) 0.0 else input.time + noise(seed, 7) * 10
    fun wave(period: Double, phase: Double = 0.0) = if (reduced) 0.0 else sin((TAU * t) / period + phase)
    val followed = gaze != null && gaze.x.isFinite() && gaze.y.isFinite()
    val givenX = if (followed) clamp(gaze.x, -1.0, 1.0) else 0.0
    val givenY = if (followed) clamp(gaze.y, -1.0, 1.0) else 0.0
    val weight = if (followed) gaze.weight?.takeIf { it.isFinite() }?.let { clamp(it, 0.0, 1.0) } ?: 1.0 else 0.0
    val sway = 1 - 0.7 * weight

    var yaw = BASE_YAW
    var pitch = BASE_PITCH
    var roll = 0.0
    var lift = 0.0
    var sx = 1.0
    var sy = 1.0
    var gazeX = 0.0
    var gazeY = 0.0
    var blink = 0.0
    var squintL = 0.0
    var squintR = 0.0
    var eyeShape = EyeShape.PILL

    when (input.mood) {
        AvatarMood.HAPPY -> {
            val period = 0.92
            if (!reduced) {
                val p = ((t % period) + period) % period / period
                val air = 4 * p * (1 - p)
                val land = clamp(1 - minOf(p, 1 - p) / 0.12, 0.0, 1.0)
                lift = -3.2 * air
                sy = 1 - 0.09 * land + 0.03 * air
                sx = 1 + 0.07 * land - 0.015 * air
            }
            roll = 4.5 * wave(period * 2)
            yaw += 0.1 * wave(period * 2, 0.6)
            eyeShape = EyeShape.HAPPY
        }
        AvatarMood.THINKING -> {
            yaw += 0.12 + 0.05 * sway * wave(7.3)
            pitch += 0.08
            roll = -5 + 1.4 * wave(4.1)
            lift = -0.5 * (1 - cos((TAU * t) / 5.2))
            gazeX = 0.55 + 0.3 * wave(5.6)
            gazeY = -0.8
            squintL = 0.22
            squintR = 0.32 + 0.1 * wave(3.1)
            if (!reduced) blink = blinkAt(seed, t * 0.7)
        }
        AvatarMood.IDLE -> {
            yaw += 0.07 * sway * wave(6.1)
            pitch += 0.04 * sway * wave(4.7, 1.0)
            roll = 1.6 * wave(5.3, 2.0)
            lift = -0.45 * (1 - cos((TAU * t) / 3.4))
            val breath = wave(3.4, -PI / 2)
            sy = 1 + 0.012 * breath
            sx = 1 - 0.008 * breath
            if (!reduced) {
                blink = blinkAt(seed, t)
                val wander = wanderAt(seed, t)
                gazeX = wander.first
                gazeY = wander.second
            }
        }
    }

    if (followed && weight > 0) {
        fun mix(a: Double, b: Double) = a + (b - a) * weight
        val share = if (reduced) 1.0 else LOOK_EYES
        if (eyeShape == EyeShape.PILL) {
            gazeX = mix(gazeX, givenX * share)
            gazeY = mix(gazeY, givenY * share)
        }
        if (!reduced) {
            yaw += weight * (givenX * LOOK_YAW - BASE_YAW)
            pitch += weight * (-givenY * LOOK_PITCH - BASE_PITCH / 2)
        }
    }
    val view = input.view
    if (view != null && view.yaw.isFinite() && view.pitch.isFinite()) {
        yaw += view.yaw
        pitch += view.pitch
    }
    return Pose(yaw, pitch, roll, lift, sx, sy, gazeX, gazeY, blink, squintL, squintR, eyeShape)
}
