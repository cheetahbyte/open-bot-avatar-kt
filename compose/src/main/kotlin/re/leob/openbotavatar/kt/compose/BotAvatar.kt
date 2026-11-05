package re.leob.openbotavatar.kt.compose

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import re.leob.openbotavatar.kt.AvatarBox
import re.leob.openbotavatar.kt.AvatarDefinition
import re.leob.openbotavatar.kt.AvatarFrame
import re.leob.openbotavatar.kt.AvatarGaze
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.AvatarPlayer
import re.leob.openbotavatar.kt.AvatarView
import re.leob.openbotavatar.kt.DEFAULT_DEPTH
import re.leob.openbotavatar.kt.FrameOptions
import re.leob.openbotavatar.kt.InvalidAvatarException
import re.leob.openbotavatar.kt.LookDirection
import re.leob.openbotavatar.kt.PreparedAvatar
import re.leob.openbotavatar.kt.pointerGaze
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "open-bot-avatar"

public object BotAvatarDefaults {
    public val Color: Color = Color(0xFFFF6A1A)
    public val EyeColor: Color = Color(0xFF151515)
}

/** What the frame loop reads; any change wakes a settled avatar. */
@Immutable
private data class LiveInput(
    val mood: AvatarMood,
    val target: LookDirection?,
    val holdGaze: Boolean,
    val view: AvatarView?,
    val reducedMotion: Boolean,
    val size: Double,
    val visible: Boolean,
)

/** Start of the animation clock every avatar shares, so one scrolled back in resumes on its phase. */
private var clockOrigin = 0L

/**
 * A live avatar in the largest centered square of its layout bounds. It paints past those bounds
 * while it turns and hops, so don't clip it tightly.
 *
 * The first build runs in the background and the avatar is empty until it finishes. An invalid
 * [definition] logs an error and draws nothing; run imported data through `validateAvatar` first.
 *
 * @param color body color.
 * @param eyeColor eye color.
 * @param mood changes ease from the current pose.
 * @param depth volume from 0 (flat) to 14; 6 is natural.
 * @param followPointer look and turn toward touches and the mouse; see [AvatarPointerArea].
 * @param lookAt fixed look direction; wins over the pointer.
 * @param holdGaze keep the current look, for example while the view is being dragged.
 * @param view extra rotation on top of the resting view.
 * @param reducedMotion keep the body still and stop blinking.
 * @param still draw one calm frame and never animate, for thumbnails and lists.
 * @param label accessible name, by default the definition's name; `""` marks it decorative.
 */
@Composable
public fun BotAvatar(
    definition: AvatarDefinition,
    modifier: Modifier = Modifier,
    color: Color = BotAvatarDefaults.Color,
    eyeColor: Color = BotAvatarDefaults.EyeColor,
    mood: AvatarMood = AvatarMood.IDLE,
    depth: Double = DEFAULT_DEPTH,
    followPointer: Boolean = false,
    lookAt: LookDirection? = null,
    holdGaze: Boolean = false,
    view: AvatarView? = null,
    reducedMotion: AvatarReducedMotion = AvatarReducedMotion.System,
    still: Boolean = false,
    label: String? = null,
) {
    val prepared by rememberPreparedAvatar(definition, depth)
    val painter = remember { AvatarPainter() }
    val player = remember { AvatarPlayer() }
    val frame = remember { mutableStateOf<AvatarFrame?>(null) }

    var rect by remember { mutableStateOf(Rect.Zero) }
    var visible by remember { mutableStateOf(true) }
    var pixels by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density

    val areaPointer = LocalAvatarPointer.current
    val ownPointer = remember { AvatarPointerState() }
    val tracksOwnPointer = followPointer && !still && areaPointer == null
    val pointer = areaPointer ?: ownPointer

    val systemReduced by rememberSystemReducedMotion()
    val reduced = when (reducedMotion) {
        AvatarReducedMotion.System -> systemReduced
        AvatarReducedMotion.On -> true
        AvatarReducedMotion.Off -> false
    }

    val input = rememberUpdatedState {
        val position = pointer.position
        val box = rect
        val target = lookAt ?: if (followPointer && !holdGaze && position != null && box.width > 0) {
            val eyes = definition.eyes
            pointerGaze(
                pointerX = position.x.toDouble() / density,
                pointerY = position.y.toDouble() / density,
                box = AvatarBox(
                    left = box.left.toDouble() / density,
                    top = box.top.toDouble() / density,
                    width = box.width.toDouble() / density,
                    height = box.height.toDouble() / density,
                ),
                origin = LookDirection(eyes.x.coerceIn(0.0, 100.0) / 100, eyes.y.coerceIn(0.0, 100.0) / 100),
            )
        } else {
            null
        }
        LiveInput(
            mood = mood,
            target = target,
            holdGaze = holdGaze,
            view = view,
            reducedMotion = reduced,
            size = min(pixels.width, pixels.height) / density.toDouble(),
            visible = visible,
        )
    }

    val stillFrame = if (still) {
        remember(prepared, mood, view, lookAt) {
            val gaze = lookAt?.let { AvatarGaze(it.x, it.y) }
            prepared?.frame(FrameOptions(view = view, gaze = gaze, mood = mood, reducedMotion = true))
        }
    } else {
        LaunchedEffect(prepared) {
            val avatar = prepared
            if (avatar == null) frame.value = null else runFrameLoop(avatar, player, input, frame)
        }
        null
    }

    val name = label ?: definition.name
    val semantics = if (name.isEmpty()) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics {
            contentDescription = name
            role = Role.Image
        }
    }
    Canvas(
        modifier
            .then(semantics)
            .onGloballyPositioned {
                pixels = it.size
                visible = !it.boundsInWindow().isEmpty
                if (followPointer) {
                    val at = it.positionInWindow()
                    rect = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                }
            }
            .then(if (tracksOwnPointer) Modifier.trackAvatarPointer(ownPointer) { rect.topLeft } else Modifier),
    ) {
        // Reading the frame only here keeps animation out of composition and layout.
        (stillFrame ?: frame.value)?.let { drawAvatarFrame(painter, it, color, eyeColor) }
    }
}

/** Build off the main thread; a recent build is ready at once, so scrolled-back avatars never flash. */
@Composable
private fun rememberPreparedAvatar(definition: AvatarDefinition, depth: Double): State<PreparedAvatar?> =
    produceState(PreparedAvatar.cached(definition, depth), definition, depth) {
        value = try {
            withContext(Dispatchers.Default) { PreparedAvatar.of(definition, depth) }
        } catch (error: InvalidAvatarException) {
            Log.e(TAG, "render failed", error)
            null
        }
    }

/**
 * Draws while the avatar asks for frames, and sleeps once it is settled, out of view or unmeasured,
 * until an input changes. The system frame clock stops by itself while the app is in the background.
 */
private suspend fun runFrameLoop(
    avatar: PreparedAvatar,
    player: AvatarPlayer,
    input: State<() -> LiveInput>,
    frame: MutableState<AvatarFrame?>,
) {
    var dueAt = 0.0
    while (true) {
        val nanos = withFrameNanos { it }
        if (clockOrigin == 0L) clockOrigin = nanos
        val time = (nanos - clockOrigin) / 1e9
        if (time < dueAt) continue
        val current = input.value()
        if (current.size <= 0.0) {
            snapshotFlow { input.value() }.first { it.size > 0.0 }
            continue
        }
        val next = player.next(
            avatar = avatar,
            nowMs = nanos / 1e6,
            time = time,
            mood = current.mood,
            target = current.target,
            holdGaze = current.holdGaze,
            view = current.view,
            reducedMotion = current.reducedMotion,
            size = current.size,
        )
        frame.value = next.frame
        val wait = next.nextFrameIn
        if (wait == null || !current.visible) {
            snapshotFlow { input.value() }.first { it != current }
            dueAt = 0.0
        } else {
            dueAt = time + wait
        }
    }
}
