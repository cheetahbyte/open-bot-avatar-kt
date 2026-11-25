package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.clamp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val TAU = PI * 2

/**
 * A state: what the bot does while it is in it, as a pose for each moment. The six of [AvatarState]
 * are definitions, and a host can pass one of its own wherever a state is taken.
 */
@SubclassOptInRequired(ExperimentalOpenBotAvatarApi::class)
public interface AvatarStateDefinition {
    /**
     * Names the state. Two definitions with one id are the same state: changing between them is not
     * a change of state, so nothing eases and the state's age runs on.
     */
    public val id: String

    /**
     * The age the state has when an avatar mounts in it, seconds (0: it starts afresh). `DONE` mounts
     * past its celebration, as if it had just finished.
     */
    public val startAge: Double get() = 0.0

    /**
     * The pose at one moment, built from the context's `base` or `rest`. A pure function of the
     * context, so a frame can be drawn again exactly. Under `reducedMotion` it returns a pose that
     * holds still. The look target and the view are applied on top.
     */
    @ExperimentalOpenBotAvatarApi
    public fun pose(ctx: StateContext): Pose
}

/** A state of the host's own. */
@ExperimentalOpenBotAvatarApi
public fun AvatarStateDefinition(id: String, startAge: Double = 0.0, pose: (StateContext) -> Pose): AvatarStateDefinition =
    FunctionState(id, startAge, pose)

private class FunctionState(
    override val id: String,
    override val startAge: Double,
    private val poseAt: (StateContext) -> Pose,
) : AvatarStateDefinition {
    override fun pose(ctx: StateContext) = poseAt(ctx)
}

/** The six built-in states. Each is a definition, so it goes wherever a custom one does. */
public enum class AvatarState(override val id: String) : AvatarStateDefinition {
    IDLE("idle"),
    THINKING("thinking"),
    WORKING("working"),
    WAITING("waiting"),
    BLOCKED("blocked"),
    DONE("done"),
    ;

    override val startAge: Double get() = if (this == DONE) DONE_TURN_S else 0.0

    @ExperimentalOpenBotAvatarApi
    override fun pose(ctx: StateContext): Pose = when (this) {
        IDLE -> idle(ctx)
        THINKING -> thinking(ctx)
        WORKING -> working(ctx)
        WAITING -> waiting(ctx)
        BLOCKED -> blocked(ctx)
        DONE -> done(ctx)
    }
}

/** The definition behind one of the six names, to build a variant on. */
public fun stateDefinition(state: AvatarState): AvatarStateDefinition = state

/** What a state works its pose out from. */
@ExperimentalOpenBotAvatarApi
public class StateContext internal constructor(
    /** The avatar's own seed, for noise that differs from one avatar to the next. */
    public val seed: Int,
    /** The animation clock as given, seconds. */
    public val time: Double,
    /** The clock offset per avatar, so several do not move in lockstep; 0 under reduced motion. */
    public val t: Double,
    /** Seconds since the state began, where the host gave it. */
    public val stateAge: Double?,
    public val mood: AvatarMood,
    public val reducedMotion: Boolean,
    /** How much of its idle turning the body keeps: less while it attends to a look target. */
    public val sway: Double,
    /** The pose a state starts from: the resting view, eyes open and shaped by the mood, no ribbons or marks. */
    public val base: Pose,
) {
    /** 1, or 0 under reduced motion: a factor for motion that should stop. */
    public val live: Double get() = if (reducedMotion) 0.0 else 1.0

    /** A sine of this period (seconds) on the clock; 0 under reduced motion. */
    public fun wave(period: Double, phase: Double = 0.0): Double =
        if (reducedMotion) 0.0 else sin((TAU * t) / period + phase)

    /**
     * The calm pose of a bot with nothing to do, for a state that keeps the body: it breathes, sways,
     * blinks and looks around. [pace] stretches its periods (`DONE` rests at 1.25).
     */
    public fun rest(pace: Double = 1.0): Pose {
        val breath = wave(3.4 * pace, -PI / 2)
        val (gazeX, gazeY) = if (reducedMotion) base.gazeX to base.gazeY else wanderAt(seed, t)
        return base.copy(
            yaw = base.yaw + 0.07 * sway * wave(6.1 * pace),
            pitch = base.pitch + 0.04 * sway * wave(4.7 * pace, 1.0),
            roll = 1.6 * wave(5.3 * pace, 2.0),
            lift = if (reducedMotion) 0.0 else -0.45 * (1 - cos((TAU * t) / (3.4 * pace))),
            sy = 1 + 0.012 * breath,
            sx = 1 - 0.008 * breath,
            blink = if (reducedMotion) base.blink else blinkAt(seed, t),
            gazeX = gazeX,
            gazeY = gazeY,
        )
    }

    /** How far into a cycle of [period] seconds the clock is, 0..1. */
    internal fun cycle(period: Double): Double = ((t % period) + period) % period / period

    /** Seconds since the state began, if the host gave a usable number. */
    internal val age: Double? get() = stateAge?.takeIf { it.isFinite() }?.let { max(0.0, it) }
}

private fun idle(ctx: StateContext): Pose {
    if (ctx.mood != AvatarMood.HAPPY) return ctx.rest()
    // With nothing to do, a happy bot hops.
    val period = 0.92
    val p = if (ctx.reducedMotion) 0.5 else ctx.cycle(period)
    val air = 4 * p * (1 - p)
    val land = if (ctx.reducedMotion) 0.0 else clamp(1 - min(p, 1 - p) / 0.12, 0.0, 1.0)
    return ctx.base.copy(
        lift = if (ctx.reducedMotion) 0.0 else -3.2 * air,
        sy = 1 - 0.09 * land + 0.03 * air * ctx.live,
        sx = 1 + 0.07 * land - 0.015 * air * ctx.live,
        roll = 4.5 * ctx.wave(period * 2),
        yaw = ctx.base.yaw + 0.1 * ctx.wave(period * 2, 0.6),
    )
}

/** Looks up and away while it gives way to the three dots. */
private fun thinking(ctx: StateContext): Pose = ctx.base.copy(
    yaw = ctx.base.yaw + (0.12 + 0.05 * ctx.sway * ctx.wave(7.3)),
    pitch = ctx.base.pitch + 0.08,
    roll = -5 + 1.4 * ctx.wave(4.1),
    lift = -0.5 * (1 - cos((TAU * ctx.t) / 5.2)) * ctx.live,
    gazeX = 0.55 + 0.3 * ctx.wave(5.6),
    gazeY = -0.8,
    squintL = 0.22,
    squintR = 0.32 + 0.1 * ctx.wave(3.1),
    blink = if (ctx.reducedMotion) ctx.base.blink else blinkAt(ctx.seed, ctx.t * 0.7),
    marks = listOf(PoseMark(DOTS, 1.0)),
)

/**
 * Bent over the work and busy: a quick steady bob and eyes darting across it, and every so often a
 * turn with a band of ribbons sweeping round it.
 */
private fun working(ctx: StateContext): Pose {
    val beat = ctx.wave(0.7)
    val (gazeX, gazeY) = if (ctx.reducedMotion) 0.15 to 0.3 else dartAt(ctx.seed, ctx.t)
    val pose = ctx.base.copy(
        yaw = ctx.base.yaw + 0.08 * ctx.sway * ctx.wave(2.3, 0.4),
        pitch = ctx.base.pitch - 0.05,
        roll = 3 + 0.6 * beat,
        lift = -0.3 * (0.5 + 0.5 * beat) * ctx.live,
        sy = 0.99 - 0.008 * beat,
        sx = 1.005 + 0.005 * beat,
        squintL = 0.25,
        squintR = 0.25,
        gazeX = gazeX,
        gazeY = gazeY,
        blink = if (ctx.reducedMotion) ctx.base.blink else blinkAt(ctx.seed, ctx.t * 1.3),
    )
    // Counted from when the state began; without an age, from the clock.
    val into = (if (ctx.reducedMotion) null else workTurn(ctx.seed, ctx.age ?: max(0.0, ctx.time))) ?: return pose
    // A full circle that eases in like a spring settling. The band rides it round, out at the start
    // and back in at the end; the body keeps facing the work, leaning into the sweep, and the eyes
    // follow the band across.
    val e = 6.2 * into
    val turn = TAU * (1 - (1 + e) * exp(-e))
    val swell = sin(turn / 2)
    return pose.copy(
        ribbons = clamp(into / 0.16, 0.0, 1.0) * clamp((WORK_TURN_S - into) / 0.5, 0.0, 1.0),
        sweep = turn,
        yaw = pose.yaw + 0.22 * swell,
        roll = pose.roll + 3 * swell,
        lift = pose.lift - 1.2 * swell,
        gazeX = clamp(pose.gazeX * (1 - swell) - 0.85 * sin(turn), -1.0, 1.0),
        gazeY = pose.gazeY * (1 - swell) - 0.3 * swell,
    )
}

/**
 * Leans to one side, eyes flattened to slits, glancing off at whatever it is waiting on. Now and
 * then it sighs. A happy bot keeps its arcs.
 */
private fun waiting(ctx: StateContext): Pose {
    val p = if (ctx.reducedMotion) 0.0 else ctx.cycle(6.5)
    // One breath in and out over the first third of the cycle.
    val sigh = sin(TAU * clamp(p / 0.33, 0.0, 1.0))
    return ctx.base.copy(
        yaw = ctx.base.yaw + 0.04 * ctx.sway * ctx.wave(7.4),
        pitch = ctx.base.pitch + (0.02 + 0.03 * sigh),
        roll = 7 + 1.2 * ctx.wave(6.4, 2.0),
        lift = -0.3 * (1 - cos((TAU * ctx.t) / 6.5)) * ctx.live,
        sy = 1 + 0.025 * sigh,
        sx = 1 - 0.012 * sigh,
        gazeX = 0.5 + 0.15 * ctx.wave(8.2),
        gazeY = 0.1,
        eyes = EyeLook(ctx.base.eyes.arc, 1 - ctx.base.eyes.arc),
        blink = if (ctx.reducedMotion) ctx.base.blink else blinkAt(ctx.seed, ctx.t * 0.6),
    )
}

/** Stops still while it gives way to the exclamation mark. */
private fun blocked(ctx: StateContext): Pose = ctx.base.copy(sy = 1.02, sx = 0.99, marks = listOf(PoseMark(BANG, 1.0)))

/**
 * Celebrates: a few turns with ribbons flying round it, then a rest, content and a little slower
 * than idle, and after a while again, the other way round.
 */
private fun done(ctx: StateContext): Pose {
    val pose = ctx.rest(1.25)
    // Counted from when the state began. Without an age it is at rest.
    val age = (if (ctx.reducedMotion) null else ctx.age) ?: DONE_TURN_S
    val length = DONE_CYCLE_S * (1 + 0.15 * noise(ctx.seed, 31))
    val cycle = floor(age / length)
    val since = age - cycle * length
    if (since >= DONE_TURN_S) return pose
    val swell = sin(PI * since / DONE_TURN_S)
    val way = if (cycle % 2 != 0.0) -1 else 1
    // The body turns a third as often as the ribbons go round, the nearest way round to it.
    val turn = celebration(since) * way / 3
    return pose.copy(
        yaw = pose.yaw + (turn - TAU * Math.round(turn / TAU)),
        lift = pose.lift - 2.2 * swell,
        sy = pose.sy + 0.04 * swell,
        // All the ribbons out at full strength while the turn is quick, drawn back in as it settles.
        ribbons = clamp(since / 0.1, 0.0, 1.0) * clamp((DONE_TURN_S - since) / 0.5, 0.0, 1.0),
        cage = 1.0,
        // The ribbons only set off once the turn goes forward: the wind-up does not carry them.
        sweep = way * max(0.0, celebration(since)),
    )
}

private const val WORK_TURN_S = 1.5

/** How far into its current turn a working bot is, seconds, or null between turns. */
private fun workTurn(seed: Int, age: Double): Double? {
    val first = 1.2 + 1.2 * noise(seed, 41)
    if (age < first) return null
    val n = floor((age - first) / 7.5).toInt()
    // A turn may start late, so the one before may still be running.
    for (k in intArrayOf(n, n - 1)) {
        if (k < 0) continue
        val start = first + k * 7.5 + if (k != 0) 1.5 * noise(seed, 60 + k) else 0.0
        if (age >= start && age < start + WORK_TURN_S) return age - start
    }
    return null
}

/** How far a celebration's ribbons have gone round [t] seconds in, radians. */
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
