package re.leob.openbotavatar.kt.compose

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import re.leob.openbotavatar.kt.AvatarBox
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.AvatarDefinition
import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.AvatarFrame
import re.leob.openbotavatar.kt.AvatarGaze
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.AvatarPlayer
import re.leob.openbotavatar.kt.AvatarView
import re.leob.openbotavatar.kt.DEFAULT_COLOR
import re.leob.openbotavatar.kt.DEFAULT_DEPTH
import re.leob.openbotavatar.kt.DEFAULT_EYE_COLOR
import re.leob.openbotavatar.kt.FrameOptions
import re.leob.openbotavatar.kt.InvalidAvatarException
import re.leob.openbotavatar.kt.LookDirection
import re.leob.openbotavatar.kt.PreparedAvatar
import re.leob.openbotavatar.kt.pointerGaze

private const val TAG = "open-bot-avatar"

private fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

public object BotAvatarDefaults {
    public val Color: Color = hexColor(DEFAULT_COLOR)
    public val EyeColor: Color = hexColor(DEFAULT_EYE_COLOR)
}

/** [AvatarCatalog.color] as a Compose color. */
public fun AvatarCatalog.displayColor(id: String): Color = hexColor(color(id))

private data class Placement(val rect: Rect, val visible: Boolean)

/** Avatar pixels in dp, the units of [pointerGaze]; the look starts at the eyes. */
internal fun lookFrom(position: Offset, bounds: Rect, eyes: AvatarEyes, density: Float): LookDirection {
    val rect = bounds.centeredSquare()
    return pointerGaze(
        pointerX = position.x.toDouble() / density,
        pointerY = position.y.toDouble() / density,
        box = AvatarBox(
            left = rect.left.toDouble() / density,
            top = rect.top.toDouble() / density,
            width = rect.width.toDouble() / density,
            height = rect.height.toDouble() / density,
        ),
        origin = LookDirection(eyes.x.coerceIn(0.0, 100.0) / 100, eyes.y.coerceIn(0.0, 100.0) / 100),
    )
}

/**
 * A live avatar in the largest centered square of its layout bounds. It paints past those bounds
 * while it turns and hops, so don't clip it tightly.
 *
 * The first build runs in the background and the avatar is empty until it finishes. An invalid
 * [definition] logs an error and draws nothing; run imported data through `validateAvatar` first.
 *
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

    var placement by remember { mutableStateOf(Placement(Rect.Zero, visible = true)) }
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
        val rect = placement.rect
        val followed = if (followPointer && !holdGaze && position != null && rect.width > 0) {
            lookFrom(position, rect, definition.eyes, density)
        } else {
            null
        }
        LiveInput(
            mood = mood,
            target = lookAt ?: followed,
            holdGaze = holdGaze,
            view = view,
            reducedMotion = reduced,
            size = rect.minDimension / density.toDouble(),
            visible = placement.visible,
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
                placement = Placement(Rect(it.positionInWindow(), it.size.toSize()), !it.boundsInWindow().isEmpty)
            }
            .then(if (tracksOwnPointer) Modifier.trackAvatarPointer(ownPointer) { placement.rect.topLeft } else Modifier),
    ) {
        // Reading the frame only here keeps animation out of composition and layout.
        (stillFrame ?: frame.value)?.let { painter.paint(this, it, color, eyeColor) }
    }
}

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
