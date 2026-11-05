package re.leob.openbotavatar.kt.compose

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Whether an avatar keeps its body still and stops blinking. */
public enum class AvatarReducedMotion {
    /** Follow the system: on when animations are turned off in the accessibility settings. */
    System,
    On,
    Off,
}

private fun animationsDisabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Android's counterpart to `prefers-reduced-motion`, watched while composed. */
@Composable
internal fun rememberSystemReducedMotion(): State<Boolean> {
    val context = LocalContext.current
    val disabled = remember(context) { mutableStateOf(animationsDisabled(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                disabled.value = animationsDisabled(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        disabled.value = animationsDisabled(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return disabled
}
