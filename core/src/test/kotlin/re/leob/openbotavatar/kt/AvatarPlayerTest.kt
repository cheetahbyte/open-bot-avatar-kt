package re.leob.openbotavatar.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarPlayerTest {
    private val avatar = PreparedAvatar.of(AvatarCatalog.definition("mug")!!)

    @Test
    fun largeAvatarsAskForEveryFrameAndSmallOnesFor30Fps() {
        assertEquals(0.0, AvatarPlayer().next(avatar, 0.0, 0.0, size = 200.0).nextFrameIn)
        assertEquals(1 / 30.0 - 0.004, AvatarPlayer().next(avatar, 0.0, 0.0, size = 64.0).nextFrameIn!!, 1e-12)
    }

    @Test
    fun reducedMotionSleepsOnceEasingSettles() {
        val player = AvatarPlayer()
        assertNull(player.next(avatar, 0.0, 0.0, reducedMotion = true, size = 200.0).nextFrameIn)
    }

    @Test
    fun aMoodChangeEasesThenSettlesUnderReducedMotionWithoutFrames() {
        val player = AvatarPlayer()
        player.next(avatar, 0.0, 0.0, size = 200.0)
        val during = player.next(avatar, 100.0, 0.1, mood = AvatarMood.HAPPY, size = 200.0)
        assertNotNull(during.nextFrameIn)
        val snapped = player.next(avatar, 200.0, 0.2, mood = AvatarMood.IDLE, reducedMotion = true, size = 200.0)
        assertNull(snapped.nextFrameIn)
    }
}
