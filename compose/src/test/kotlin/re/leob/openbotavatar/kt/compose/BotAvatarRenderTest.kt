package re.leob.openbotavatar.kt.compose

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.AvatarView
import re.leob.openbotavatar.kt.PreparedAvatar
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws through real Skia on the JVM, so these read what the composable paints. PNGs land in build/renders. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-xxhdpi")
class BotAvatarRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val mug = AvatarCatalog.definition("mug")!!
    private val red = Color(0xFFE02020)

    @Before
    fun prepare() {
        PreparedAvatar.of(mug)
        rule.mainClock.autoAdvance = false
    }

    private fun capture(name: String): Bitmap {
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(android.graphics.Canvas(image)) }
        val file = File("build/renders/$name.png").also { it.parentFile?.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    private fun Bitmap.count(color: Color): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val p = Color(getPixel(x, y))
            if (p.alpha > 0.98f && abs(p.red - color.red) < 0.02f && abs(p.green - color.green) < 0.02f && abs(p.blue - color.blue) < 0.02f) n++
        }
        return n
    }

    private fun showStill(view: AvatarView? = null, label: String? = null) {
        rule.setContent {
            Box(Modifier.size(240.dp)) {
                BotAvatar(
                    definition = mug,
                    modifier = Modifier.size(240.dp).testTag("avatar"),
                    color = red,
                    eyeColor = Color.Black,
                    still = true,
                    view = view,
                    label = label,
                )
            }
        }
    }

    @Test
    fun stillPaintsBodyAndEyes() {
        showStill()
        val image = capture("still")
        val side = with(rule.density) { 240.dp.toPx() }
        val share = image.count(red) / (side * side)
        assertTrue("body covers $share of the box", share in 0.2f..0.7f)
        assertTrue(image.count(Color.Black) > 500)
    }

    @Test
    fun liveAvatarRedrawsForANewMood() {
        val mood = mutableStateOf(AvatarMood.IDLE)
        rule.setContent {
            Box(Modifier.size(240.dp)) {
                BotAvatar(
                    mug,
                    Modifier.size(240.dp),
                    color = red,
                    eyeColor = Color.Black,
                    mood = mood.value,
                    reducedMotion = AvatarReducedMotion.On,
                )
            }
        }
        val idle = capture("live-idle")
        assertTrue(idle.count(red) > 1000)
        rule.runOnUiThread { mood.value = AvatarMood.HAPPY }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val happy = capture("live-happy")
        assertFalse(idle.sameAs(happy))
    }

    @Test
    fun aSidewaysViewStillDrawsABody() {
        showStill(view = AvatarView(yaw = 1.6, pitch = 0.0))
        assertTrue(capture("side").count(red) > 1000)
    }

    @Test
    fun turnedAwayItHidesTheEyes() {
        showStill(view = AvatarView(yaw = 2.9, pitch = 0.0))
        val image = capture("back")
        assertTrue(image.count(red) > 1000)
        assertEquals(0, image.count(Color.Black))
    }

    @Test
    fun aPointerOnTheDrawnEyesInAWideBoxIsLevelWithThem() {
        val eyes = mug.eyes
        val look = lookFrom(
            position = Offset(100f + eyes.x.toFloat(), eyes.y.toFloat()),
            bounds = Rect(0f, 0f, 300f, 100f),
            eyes = eyes,
            density = 1f,
        )
        assertEquals(0.0, look.x, 1e-3)
        assertEquals(0.0, look.y, 1e-3)
    }

    @Test
    fun labelBecomesTheContentDescription() {
        showStill(label = "Build bot")
        rule.onNodeWithTag("avatar").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Build bot")),
        )
    }
}
