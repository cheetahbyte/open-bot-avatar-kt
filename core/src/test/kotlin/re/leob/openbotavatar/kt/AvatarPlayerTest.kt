package re.leob.openbotavatar.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarPlayerTest {
    private val avatar = PreparedAvatar.of(AvatarCatalog.definition("mug")!!)

    @Test
    fun smallAvatarsSkipEveryOtherVsyncAndLargeOnesDoNot() {
        assertEquals(0.0, AvatarPlayer().next(avatar, 0.0, 0.0, size = 200.0).nextFrameIn)
        val wait = AvatarPlayer().next(avatar, 0.0, 0.0, size = 64.0).nextFrameIn!!
        assertTrue("$wait", wait > 1 / 60.0 && wait <= 1 / 30.0)
    }

    @Test
    fun reducedMotionAsksForNoFurtherFrames() {
        assertNull(AvatarPlayer().next(avatar, 0.0, 0.0, reducedMotion = true, size = 200.0).nextFrameIn)
    }

    @Test
    fun reducedMotionAppliesAMoodChangeAtOnce() {
        val player = AvatarPlayer()
        player.next(avatar, 0.0, 0.0, size = 200.0)
        val changed = player.next(avatar, 16.0, 0.016, mood = AvatarMood.HAPPY, reducedMotion = true, size = 200.0)
        assertNull(changed.nextFrameIn)
        assertTrue(changed.frame.shapes.filterIsInstance<FrameShape.Eye>().all { it.mark is EyeMark.Arc })
    }

    @Test
    fun aMoodChangeEasesInstead() {
        val player = AvatarPlayer()
        player.next(avatar, 0.0, 0.0, size = 200.0)
        val eyes = player.next(avatar, 16.0, 0.016, mood = AvatarMood.HAPPY, size = 200.0).frame.shapes.filterIsInstance<FrameShape.Eye>()
        assertTrue(eyes.isNotEmpty() && eyes.all { it.mark is EyeMark.Pill })
    }
}
