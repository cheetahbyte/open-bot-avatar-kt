package re.leob.openbotavatar.kt.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** Where the pointer is, in window pixels; null when there is none. */
internal class AvatarPointerState {
    var position: Offset? by mutableStateOf(null)
}

internal val LocalAvatarPointer = staticCompositionLocalOf<AvatarPointerState?> { null }

/**
 * Tracks the pointer over [content] for every [BotAvatar] inside with `followPointer`. Wrap the
 * screen in it for the avatars to look at touches anywhere; without it, each sees only its own box.
 * Events are observed, never consumed, so scrolling and clicks work as before.
 */
@Composable
public fun AvatarPointerArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pointer = remember { AvatarPointerState() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .trackAvatarPointer(pointer) { origin },
    ) {
        CompositionLocalProvider(LocalAvatarPointer provides pointer, content)
    }
}

/** Follows mouse and touch; a lifted finger or pen has no position any more. [origin] may read state. */
internal fun Modifier.trackAvatarPointer(pointer: AvatarPointerState, origin: () -> Offset): Modifier =
    pointerInput(pointer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                pointer.position = when {
                    event.type == PointerEventType.Exit -> null
                    event.type == PointerEventType.Release && change.type != PointerType.Mouse -> null
                    else -> origin() + change.position
                }
            }
        }
    }
