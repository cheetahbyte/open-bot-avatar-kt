package re.leob.openbotavatar.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
        val eyes = changed.frame.shapes.filterIsInstance<FrameShape.Eye>()
        assertEquals(2, eyes.size)
        assertTrue(eyes.all { it.mark is EyeMark.Arc })
    }

    @Test
    fun stateClockSurvivesMoodChangesButRestartsForANewState() {
        val animator = AvatarAnimator()
        assertEquals(0.0, animator.step(1000.0, state = AvatarState.WORKING).stateAge, 0.0)
        assertEquals(2.0, animator.step(3000.0, state = AvatarState.WORKING, mood = AvatarMood.HAPPY).stateAge, 0.0)
        assertEquals(0.0, animator.step(3200.0, state = AvatarState.DONE).stateAge, 0.0)
        assertEquals(0.2, animator.step(3400.0, state = AvatarState.DONE, mood = AvatarMood.HAPPY).stateAge, 1e-12)
        animator.reset()
        assertEquals(DONE_TURN_S, animator.step(4000.0, state = AvatarState.DONE).stateAge, 1e-12)
    }

    @Test
    fun moodOnlyBlendsKeepTheTurnOnBothSides() {
        val input = PoseInput(hashString("mug"), 8.0, state = AvatarState.DONE, stateAge = 1.3)
        val before = computePose(input)
        val mixed = computePose(input.copy(mood = AvatarMood.HAPPY,
            blend = AvatarMoodBlend(MoodState(state = AvatarState.DONE, mood = AvatarMood.NEUTRAL, age = 0.1), 0.4)))
        assertEquals(before.yaw, mixed.yaw, 1e-12)
        assertEquals(before.sweep, mixed.sweep, 1e-12)
        assertEquals(0.4, mixed.eyes.arc, 1e-12)
    }

    @Test
    fun replacementMarksAndShrinkKeepSomethingVisible() {
        val blocked = avatar.frame(FrameOptions(state = AvatarState.BLOCKED, reducedMotion = true))
        assertEquals(listOf("mark-0", "mark-1"), blocked.shapes.map { it.key })
        val thinking = avatar.frame(FrameOptions(state = AvatarState.THINKING, reducedMotion = true))
        assertEquals(listOf("mark-0", "mark-1", "mark-2"), thinking.shapes.map { it.key })
        val changing = avatar.frame(FrameOptions(state = AvatarState.BLOCKED, reducedMotion = true,
            moodBlend = AvatarMoodBlend(MoodState(state = AvatarState.IDLE), 0.5)))
        assertTrue(changing.shapes.any { it.key.startsWith("mark-") })
        assertTrue(changing.shapes.any { !it.key.startsWith("mark-") })
    }

    @Test
    fun ribbonsBracketTheBodyAndFollowItsColor() {
        val options = FrameOptions(state = AvatarState.DONE, stateAge = 1.2, time = 8.0, color = "#1688ff")
        val frame = avatar.frame(options)
        val ribbons = frame.shapes.filterIsInstance<FrameShape.Ribbon>()
        assertTrue(ribbons.any { it.front })
        assertTrue(ribbons.any { !it.front })
        val firstBody = frame.shapes.indexOfFirst { it !is FrameShape.Ribbon }
        val lastBody = frame.shapes.indexOfLast { it !is FrameShape.Ribbon }
        assertTrue(frame.shapes.take(firstBody).all { it is FrameShape.Ribbon && !it.front })
        assertTrue(frame.shapes.drop(lastBody + 1).all { it is FrameShape.Ribbon && it.front })
        assertTrue(ribbons.all { it.gradient.stops.size == 3 })
        assertFalse(ribbons.map { it.gradient.stops } == avatar.frame(options.copy(color = "#ff6a1a"))
            .shapes.filterIsInstance<FrameShape.Ribbon>().map { it.gradient.stops })
    }

    @Test
    fun reducedMotionFreezesEveryStateAndHidesRibbons() {
        for (state in AvatarState.entries) {
            val options = FrameOptions(state = state, stateAge = 1.2, reducedMotion = true)
            val calm = avatar.frame(options)
            assertEquals(state.name, calm, avatar.frame(options.copy(time = 300.0, stateAge = 100.0)))
            assertTrue(calm.shapes.none { it is FrameShape.Ribbon })
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyMoodCallsResolveToTheNewLooks() {
        assertEquals(avatar.frame(FrameOptions(state = AvatarState.THINKING)), avatar.frame(FrameOptions(mood = AvatarMood.THINKING)))
        assertEquals(avatar.frame(FrameOptions(state = AvatarState.IDLE)), avatar.frame(FrameOptions(mood = AvatarMood.IDLE)))
        assertEquals(avatar.frame(FrameOptions(state = AvatarState.IDLE, mood = AvatarMood.HAPPY)), avatar.frame(FrameOptions(motion = AvatarMotion.HAPPY)))
    }

    @Test
    fun aMoodChangeEasesInstead() {
        val player = AvatarPlayer()
        player.next(avatar, 0.0, 0.0, size = 200.0)
        val eyes = player.next(avatar, 16.0, 0.016, mood = AvatarMood.HAPPY, size = 200.0).frame.shapes.filterIsInstance<FrameShape.Eye>()
        assertTrue(eyes.isNotEmpty() && eyes.all { it.mark is EyeMark.Pill })
    }
}
