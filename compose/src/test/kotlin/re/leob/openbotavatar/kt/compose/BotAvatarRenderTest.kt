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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.AvatarMark
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.AvatarState
import re.leob.openbotavatar.kt.AvatarStateDefinition
import re.leob.openbotavatar.kt.FrameOptions
import re.leob.openbotavatar.kt.FrameShape
import re.leob.openbotavatar.kt.AvatarView
import re.leob.openbotavatar.kt.MarkPart
import re.leob.openbotavatar.kt.PathBox
import re.leob.openbotavatar.kt.PoseMark
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
    fun aWaitingBotPaintsFilledPathEyes() {
        rule.setContent {
            BotAvatar(mug, Modifier.size(240.dp), color = red, eyeColor = Color.Black,
                state = AvatarState.WAITING, still = true)
        }
        val image = capture("waiting")
        assertTrue(image.count(red) > 1000)
        assertTrue(image.count(Color.Black) > 300)
    }

    @Test
    fun aBlockedBotPaintsItsMarkInsteadOfTheBodyAndEyes() {
        rule.setContent {
            BotAvatar(mug, Modifier.size(240.dp), color = red, eyeColor = Color.Black,
                state = AvatarState.BLOCKED, still = true)
        }
        val image = capture("blocked")
        val side = with(rule.density) { 240.dp.toPx() }
        assertTrue(image.count(red) / (side * side) in 0.02f..0.15f)
        assertEquals(0, image.count(Color.Black))
    }

    @Test
    fun liveRibbonGradientsUseTheCurrentComposeColor() {
        val blue = Color(0xFF1688FF)
        val color = mutableStateOf(red)
        rule.setContent {
            BotAvatar(mug, Modifier.size(240.dp), color = color.value, eyeColor = Color.Black,
                state = AvatarState.DONE, stateAge = 1.2, reducedMotion = AvatarReducedMotion.Off)
        }
        fun Bitmap.blueRibbonPixels(): Int {
            var count = 0
            for (y in 0 until height) for (x in 0 until width) {
                val p = Color(getPixel(x, y))
                // Ignore the flat body and its antialiased edge; look for the blue ribbon ramp.
                val differsFromBody = abs(p.red - blue.red) > 0.04f || abs(p.green - blue.green) > 0.04f || abs(p.blue - blue.blue) > 0.04f
                if (differsFromBody && p.blue > p.red + 0.2f && p.blue > p.green + 0.05f) count++
            }
            return count
        }
        val before = capture("ribbons-red")
        rule.runOnUiThread { color.value = blue }
        val after = capture("ribbons-blue")
        assertTrue(after.count(blue) > 1000)
        assertTrue("blue ribbons must be recolored, not left orange", after.blueRibbonPixels() > before.blueRibbonPixels() + 1000)
    }

    @Test
    fun drawScopePaintsNumericEyeMorphs() {
        val prepared = PreparedAvatar.of(mug)
        val frame = prepared.frame(FrameOptions(state = AvatarState.WAITING, mood = AvatarMood.HAPPY,
            moodBlend = re.leob.openbotavatar.kt.AvatarMoodBlend(
                re.leob.openbotavatar.kt.MoodState(state = AvatarState.WAITING, mood = AvatarMood.NEUTRAL), 0.4), reducedMotion = true))
        val eyes = frame.shapes.filterIsInstance<FrameShape.Eye>()
        assertEquals(2, eyes.size)
        assertTrue(eyes.all { it.mark is re.leob.openbotavatar.kt.EyeMark.Path })
        rule.setContent {
            androidx.compose.foundation.Canvas(Modifier.size(240.dp)) { drawAvatarFrame(frame, red, Color.Black) }
        }
        val image = capture("eye-morph")
        assertTrue(image.count(red) > 1000)
        assertTrue(image.count(Color.Black) > 300)
    }

    @Test
    fun aCustomStateShowsItsMarkAndLeavesAgain() {
        val bar = AvatarMark("bar") { listOf(MarkPart("bar", "M-30 -8H30V8H-30Z", PathBox(-30.0, -8.0, 60.0, 16.0))) }
        val asking = AvatarStateDefinition("asking") { it.base.copy(marks = listOf(PoseMark(bar, 1.0))) }
        val state = mutableStateOf<AvatarStateDefinition>(AvatarState.IDLE)
        rule.setContent {
            BotAvatar(mug, Modifier.size(240.dp), color = red, eyeColor = Color.Black, state = state.value,
                reducedMotion = AvatarReducedMotion.On)
        }
        val idle = capture("custom-idle")
        assertTrue(idle.count(Color.Black) > 300)
        rule.runOnUiThread { state.value = asking }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val shown = capture("custom-asking")
        assertEquals(0, shown.count(Color.Black))
        val side = with(rule.density) { 240.dp.toPx() }
        assertEquals(0.6f * 0.16f, shown.count(red) / (side * side), 0.01f)
        rule.runOnUiThread { state.value = AvatarState.IDLE }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue(capture("custom-back").sameAs(idle))
    }

    private fun showsAMaterial(still: Boolean) {
        val blue = Color(0xFF2040E0)
        val tile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(blue.toArgb()) }.asImageBitmap()
        val finish = AvatarMaterial("tile") { shape, _, _, _ ->
            val b = shape.bounds
            MaterialImage(tile, (b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, 20.0)
        }
        val material = mutableStateOf<AvatarMaterial?>(null)
        rule.setContent {
            BotAvatar(mug, Modifier.size(240.dp), color = red, eyeColor = Color.Black, still = still, material = material.value)
        }
        val flat = capture("flat-$still")
        assertTrue(flat.count(red) > 1000)
        assertEquals(0, flat.count(blue))
        rule.runOnUiThread { material.value = finish }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val finished = capture("material-$still")
        assertEquals(0, finished.count(red))
        assertEquals(0, finished.count(Color.Black))
        assertTrue(finished.count(blue) > 1000)
    }

    @Test
    fun aMaterialPaintsTheBodyOfAStillAvatar() = showsAMaterial(still = true)

    @Test
    fun aMaterialPaintsTheBodyOfALiveAvatar() = showsAMaterial(still = false)

    @Test
    fun labelBecomesTheContentDescription() {
        showStill(label = "Build bot")
        rule.onNodeWithTag("avatar").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Build bot")),
        )
    }
}
