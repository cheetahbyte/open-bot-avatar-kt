package re.leob.openbotavatar.kt.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import re.leob.openbotavatar.kt.AvatarFrame
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.AvatarPlayer
import re.leob.openbotavatar.kt.AvatarView
import re.leob.openbotavatar.kt.LookDirection
import re.leob.openbotavatar.kt.PreparedAvatar

internal data class LiveInput(
    val mood: AvatarMood,
    val target: LookDirection?,
    val holdGaze: Boolean,
    val view: AvatarView?,
    val reducedMotion: Boolean,
    val size: Double,
    val visible: Boolean,
)

private var clockOrigin: Long? = null

private fun clockSeconds(nanos: Long): Double {
    val origin = clockOrigin ?: nanos.also { clockOrigin = it }
    return (nanos - origin) / 1e9
}

// The system frame clock stops by itself while the app is in the background.
internal suspend fun runFrameLoop(
    avatar: PreparedAvatar,
    player: AvatarPlayer,
    input: State<() -> LiveInput>,
    frame: MutableState<AvatarFrame?>,
) {
    var dueAt = 0.0
    while (true) {
        val nanos = withFrameNanos { it }
        val time = clockSeconds(nanos)
        if (time < dueAt) continue
        val current = input.value()
        val wait = if (current.size > 0.0) {
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
            next.nextFrameIn
        } else {
            null
        }
        if (wait == null || !current.visible) {
            snapshotFlow { input.value() }.first { it != current }
            dueAt = 0.0
        } else {
            dueAt = time + wait
        }
    }
}
