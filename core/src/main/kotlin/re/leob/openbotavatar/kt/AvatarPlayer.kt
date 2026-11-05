package re.leob.openbotavatar.kt

/** Avatars narrower than this redraw at SMALL_FPS: the difference doesn't show, and lists often hold many. */
private const val SMALL_SIZE = 128.0
private const val SMALL_FPS = 30.0

/** Slack so a 60 Hz display reliably draws every other frame rather than drifting between 1 and 3. */
private const val FRAME_SLACK = 0.004

public data class PlayerFrame(
    val frame: AvatarFrame,
    /** Seconds until the next frame is worth drawing; 0 for the next vsync, null once settled. */
    val nextFrameIn: Double?,
)

/**
 * Drives one live avatar, on any platform: feed it the clock and current intent once per frame.
 * Keep one per avatar on screen, across definition changes, so moods and gaze ease through them.
 */
public class AvatarPlayer {
    private val animator = AvatarAnimator()

    /**
     * [nowMs] is a wall clock and [time] the shared animation clock in seconds. [size] is the
     * rendered width in density-independent units (dp on Android, CSS pixels upstream).
     */
    public fun next(
        avatar: PreparedAvatar,
        nowMs: Double,
        time: Double,
        mood: AvatarMood = AvatarMood.IDLE,
        target: LookDirection? = null,
        holdGaze: Boolean = false,
        view: AvatarView? = null,
        reducedMotion: Boolean = false,
        size: Double,
    ): PlayerFrame {
        val step = animator.step(nowMs, mood, target, holdGaze, reducedMotion)
        val frame = avatar.frame(
            FrameOptions(
                view = view,
                time = time,
                gaze = step.gaze,
                mood = mood,
                moodBlend = step.moodBlend,
                reducedMotion = reducedMotion,
            ),
            renderedSize = size,
        )
        val wait = when {
            reducedMotion -> null
            size > 0 && size < SMALL_SIZE -> 1 / SMALL_FPS - FRAME_SLACK
            else -> 0.0
        }
        return PlayerFrame(frame, wait)
    }
}
