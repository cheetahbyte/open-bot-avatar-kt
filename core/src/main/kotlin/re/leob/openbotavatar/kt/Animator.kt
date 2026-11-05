package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.smoothstep
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Length of a mood change, ms. */
public const val MOOD_MS: Double = 420.0

/** Eased progress of a mood change from elapsed time. */
public fun moodProgress(elapsedMs: Double): Double = smoothstep(elapsedMs / MOOD_MS)

/**
 * What a new mood change eases in from: the mood on screen with any change under way frozen at its
 * progress. Older changes whose share has all but faded, or beyond a few levels, are dropped, so
 * rapid changes never grow the chain.
 */
public fun settleMood(state: MoodState, share: Double = 1.0, depth: Int = 0): MoodState {
    val b = state.blend ?: return state
    val rest = share * (1 - b.t)
    if (rest < 0.02 || depth >= 3) return MoodState(state.mood)
    return MoodState(state.mood, AvatarMoodBlend(settleMood(b.from, rest, depth + 1), b.t))
}

/** An eased look direction. [attention] is 0 at rest, 1 when fully following. */
public data class FollowState(val x: Double, val y: Double, val attention: Double) {
    public companion object {
        public val REST: FollowState = FollowState(0.0, 0.0, 0.0)
    }
}

/** Time constant of the look easing, seconds: quick enough to track a moving pointer. */
private const val LOOK_TAU = 0.12

/** Attention fades more slowly, so taking up or losing a target is an unhurried turn. */
private const val ATTEND_TAU = 0.3

/** Small avatars still get a usable reach, in the same units as the box; dp on Android. */
private const val MIN_REACH = 160.0

/** An avatar's box, in the same units as the pointer passed to [pointerGaze]. */
public data class AvatarBox(val left: Double, val top: Double, val width: Double, val height: Double)

/**
 * Look direction toward a point, from [origin] in the box (fractions of its size; the eyes). Radial
 * and soft: diagonals are no stronger than straight lines, and it saturates gently far away.
 */
public fun pointerGaze(
    pointerX: Double,
    pointerY: Double,
    box: AvatarBox,
    origin: LookDirection = LookDirection(0.5, 0.5),
): LookDirection {
    val reach = maxOf(box.width, box.height, MIN_REACH)
    val dx = (pointerX - (box.left + box.width * origin.x)) / reach
    val dy = (pointerY - (box.top + box.height * origin.y)) / reach
    val m = hypot(dx, dy)
    if (!m.isFinite() || m < 1e-9) return LookDirection(0.0, 0.0)
    val k = tanh(m) / m
    return LookDirection(dx * k, dy * k)
}

/** One frame-rate independent easing step toward [target]; null eases back to rest. Reduced motion snaps. */
public fun followStep(
    state: FollowState,
    target: LookDirection?,
    dt: Double,
    reduced: Boolean,
): Pair<FollowState, Boolean> {
    val goal = if (target != null) FollowState(target.x, target.y, 1.0) else FollowState.REST
    val step = max(0.0, min(dt, 0.1))
    fun ease(tau: Double) = if (reduced) 1.0 else 1 - exp(-step / tau)
    val k = ease(if (target != null) LOOK_TAU else ATTEND_TAU)
    val next = FollowState(
        state.x + (goal.x - state.x) * k,
        state.y + (goal.y - state.y) * k,
        state.attention + (goal.attention - state.attention) * ease(ATTEND_TAU),
    )
    val moving = abs(goal.x - next.x) > 0.002 || abs(goal.y - next.y) > 0.002 || abs(goal.attention - next.attention) > 0.002
    return (if (moving) next else goal) to moving
}

/** The pose's look target for a follow state: none at rest, else attention through a smoothstep as weight. */
public fun followGaze(state: FollowState): AvatarGaze? =
    if (state.attention > 0) AvatarGaze(state.x, state.y, smoothstep(state.attention)) else null

public data class AnimatorStep(
    val gaze: AvatarGaze?,
    val moodBlend: AvatarMoodBlend?,
    /** True while the look or a mood change is still easing. */
    val moving: Boolean,
)

/** The per-avatar state a live renderer keeps between frames: the eased look and the eased mood change. */
public class AvatarAnimator {
    private class Shown(val mood: AvatarMood, var blend: AvatarMoodBlend?, val since: Double)

    private var follow = FollowState.REST
    private var last: Double? = null
    private var shown: Shown? = null

    /**
     * Advance to [now], a wall clock in ms. Easing runs on it even when the animation clock stops.
     * [target] is the look direction, [hold] keeps the current look, [reducedMotion] snaps.
     */
    public fun step(
        now: Double,
        mood: AvatarMood,
        target: LookDirection?,
        hold: Boolean = false,
        reducedMotion: Boolean = false,
    ): AnimatorStep {
        val previous = last
        val dt = if (previous == null) 0.0 else (now - previous) / 1000
        last = now
        var moving = false
        if (!hold) {
            val (state, stillMoving) = followStep(follow, target, dt, reducedMotion)
            follow = state
            moving = stillMoving
        }

        val current = shown
        if (current != null && mood != current.mood) {
            val frozen = current.blend?.let { AvatarMoodBlend(it.from, moodProgress(now - current.since)) }
            val blend = if (reducedMotion) null else AvatarMoodBlend(settleMood(MoodState(current.mood, frozen)), 0.0)
            shown = Shown(mood, blend, now)
        }
        val active = shown ?: Shown(mood, null, now).also { shown = it }
        val t = if (reducedMotion) 1.0 else moodProgress(now - active.since)
        var moodBlend: AvatarMoodBlend? = null
        val blend = active.blend
        if (blend != null && t < 1) moodBlend = AvatarMoodBlend(blend.from, t) else active.blend = null

        return AnimatorStep(followGaze(follow), moodBlend, moving || moodBlend != null)
    }

    /** Back to rest with no mood history. */
    public fun reset() {
        follow = FollowState.REST
        last = null
        shown = null
    }
}
