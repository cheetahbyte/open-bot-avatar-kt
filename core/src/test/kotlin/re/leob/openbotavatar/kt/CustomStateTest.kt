package re.leob.openbotavatar.kt

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.volume.OutlineModel
import re.leob.openbotavatar.kt.volume.analyzeBody
import re.leob.openbotavatar.kt.volume.buildVolume

/** States and marks of a host's own, beyond the fixtures of ParityTest. */
class CustomStateTest {
    private val mug = PreparedAvatar.of(AvatarCatalog.definition("mug")!!)
    private val asking = customStates.getValue("asking")
    private val base = PoseInput(seed = 7, time = 1.3)

    private fun AvatarFrame.marks() = shapes.filter { it.key.startsWith("mark-") }
    private fun showing(vararg parts: MarkPart) = mark("custom", *parts).let { m ->
        AvatarStateDefinition("custom") { it.base.copy(marks = listOf(PoseMark(m, 1.0))) }
    }

    @Test
    fun theSixStatesAreDefinitions() {
        for (state in AvatarState.entries) {
            assertEquals(state.name.lowercase(), state.id)
            assertSame(state, stateDefinition(state))
            // A definition that only delegates is the state itself.
            val copy = AvatarStateDefinition(state.id) { state.pose(it) }
            assertEquals(computePose(base.copy(state = state, stateAge = 1.0)), computePose(base.copy(state = copy, stateAge = 1.0)))
        }
        assertEquals(DONE_TURN_S, AvatarState.DONE.startAge, 0.0)
        assertEquals(0.0, AvatarState.IDLE.startAge, 0.0)
    }

    @Test
    fun theContextIsTheOneABuiltInStateGets() {
        val seen = ArrayList<StateContext>()
        val spy = AvatarStateDefinition("spy") { seen.add(it); it.base }
        computePose(PoseInput(7, 2.0, AvatarMood.HAPPY, AvatarGaze(0.0, 0.0, 1.0), state = spy, stateAge = 4.5))
        val live = seen.last()
        assertEquals(listOf<Any?>(7, 2.0, 4.5, AvatarMood.HAPPY, false, 1.0), listOf(live.seed, live.time, live.stateAge, live.mood, live.reducedMotion, live.live))
        assertTrue(live.t != 2.0)
        assertEquals(0.3, live.sway, 1e-12)
        assertEquals(1.0, live.base.eyes.arc, 0.0)
        assertEquals(computePose(base.copy(state = AvatarState.IDLE)), computePose(base.copy(state = AvatarStateDefinition("calm") { it.rest() })))
        computePose(PoseInput(7, 2.0, state = spy, reducedMotion = true))
        val still = seen.last()
        assertEquals(listOf(0.0, 0.0, 0.0), listOf(still.t, still.live, still.wave(3.0)))
        assertNull(still.stateAge)
    }

    @Test
    fun thePlayerTakesADefinitionAndEasesToAndFromIt() {
        val player = AvatarPlayer()
        fun at(ms: Double, state: AvatarStateDefinition) = player.next(mug, ms, ms / 1000, size = 200.0, state = state).frame
        assertTrue(at(0.0, AvatarState.IDLE).marks().isEmpty())
        assertTrue(at(16.0, asking).marks().isEmpty())
        at(250.0, asking)
        assertEquals(2, at(900.0, asking).marks().size)
        assertTrue(at(900.0, asking).shapes.all { it.key.startsWith("mark-") })
        // The same state built again, as a composable that recomposes would, is no change.
        assertEquals(2, at(1000.0, AvatarStateDefinition("asking") { asking.pose(it) }).marks().size)
        assertEquals(2, at(1016.0, AvatarState.IDLE).marks().size)
        assertTrue(at(2000.0, AvatarState.IDLE).marks().isEmpty())
    }

    @Test
    fun aStateKeepsItsAgeThroughABlendFromTheSameId() {
        val ages = ArrayList<Double?>()
        fun aged(id: String) = AvatarStateDefinition(id) { ages.add(it.stateAge); it.base }
        fun run(from: String) {
            ages.clear()
            computePose(base.copy(state = aged("a"), stateAge = 5.0, blend = AvatarMoodBlend(MoodState(AvatarMood.HAPPY, state = aged(from), age = 3.0), 0.5)))
        }
        run("a")
        assertEquals(listOf(5.0, 5.0), ages)
        run("b")
        assertEquals(listOf(5.0, 3.0), ages)
    }

    @Test
    fun blendedMarksDoNotDependOnTheSideTheyComeFrom() {
        fun ids(state: AvatarState, from: AvatarState) =
            computePose(base.copy(state = state, blend = AvatarMoodBlend(MoodState(state = from), 0.5))).marks.map { it.mark.id to it.weight }
        val both = listOf("bang" to 0.5, "dots" to 0.5)
        assertEquals(both, ids(AvatarState.BLOCKED, AvatarState.THINKING))
        assertEquals(both, ids(AvatarState.THINKING, AvatarState.BLOCKED))
        val thinking = computePose(base.copy(state = AvatarState.THINKING))
        val blocked = computePose(base.copy(state = AvatarState.BLOCKED))
        assertEquals(blocked.marks, blendPoses(thinking, blocked, 1.0).marks)
        assertEquals(thinking.marks, blendPoses(thinking, blocked, 0.0).marks)
    }

    @Test
    fun somethingIsDrawnWhateverTheMarksWeigh() {
        val third = mark("third", part("third"))
        val bounds = Bounds(10.0, 10.0, 90.0, 90.0)
        for (marks in listOf(
            listOf(BANG, DOTS, third).map { PoseMark(it, 1.0 / 3) },
            listOf(BANG, DOTS).map { PoseMark(it, 0.3) },
            listOf(BANG, DOTS, third).map { PoseMark(it, 0.45) },
        )) {
            assertEquals(emptyList<MarkPart>(), markParts(bounds, marks, 1, 0.0, true))
            assertTrue(bodyScale(marks) > 0.05)
        }
        // A mark that shows takes the body's place as before.
        assertEquals(1.0, bodyScale(emptyList()), 0.0)
        assertEquals(0.0, bodyScale(listOf(PoseMark(BANG, 1.0))), 0.0)
        assertEquals(0.0, bodyScale(listOf(PoseMark(BANG, 0.5), PoseMark(DOTS, 0.5))), 0.0)
        for (t in 0..50) for (u in 0..10) {
            val between = MoodState(state = AvatarState.BLOCKED, blend = AvatarMoodBlend(MoodState(state = AvatarState.THINKING), u / 10.0))
            for (state in listOf(AvatarState.IDLE, customStates.getValue("third"))) {
                val frame = mug.frame(FrameOptions(state = state, time = 1.0, moodBlend = AvatarMoodBlend(between, t / 50.0)))
                assertTrue("$t $u", frame.shapes.isNotEmpty())
            }
        }
    }

    @Test
    fun aMarksPathNeverSharesAnIdWithABodySlice() {
        val body = mug.frame(FrameOptions()).paths.map { it.id }.toSet()
        for (shape in listOf("0", "1", "12")) {
            val frame = mug.frame(FrameOptions(state = showing(part(shape))))
            assertEquals(1, frame.paths.size)
            assertFalse(frame.paths[0].id in body)
            val mid = mug.frame(FrameOptions(state = showing(part(shape)), moodBlend = AvatarMoodBlend(MoodState(state = AvatarState.IDLE), 0.5)))
            assertEquals(mid.paths.size, mid.paths.map { it.id }.toSet().size)
        }
    }

    @Test
    fun aMarksOutlineCannotBecomeMarkup() {
        val svg = mug.svg(FrameOptions(state = customStates.getValue("markup")))
        assertFalse("<script" in svg)
        assertTrue("L5 5Z&#34;/&#62;&#60;script&#62;" in svg)
    }

    @Suppress("DEPRECATION")
    @Test
    fun theOldMarkFieldsStillRead() {
        assertEquals(1.0, computePose(base.copy(state = AvatarState.BLOCKED)).bang, 0.0)
        assertEquals(1.0, computePose(base.copy(state = AvatarState.THINKING)).dots, 0.0)
        assertEquals(0.0, computePose(base.copy(state = asking)).bang, 0.0)
    }

    @Test
    fun onlyASurfaceWithDepthHasAModel() {
        assertSame(mug.frame(FrameOptions()).surface.volume, mug.frame(FrameOptions(time = 3.0)).surface.volume)
        assertNull(PreparedAvatar.of(mug.definition, 0.0).frame().surface.volume)
    }

    @Test
    fun anOutlineIsOneModelAtAnySize() {
        fun box(u: Double) = PathBox(-0.26 * u, -0.3225 * u, 0.52 * u, 0.645 * u)
        val a = OutlineModel.of(hookPath(40.0), box(40.0))!!
        val b = OutlineModel.of(hookPath(70.0), box(70.0))!!
        assertEquals(a.key, b.key)
        assertSame(a.volume, b.volume)
        assertEquals(70.0 / 40, a.scale / b.scale, 1e-9)
        assertSame(a, OutlineModel.of(hookPath(40.0), box(40.0)))
        val wide = OutlineModel.of(SQUARE, PathBox(-9.0, -9.0, 36.0, 18.0))!!
        assertNotSame(OutlineModel.of(SQUARE, PathBox(-9.0, -9.0, 18.0, 18.0)), wide)
        assertEquals(9.0, wide.cx, 0.0)
    }

    @Test
    fun volumesBuildAlikeOnManyThreads() {
        val defs = AvatarCatalog.all
        fun slices(d: AvatarDefinition) = buildVolume(d.eyes, 1.0, analyzeBody(d.path)).parts.flatMap { p -> (p.zSlices + p.xSlices).map { it.d } }
        val alone = defs.map(::slices)
        val pool = Executors.newFixedThreadPool(8)
        try {
            assertEquals(alone, pool.invokeAll(defs.map { Callable { slices(it) } }).map { it.get() })
        } finally {
            pool.shutdown()
        }
    }
}
