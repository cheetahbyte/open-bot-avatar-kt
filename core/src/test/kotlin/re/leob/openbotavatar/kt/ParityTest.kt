package re.leob.openbotavatar.kt

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Holds the port to the TypeScript engine, through fixtures from scripts/sync-upstream.ts. */
class ParityTest {
    private val fixtures: JsonObject = javaClass.getResourceAsStream("/parity.json.gz")!!.use { stream ->
        Json.parseToJsonElement(GZIPInputStream(stream).readBytes().decodeToString()).jsonObject
    }
    private val definitions: Map<String, AvatarDefinition> = fixtures.list("defs").associate { element ->
        val valid = validateAvatar(element) as ValidationResult.Valid
        valid.value.id to valid.value
    }

    private fun JsonObject.list(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonObject.number(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun moodOf(name: String?): AvatarMood? = name?.let { AvatarMood.valueOf(it.uppercase()) }
    private fun stateOf(name: String?): AvatarState? = name?.let { AvatarState.valueOf(it.uppercase()) }
    private fun motionOf(name: String?): AvatarMotion? = name?.let { AvatarMotion.valueOf(it.uppercase()) }

    private fun stateOf(e: JsonElement): MoodState {
        val o = e as? JsonObject ?: return MoodState(motion = motionOf(e.jsonPrimitive.content))
        return MoodState(mood = moodOf(o.text("mood")), blend = blendOf(o["blend"]),
            state = stateOf(o.text("state")), age = o.number("age"), motion = motionOf(o.text("motion")))
    }

    private fun blendOf(e: JsonElement?): AvatarMoodBlend? {
        val o = e as? JsonObject ?: return null
        return AvatarMoodBlend(stateOf(o.getValue("from")), o.getValue("t").jsonPrimitive.double)
    }

    private fun gazeOf(e: JsonElement?): AvatarGaze? {
        val o = e as? JsonObject ?: return null
        return AvatarGaze(o.number("x")!!, o.number("y")!!, o.number("weight"))
    }

    private fun optionsOf(o: JsonObject) = FrameOptions(
        color = o.text("color"),
        eyeColor = o.text("eyeColor"),
        view = (o["view"] as? JsonObject)?.let { AvatarView(it.number("yaw")!!, it.number("pitch")!!) },
        time = o.number("time") ?: 0.0,
        gaze = gazeOf(o["gaze"]),
        mood = moodOf(o.text("mood")),
        state = stateOf(o.text("state")),
        motion = motionOf(o.text("motion")),
        stateAge = o.number("stateAge"),
        moodBlend = blendOf(o["motionBlend"]),
        reducedMotion = o["reducedMotion"]?.jsonPrimitive?.booleanOrNull == true,
    )

    private val number = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")

    private fun lastDigit(token: String) = 10.0.pow(-token.substringAfter('.', "").length)

    /** Equal text, except that numbers may differ in their last rounded digit: libm results vary by an ulp. */
    private fun assertSameMarkup(context: String, expected: String, actual: String) {
        val a = number.findAll(expected).map { it.value }.toList()
        val b = number.findAll(actual).map { it.value }.toList()
        // Compact SVG paths omit separators before signs and decimal points. Compare commands,
        // then numbers, without requiring identical optional separators.
        fun markup(s: String) = s.replace(number, "#").replace(Regex("(?<=#)\\s+(?=#)"), "")
        assertEquals("$context: markup", markup(expected), markup(actual))
        assertEquals("$context: number count", a.size, b.size)
        for (i in a.indices) {
            val digit = minOf(0.1, lastDigit(a[i]), lastDigit(b[i]))
            assertTrue("$context: number $i, ${a[i]} vs ${b[i]}", abs(a[i].toDouble() - b[i].toDouble()) <= digit * 1.001)
        }
    }

    @Test
    fun fixturesIdentifyThePinnedUpstream() {
        assertEquals("0.3.0", fixtures.text("upstreamVersion"))
        assertEquals("5e64c081971eb296f562544641bad37d40fb36b6", fixtures.text("upstreamRevision"))
    }

    @Test
    fun malformedJsonReturnsAnInvalidResult() {
        assertTrue(validateAvatar("{not json") is ValidationResult.Invalid)
    }

    @Test
    fun svgMatchesUpstream() {
        for (case in fixtures.list("svgs").map { it.jsonObject }) {
            val options = case.getValue("options").jsonObject
            val svg = renderAvatarSvg(
                definitions.getValue(case.text("id")!!),
                optionsOf(options),
                depth = options.number("depth") ?: DEFAULT_DEPTH,
                size = options.number("size"),
            )
            assertSameMarkup("${case.text("id")} $options", case.text("svg")!!, svg)
        }
    }

    @Test
    fun liveFramesKeepTheSameSlices() {
        for (case in fixtures.list("frames").map { it.jsonObject }) {
            val avatar = PreparedAvatar.of(definitions.getValue(case.text("id")!!))
            val frame = avatar.frame(FrameOptions(time = 2.5), case.number("size"))
            val keys = case.list("keys").map { it.jsonPrimitive.content }
            assertEquals(case.toString().take(80), keys, frame.shapes.map { it.key })
            assertEquals(case.list("paths").map { it.jsonPrimitive.content }, frame.paths.map { it.id })
        }
    }

    @Test
    fun validationMatchesUpstream() {
        for (case in fixtures.list("invalid").map { it.jsonObject }) {
            val result = case.getValue("result").jsonObject
            val input = case.getValue("input")
            val actual = validateAvatar(input)
            if (result.getValue("ok").jsonPrimitive.boolean) {
                val expected = result.getValue("value").jsonObject
                val normalized = (actual as ValidationResult.Valid).value
                assertEquals(expected.text("id"), normalized.id)
                assertEquals(expected.text("name"), normalized.name)
                assertEquals(expected.getValue("body").jsonObject.text("path"), normalized.path)
                val eyes = expected.getValue("eyes").jsonObject
                for ((k, v) in mapOf("x" to normalized.eyes.x, "y" to normalized.eyes.y,
                    "spacing" to normalized.eyes.spacing, "scale" to normalized.eyes.scale))
                    assertEquals(k, eyes.number(k)!!, v, 1e-12)
            } else {
                val errors = result.list("errors").map { it.jsonPrimitive.content }
                assertEquals(input.toString(), errors, (actual as ValidationResult.Invalid).errors)
            }
        }
    }

    @Test
    fun posesMatchUpstream() {
        for (case in fixtures.list("poses").map { it.jsonObject }) {
            val i = case.getValue("input").jsonObject
            val pose = computePose(
                PoseInput(
                    seed = i.getValue("seed").jsonPrimitive.int,
                    time = i.number("time")!!,
                    mood = moodOf(i.text("mood")),
                    state = stateOf(i.text("state")),
                    stateAge = i.number("stateAge"),
                    motion = motionOf(i.text("motion")),
                    gaze = gazeOf(i["gaze"]),
                    reducedMotion = i.getValue("reducedMotion").jsonPrimitive.boolean,
                    blend = blendOf(i["blend"]),
                ),
            )
            val e = case.getValue("pose").jsonObject
            val fields = mapOf(
                "yaw" to pose.yaw, "pitch" to pose.pitch, "roll" to pose.roll, "lift" to pose.lift,
                "sx" to pose.sx, "sy" to pose.sy, "gazeX" to pose.gazeX, "gazeY" to pose.gazeY,
                "blink" to pose.blink, "squintL" to pose.squintL, "squintR" to pose.squintR,
                "ribbons" to pose.ribbons, "cage" to pose.cage, "sweep" to pose.sweep, "bang" to pose.bang, "dots" to pose.dots,
            )
            for ((name, value) in fields) assertEquals("$name in $i", e.number(name)!!, value, 1e-9)
            val eyes = e.getValue("eyes").jsonObject
            assertEquals("eyes.arc in $i", eyes.number("arc")!!, pose.eyes.arc, 1e-9)
            assertEquals("eyes.slit in $i", eyes.number("slit")!!, pose.eyes.slit, 1e-9)
        }
    }

    @Test
    fun animatorMatchesUpstream() {
        val animator = AvatarAnimator()
        for (case in fixtures.list("animator").map { it.jsonObject }) {
            val i = case.getValue("input").jsonObject
            if (case["reset"]?.jsonPrimitive?.booleanOrNull == true) animator.reset()
            val target = (i["target"] as? JsonObject)?.let { LookDirection(it.number("x")!!, it.number("y")!!) }
            val step = animator.step(
                now = i.number("now")!!,
                mood = moodOf(i.text("mood")),
                state = stateOf(i.text("state")),
                motion = motionOf(i.text("motion")),
                target = target,
                reducedMotion = i["reducedMotion"]?.jsonPrimitive?.booleanOrNull == true,
                hold = i["hold"]?.jsonPrimitive?.booleanOrNull == true,
            )
            val e = case.getValue("step").jsonObject
            assertEquals(e.getValue("moving").jsonPrimitive.boolean, step.moving)
            val gaze = e["gaze"] as? JsonObject
            assertEquals(gaze == null, step.gaze == null)
            assertEquals(e["motionBlend"] == null, step.moodBlend == null)
            if (gaze != null) {
                assertEquals(gaze.number("x")!!, step.gaze!!.x, 1e-9)
                assertEquals(gaze.number("y")!!, step.gaze.y, 1e-9)
                assertEquals(gaze.number("weight")!!, step.gaze.weight!!, 1e-9)
            }
            assertEquals(blendOf(e["motionBlend"]), step.moodBlend)
            assertEquals("stateAge in $i", e.number("stateAge")!!, step.stateAge, 1e-12)
        }
    }

    @Test
    fun pointerGazeAndHashMatchUpstream() {
        val box = AvatarBox(10.0, 20.0, 96.0, 96.0)
        for (case in fixtures.list("gazes").map { it.jsonObject }) {
            val gaze = pointerGaze(case.number("x")!!, case.number("y")!!, box, LookDirection(0.5, 0.42))
            val e = case.getValue("gaze").jsonObject
            assertEquals(e.number("x")!!, gaze.x, 1e-12)
            assertEquals(e.number("y")!!, gaze.y, 1e-12)
        }
        for (case in fixtures.list("hashes").map { it.jsonObject }) {
            assertEquals(case.getValue("hash").jsonPrimitive.int, hashString(case.text("s")!!))
        }
    }

    private fun assertMatrix(expected: JsonElement, actual: AvatarMatrix) {
        val values = listOf(actual.a, actual.b, actual.c, actual.d, actual.e, actual.f)
        expected.jsonArray.forEachIndexed { i, e -> assertEquals("matrix[$i]", e.jsonPrimitive.double, values[i], 1e-9) }
    }

    private fun assertBox(expected: JsonElement, actual: PathBox) {
        val e = expected.jsonObject
        for ((key, value) in mapOf("x" to actual.x, "y" to actual.y, "w" to actual.w, "h" to actual.h))
            assertEquals("box.$key", e.number(key)!!, value, 1e-9)
    }

    @Test
    fun detailedFramesMatchUpstream() {
        for (case in fixtures.list("detailedFrames").map { it.jsonObject }) {
            val o = case.getValue("options").jsonObject
            val frame = PreparedAvatar.of(definitions.getValue(case.text("id")!!), o.number("depth") ?: DEFAULT_DEPTH)
                .frame(optionsOf(o), o.number("size"))
            val e = case.getValue("frame").jsonObject
            assertEquals(e.text("name"), frame.name)
            assertEquals(e.text("color"), frame.color)
            assertEquals(e.text("ink"), frame.ink)
            assertMatrix(e.getValue("root"), frame.root)
            assertEquals("paths in $o", e.list("paths").size, frame.paths.size)
            e.list("paths").forEachIndexed { i, p ->
                val path = p.jsonObject; val actual = frame.paths[i]
                assertEquals(path.text("id"), actual.id)
                assertSameMarkup("${case.text("id")} path $i in $o", path.text("d")!!, actual.d)
                assertEquals(path.getValue("evenOdd").jsonPrimitive.boolean, actual.evenOdd)
                assertBox(path.getValue("box"), actual.box)
            }
            assertEquals("shapes in $o", e.list("shapes").size, frame.shapes.size)
            e.list("shapes").forEachIndexed { i, s ->
                val shape = s.jsonObject; val actual = frame.shapes[i]
                assertEquals(shape.text("key"), actual.key)
                assertMatrix(shape.getValue("matrix"), actual.matrix)
                when (actual) {
                    is FrameShape.Slice -> { assertEquals("slice", shape.text("kind")); assertEquals(shape.text("path"), actual.path) }
                    is FrameShape.Eye -> {
                        assertEquals("eye", shape.text("kind"))
                        val mark = shape.getValue("mark").jsonObject
                        when (val m = actual.mark) {
                            is EyeMark.Path -> { assertEquals("path", mark.text("shape")); assertSameMarkup("eye", mark.text("d")!!, m.d) }
                            is EyeMark.Arc -> {
                                assertEquals("arc", mark.text("shape")); assertSameMarkup("arc", mark.text("d")!!, m.d)
                                assertEquals(mark.number("strokeWidth")!!, m.strokeWidth, 1e-9)
                            }
                            is EyeMark.Pill -> {
                                assertEquals("pill", mark.text("shape"))
                                for ((k, v) in mapOf("x" to m.x, "y" to m.y, "width" to m.width, "height" to m.height, "radius" to m.radius))
                                    assertEquals(k, mark.number(k)!!, v, 1e-9)
                            }
                        }
                    }
                    is FrameShape.Ribbon -> {
                        assertEquals("ribbon", shape.text("kind")); assertSameMarkup("ribbon", shape.text("d")!!, actual.d)
                        assertBox(shape.getValue("box"), actual.box)
                        assertEquals(shape.getValue("front").jsonPrimitive.boolean, actual.front)
                        assertEquals(shape.text("fill"), actual.fill)
                        val g = shape.getValue("gradient").jsonObject
                        for ((k, v) in mapOf("x1" to actual.gradient.x1, "y1" to actual.gradient.y1, "x2" to actual.gradient.x2, "y2" to actual.gradient.y2))
                            assertEquals(k, g.number(k)!!, v, 1e-9)
                        assertEquals(g.list("stops").map { it.jsonPrimitive.content }, actual.gradient.stops)
                    }
                }
            }
        }
    }

    @Test
    fun numericEyesMatchUpstream() {
        for (case in fixtures.list("eyes").map { it.jsonObject }) {
            val look = case.getValue("look").jsonObject
            val spot = case.getValue("spot").jsonObject
            val eye = EyeSpot(spot.number("x")!!, spot.number("y")!!, spot.number("w")!!, spot.number("h")!!,
                spot.number("open")!!, spot.number("shift")!!)
            val numericLook = EyeLook(look.number("arc")!!, look.number("slit")!!)
            assertSameMarkup("numeric eye", case.text("path")!!, eyePath(numericLook, eye))
            val outline = eyeOutline(numericLook, eye)
            assertEquals(40, outline.size)
            case.list("outline").forEachIndexed { i, p ->
                assertEquals(p.jsonArray[0].jsonPrimitive.double, outline[i].x, 1e-9)
                assertEquals(p.jsonArray[1].jsonPrimitive.double, outline[i].y, 1e-9)
            }
        }
    }

    @Test
    fun catalogMatchesUpstream() {
        assertEquals(definitions.keys, AvatarCatalog.all.map { it.id }.toSet())
        for (entry in AvatarCatalog.all) assertTrue(entry.id, validateAvatar(entry) is ValidationResult.Valid)
        assertEquals("#f4f4f4", AvatarCatalog.color("mug"))
        assertEquals(DEFAULT_COLOR, AvatarCatalog.color("unknown"))
    }

    @Test
    fun aPreparedAvatarIsReadyWithoutRebuilding() {
        val mug = AvatarCatalog.definition("mug")!!
        assertSame(PreparedAvatar.of(mug), PreparedAvatar.cached(mug))
    }

    @Test
    fun anInvalidDefinitionThrowsWithItsErrors() {
        val invalid = AvatarCatalog.definition("mug")!!.copy(id = "Not A Slug")
        val error = assertThrows(InvalidAvatarException::class.java) { PreparedAvatar.of(invalid) }
        assertEquals(listOf("id must be a lowercase slug of 1-40 chars [a-z0-9-]"), error.errors)
    }
}
