package re.leob.openbotavatar.kt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The only definition contract version this library reads. */
public const val AVATAR_CONTRACT_VERSION: Int = 1

/** Placement of the eye pair, in viewBox units. A [scale] of 1 is an eye about 4 wide and 11 tall. */
public data class AvatarEyes(
    val x: Double,
    val y: Double,
    val spacing: Double,
    val scale: Double,
)

/**
 * Avatar geometry: one filled SVG [path] in a `0 0 100 100` viewBox, nonzero fill rule, and the eyes.
 * Color, volume, gaze and motion belong to the renderer.
 */
public data class AvatarDefinition(
    val id: String,
    val name: String,
    val path: String,
    val eyes: AvatarEyes,
)

/** The definition in contract JSON. */
public fun AvatarDefinition.toJson(): JsonObject = buildJsonObject {
    put("version", AVATAR_CONTRACT_VERSION)
    put("id", id)
    put("name", name)
    putJsonObject("body") { put("path", path) }
    putJsonObject("eyes") {
        put("x", eyes.x)
        put("y", eyes.y)
        put("spacing", eyes.spacing)
        put("scale", eyes.scale)
    }
}

public enum class AvatarState { IDLE, THINKING, WORKING, WAITING, BLOCKED, DONE }

public enum class AvatarMood {
    NEUTRAL,
    HAPPY,
    @Deprecated("Use state = AvatarState.IDLE and mood = AvatarMood.NEUTRAL") IDLE,
    @Deprecated("Use state = AvatarState.THINKING and mood = AvatarMood.NEUTRAL") THINKING,
}

/** The legacy combined state and mood option. Explicit state and mood take precedence. */
public enum class AvatarMotion { IDLE, THINKING, HAPPY }

internal data class AvatarLook(val state: AvatarState, val mood: AvatarMood)

@Suppress("DEPRECATION")
internal fun resolveLook(state: AvatarState?, mood: AvatarMood?, motion: AvatarMotion? = null): AvatarLook {
    val legacy = motion ?: when (mood) {
        AvatarMood.THINKING -> AvatarMotion.THINKING
        AvatarMood.IDLE -> AvatarMotion.IDLE
        else -> null
    }
    return AvatarLook(
        state ?: if (legacy == AvatarMotion.THINKING) AvatarState.THINKING else AvatarState.IDLE,
        when (mood) {
            AvatarMood.NEUTRAL, AvatarMood.HAPPY -> mood
            else -> if (legacy == AvatarMotion.HAPPY) AvatarMood.HAPPY else AvatarMood.NEUTRAL
        },
    )
}

/** A look direction, each axis -1 to 1, x right and y down. */
public data class LookDirection(val x: Double, val y: Double)

/** A look target for one frame. [weight] (0 to 1, default 1) fades it in over the idle pose. */
public data class AvatarGaze(val x: Double, val y: Double, val weight: Double? = null)

/** Extra rotation in radians on top of the resting view. */
public data class AvatarView(val yaw: Double, val pitch: Double)

/** A mood on screen, possibly still easing in over what came before. */
public data class MoodState(
    val mood: AvatarMood? = null,
    val blend: AvatarMoodBlend? = null,
    val state: AvatarState? = null,
    /** Seconds the state had been showing when the next change began. */
    val age: Double? = null,
    val motion: AvatarMotion? = null,
)

/** Easing into a mood from the state shown before it; [t] runs from 0 to 1. */
public data class AvatarMoodBlend(val from: MoodState, val t: Double)

/** What a single frame shows. The same options always produce the same frame. */
public data class FrameOptions(
    /** Body color as `#rgb` or `#rrggbb`; anything else falls back to [DEFAULT_COLOR]. */
    val color: String? = null,
    /** Eye color as `#rgb` or `#rrggbb`; anything else falls back to [DEFAULT_EYE_COLOR]. */
    val eyeColor: String? = null,
    val view: AvatarView? = null,
    /** Animation clock, seconds. */
    val time: Double = 0.0,
    val gaze: AvatarGaze? = null,
    val mood: AvatarMood? = null,
    val moodBlend: AvatarMoodBlend? = null,
    /** Freezes body motion and blinking at a calm pose; gaze still applies. */
    val reducedMotion: Boolean = false,
    val state: AvatarState? = null,
    /** Seconds since the state began. Without it, DONE is at rest. */
    val stateAge: Double? = null,
    /** Legacy combined option; explicit state and mood win. */
    val motion: AvatarMotion? = null,
)

public const val DEFAULT_COLOR: String = "#ff6a1a"
public const val DEFAULT_EYE_COLOR: String = "#151515"

/** Depth option that maps to the natural inferred volume. */
public const val DEFAULT_DEPTH: Double = 6.0
