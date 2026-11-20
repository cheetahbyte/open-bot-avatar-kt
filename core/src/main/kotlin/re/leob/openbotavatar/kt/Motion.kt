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
    val bang: Double = 0.0, val dots: Double = 0.0,
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
}

public data class PoseInput(
    val seed: Int,
    val time: Double,
    val mood: AvatarMood? = null,
    val gaze: AvatarGaze? = null,
    val reducedMotion: Boolean = false,
    val view: AvatarView? = null,
    val blend: AvatarMoodBlend? = null,
    val state: AvatarState? = null,
    val stateAge: Double? = null,
    val motion: AvatarMotion? = null,
)

public const val BASE_YAW: Double = -0.3
public const val BASE_PITCH: Double = 0.2
public const val DONE_TURN_S: Double = 3.87
public const val DONE_CYCLE_S: Double = 6.2
private const val TAU = PI * 2

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

private fun blinkAt(seed: Int, t: Double): Double {
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

private fun wanderAt(seed: Int, t: Double): Pair<Double, Double> {
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

private fun dartAt(seed: Int, t: Double): Pair<Double, Double> {
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
        m(a.ribbons, b.ribbons), cage, sweep, m(a.bang, b.bang), m(a.dots, b.dots),
    )
}

public fun computePose(input: PoseInput): Pose {
    val pose = statePose(input)
    val blend = input.blend
    if (blend == null || !(blend.t < 1)) return pose
    val from = blend.from
    val same = resolveLook(from.state, from.mood, from.motion).state == resolveLook(input.state, input.mood, input.motion).state
    val before = computePose(input.copy(state = from.state, mood = from.mood, motion = from.motion,
        stateAge = if (same) input.stateAge else from.age, blend = from.blend))
    return blendPoses(before, pose, if (blend.t.isFinite()) blend.t else 1.0)
}

private fun workTurn(seed: Int, age: Double): Double? {
    val first = 1.2 + 1.2 * noise(seed, 41)
    if (age < first) return null
    val n = floor((age - first) / 7.5).toInt()
    for (k in intArrayOf(n, n - 1)) {
        if (k < 0) continue
        val start = first + k * 7.5 + if (k != 0) 1.5 * noise(seed, 60 + k) else 0.0
        if (age >= start && age < start + 1.5) return age - start
    }
    return null
}

private fun celebration(t: Double): Double {
    val v = (9 * TAU + 0.5) / 2.4625
    return when {
        t <= 0 -> 0.0
        t < 0.24 -> -0.25 * (1 - cos(PI * t / 0.24))
        t < 0.54 -> -0.5 + v * (t - 0.24).pow(2) / 0.6
        t < 2.54 -> -0.5 + v * (t - 0.39)
        t < 3.79 -> -0.5 + v * 2.15 + 1.25 * v * (1 - (1 - (t - 2.54) / 1.25).pow(4)) / 4
        else -> 9 * TAU
    }
}

private fun statePose(input: PoseInput): Pose {
    val seed = input.seed
    val reduced = input.reducedMotion
    val look = resolveLook(input.state, input.mood, input.motion)
    val t = if (reduced) 0.0 else input.time + noise(seed, 7) * 10
    val live = if (reduced) 0.0 else 1.0
    fun wave(period: Double, phase: Double = 0.0) = if (reduced) 0.0 else sin(TAU * t / period + phase)
    val gaze = input.gaze
    val followed = gaze != null && gaze.x.isFinite() && gaze.y.isFinite()
    val gx = if (followed) clamp(gaze.x, -1.0, 1.0) else 0.0
    val gy = if (followed) clamp(gaze.y, -1.0, 1.0) else 0.0
    val weight = if (followed) gaze.weight?.takeIf { it.isFinite() }?.let { clamp(it, 0.0, 1.0) } ?: 1.0 else 0.0
    val sway = 1 - 0.7 * weight
    var yaw = BASE_YAW; var pitch = BASE_PITCH; var roll = 0.0; var lift = 0.0
    var sx = 1.0; var sy = 1.0; var gazeX = 0.0; var gazeY = 0.0
    var blink = 0.0; var squintL = 0.0; var squintR = 0.0
    val arc = if (look.mood == AvatarMood.HAPPY) 1.0 else 0.0
    var slit = 0.0; var ribbons = 0.0; var cage = 0.0; var sweep = 0.0; var bang = 0.0; var dots = 0.0
    fun rest(pace: Double) {
        yaw += 0.07 * sway * wave(6.1 * pace)
        pitch += 0.04 * sway * wave(4.7 * pace, 1.0)
        roll = 1.6 * wave(5.3 * pace, 2.0)
        lift = if (reduced) 0.0 else -0.45 * (1 - cos(TAU * t / (3.4 * pace)))
        val breath = wave(3.4 * pace, -PI / 2)
        sy = 1 + 0.012 * breath; sx = 1 - 0.008 * breath
        if (!reduced) { blink = blinkAt(seed, t); val wander = wanderAt(seed, t); gazeX = wander.first; gazeY = wander.second }
    }
    if (look.state == AvatarState.IDLE && look.mood == AvatarMood.HAPPY) {
        val period = 0.92
        val p = if (reduced) 0.5 else ((t % period) + period) % period / period
        val air = 4 * p * (1 - p)
        val land = if (reduced) 0.0 else clamp(1 - min(p, 1 - p) / 0.12, 0.0, 1.0)
        lift = if (reduced) 0.0 else -3.2 * air
        sy = 1 - 0.09 * land + 0.03 * air * live; sx = 1 + 0.07 * land - 0.015 * air * live
        roll = 4.5 * wave(period * 2); yaw += 0.1 * wave(period * 2, 0.6)
    } else when (look.state) {
        AvatarState.THINKING -> {
            yaw += 0.12 + 0.05 * sway * wave(7.3); pitch += 0.08
            roll = -5 + 1.4 * wave(4.1); lift = -0.5 * (1 - cos(TAU * t / 5.2)) * live
            gazeX = 0.55 + 0.3 * wave(5.6); gazeY = -0.8
            squintL = 0.22; squintR = 0.32 + 0.1 * wave(3.1)
            if (!reduced) blink = blinkAt(seed, t * 0.7)
            dots = 1.0
        }
        AvatarState.WORKING -> {
            val beat = wave(0.7)
            yaw += 0.08 * sway * wave(2.3, 0.4); pitch -= 0.05; roll = 3 + 0.6 * beat
            lift = -0.3 * (0.5 + 0.5 * beat) * live; sy = 0.99 - 0.008 * beat; sx = 1.005 + 0.005 * beat
            squintL = 0.25; squintR = 0.25
            if (reduced) { gazeX = 0.15; gazeY = 0.3 } else {
                val dart = dartAt(seed, t); gazeX = dart.first; gazeY = dart.second; blink = blinkAt(seed, t * 1.3)
            }
            val age = input.stateAge?.takeIf { it.isFinite() }?.let { max(0.0, it) } ?: max(0.0, input.time)
            val into = if (reduced) null else workTurn(seed, age)
            if (into != null) {
                val e = 6.2 * into; val turn = TAU * (1 - (1 + e) * exp(-e)); val swell = sin(turn / 2)
                ribbons = clamp(into / 0.16, 0.0, 1.0) * clamp((1.5 - into) / 0.5, 0.0, 1.0)
                sweep = turn; yaw += 0.22 * swell; roll += 3 * swell; lift -= 1.2 * swell
                gazeX = clamp(gazeX * (1 - swell) - 0.85 * sin(turn), -1.0, 1.0)
                gazeY = gazeY * (1 - swell) - 0.3 * swell
            }
        }
        AvatarState.WAITING -> {
            val p = if (reduced) 0.0 else ((t % 6.5) + 6.5) % 6.5 / 6.5
            val sigh = sin(TAU * clamp(p / 0.33, 0.0, 1.0))
            yaw += 0.04 * sway * wave(7.4); pitch += 0.02 + 0.03 * sigh; roll = 7 + 1.2 * wave(6.4, 2.0)
            lift = -0.3 * (1 - cos(TAU * t / 6.5)) * live; sy = 1 + 0.025 * sigh; sx = 1 - 0.012 * sigh
            gazeX = 0.5 + 0.15 * wave(8.2); gazeY = 0.1; slit = 1 - arc
            if (!reduced) blink = blinkAt(seed, t * 0.6)
        }
        AvatarState.BLOCKED -> { sy = 1.02; sx = 0.99; bang = 1.0 }
        AvatarState.DONE -> {
            rest(1.25)
            val age = if (!reduced && input.stateAge?.isFinite() == true) max(0.0, input.stateAge) else DONE_TURN_S
            val length = DONE_CYCLE_S * (1 + 0.15 * noise(seed, 31))
            val cycle = floor(age / length); val since = age - cycle * length
            if (since < DONE_TURN_S) {
                val swell = sin(PI * since / DONE_TURN_S); val way = if (cycle % 2 != 0.0) -1 else 1
                val turn = celebration(since) * way
                yaw += turn / 3 - TAU * Math.round(turn / 3 / TAU)
                lift -= 2.2 * swell; sy += 0.04 * swell
                ribbons = clamp(since / 0.1, 0.0, 1.0) * clamp((DONE_TURN_S - since) / 0.5, 0.0, 1.0)
                cage = 1.0; sweep = way * max(0.0, celebration(since))
            }
        }
        else -> rest(1.0)
    }
    if (followed && weight > 0) {
        val share = if (reduced) 1.0 else 0.7; val follow = weight * (1 - arc)
        gazeX += (gx * share - gazeX) * follow; gazeY += (gy * share - gazeY) * follow
        if (!reduced) { yaw += weight * (gx * 0.32 - BASE_YAW); pitch += weight * (-gy * 0.18 - BASE_PITCH / 2) }
    }
    val view = input.view
    if (view != null && view.yaw.isFinite() && view.pitch.isFinite()) { yaw += view.yaw; pitch += view.pitch }
    return Pose(yaw, pitch, roll, lift, sx, sy, gazeX, gazeY, blink, squintL, squintR, EyeLook(arc, slit), ribbons, cage, sweep, bang, dots)
}
