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
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.volume.OutlineModel
import re.leob.openbotavatar.kt.volume.Volume
import re.leob.openbotavatar.kt.volume.analyzeBody
import re.leob.openbotavatar.kt.volume.buildVolume

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
    private fun stateOf(name: String?): AvatarStateDefinition? = name?.let { customStates[it] ?: AvatarState.valueOf(it.uppercase()) }

    /** A state as upstream holds it: a name, or a definition with the key the fixtures name it by. */
    private fun definitionOf(e: JsonElement?): AvatarStateDefinition? =
        stateOf(if (e is JsonObject) e.text("key") else e?.jsonPrimitive?.content)
    private fun motionOf(name: String?): AvatarMotion? = name?.let { AvatarMotion.valueOf(it.uppercase()) }

    private fun stateOf(e: JsonElement): MoodState {
        val o = e as? JsonObject ?: return MoodState(motion = motionOf(e.jsonPrimitive.content))
        return MoodState(mood = moodOf(o.text("mood")), blend = blendOf(o["blend"]),
            state = definitionOf(o["state"]), age = o.number("age"), motion = motionOf(o.text("motion")))
    }

    private fun blendOf(e: JsonElement?): AvatarMoodBlend? {
        val o = e as? JsonObject ?: return null
        return AvatarMoodBlend(stateOf(o.getValue("from")), o.getValue("t").jsonPrimitive.double)
    }

    private fun gazeOf(e: JsonElement?): AvatarGaze? {
        val o = e as? JsonObject ?: return null
        return AvatarGaze(o.number("x")!!, o.number("y")!!, o.number("weight"))
    }

    private fun viewOf(e: JsonElement?): AvatarView? =
        (e as? JsonObject)?.let { AvatarView(it.number("yaw")!!, it.number("pitch")!!) }

    private fun optionsOf(o: JsonObject) = FrameOptions(
        color = o.text("color"),
        eyeColor = o.text("eyeColor"),
        view = viewOf(o["view"]),
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
        assertEquals("0.5.1", fixtures.text("upstreamVersion"))
        assertEquals("396073383830bf7d1c6b83316a9ff2111192df03", fixtures.text("upstreamRevision"))
    }

    @Test
    fun malformedJsonReturnsAnInvalidResult() {
        assertTrue(validateAvatar("{not json") is ValidationResult.Invalid)
    }

    private fun assertSvgs(fixture: String) {
        for (case in fixtures.list(fixture).map { it.jsonObject }) {
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
    fun svgMatchesUpstream() = assertSvgs("svgs")

    @Test
    fun customSvgMatchesUpstream() = assertSvgs("customSvgs")

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

    private fun poseInputOf(i: JsonObject) = PoseInput(
        seed = i.getValue("seed").jsonPrimitive.int,
        time = i.number("time")!!,
        mood = moodOf(i.text("mood")),
        state = stateOf(i.text("state")),
        stateAge = i.number("stateAge"),
        motion = motionOf(i.text("motion")),
        gaze = gazeOf(i["gaze"]),
        view = viewOf(i["view"]),
        reducedMotion = i.getValue("reducedMotion").jsonPrimitive.boolean,
        blend = blendOf(i["blend"]),
    )

    private fun assertPose(context: String, e: JsonObject, pose: Pose) {
        val fields = mapOf(
            "yaw" to pose.yaw, "pitch" to pose.pitch, "roll" to pose.roll, "lift" to pose.lift,
            "sx" to pose.sx, "sy" to pose.sy, "gazeX" to pose.gazeX, "gazeY" to pose.gazeY,
            "blink" to pose.blink, "squintL" to pose.squintL, "squintR" to pose.squintR,
            "ribbons" to pose.ribbons, "cage" to pose.cage, "sweep" to pose.sweep,
        )
        for ((name, value) in fields) assertEquals("$name in $context", e.number(name)!!, value, 1e-9)
        val eyes = e.getValue("eyes").jsonObject
        assertEquals("eyes.arc in $context", eyes.number("arc")!!, pose.eyes.arc, 1e-9)
        assertEquals("eyes.slit in $context", eyes.number("slit")!!, pose.eyes.slit, 1e-9)
        val marks = e.list("marks").map { it.jsonObject }
        assertEquals("marks in $context", marks.map { it.getValue("mark").jsonObject.text("id") }, pose.marks.map { it.mark.id })
        marks.forEachIndexed { k, m -> assertEquals("weight $k in $context", m.number("weight")!!, pose.marks[k].weight, 1e-9) }
    }

    @Test
    fun posesMatchUpstream() {
        for (case in fixtures.list("poses").map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            assertPose("$input", case.getValue("pose").jsonObject, computePose(poseInputOf(input)))
        }
    }

    @Test
    fun customPosesMatchUpstream() {
        for (case in fixtures.list("customPoses").map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            assertPose("$input", case.getValue("pose").jsonObject, computePose(poseInputOf(input)))
        }
    }

    @Test
    fun animatorMatchesUpstream() {
        val animator = AvatarAnimator()
        for (case in fixtures.list("animator").map { it.jsonObject }) {
            if (case["reset"]?.jsonPrimitive?.booleanOrNull == true) animator.reset()
            assertStep(animator, case)
        }
    }

    @Test
    fun customStatesAnimateAsUpstream() {
        for (run in fixtures.list("customAnimator")) {
            val animator = AvatarAnimator()
            for (case in run.jsonArray.map { it.jsonObject }) {
                val step = assertStep(animator, case)
                val i = case.getValue("input").jsonObject
                val pose = computePose(
                    PoseInput(7, i.number("now")!! / 1000, moodOf(i.text("mood")), state = stateOf(i.text("state")), stateAge = step.stateAge,
                        reducedMotion = i.getValue("reducedMotion").jsonPrimitive.boolean, blend = step.moodBlend),
                )
                assertPose("$i", case.getValue("pose").jsonObject, pose)
            }
        }
    }

    private fun assertStep(animator: AvatarAnimator, case: JsonObject): AnimatorStep {
        val i = case.getValue("input").jsonObject
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
        assertEquals("moving in $i", e.getValue("moving").jsonPrimitive.boolean, step.moving)
        val gaze = e["gaze"] as? JsonObject
        assertEquals(gaze == null, step.gaze == null)
        assertEquals(e["motionBlend"] == null, step.moodBlend == null)
        if (gaze != null) {
            assertEquals(gaze.number("x")!!, step.gaze!!.x, 1e-9)
            assertEquals(gaze.number("y")!!, step.gaze.y, 1e-9)
            assertEquals(gaze.number("weight")!!, step.gaze.weight!!, 1e-9)
        }
        assertEquals("blend in $i", blendOf(e["motionBlend"]), step.moodBlend)
        assertEquals("stateAge in $i", e.number("stateAge")!!, step.stateAge, 1e-12)
        return step
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

    private fun assertMark(mark: JsonObject, actual: EyeMark) {
        when (actual) {
            is EyeMark.Path -> { assertEquals("path", mark.text("shape")); assertSameMarkup("eye", mark.text("d")!!, actual.d) }
            is EyeMark.Arc -> {
                assertEquals("arc", mark.text("shape")); assertSameMarkup("arc", mark.text("d")!!, actual.d)
                assertEquals(mark.number("strokeWidth")!!, actual.strokeWidth, 1e-9)
            }
            is EyeMark.Pill -> {
                assertEquals("pill", mark.text("shape"))
                for ((k, v) in mapOf("x" to actual.x, "y" to actual.y, "width" to actual.width, "height" to actual.height, "radius" to actual.radius))
                    assertEquals(k, mark.number(k)!!, v, 1e-9)
            }
        }
    }

    @Test
    fun detailedFramesMatchUpstream() = assertFrames("detailedFrames")

    @Test
    fun customFramesMatchUpstream() = assertFrames("customFrames")

    private fun assertFrames(fixture: String) {
        for (case in fixtures.list(fixture).map { it.jsonObject }) {
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
                assertEquals("round of ${actual.id} in $o", path["round"]?.jsonPrimitive?.boolean ?: false, actual.round)
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
                        assertMark(mark, actual.mark)
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
            val surface = e.getValue("surface").jsonObject
            val view = surface.getValue("view").jsonObject
            for ((k, v) in mapOf("cY" to frame.surface.view.cY, "sY" to frame.surface.view.sY, "cP" to frame.surface.view.cP,
                "sP" to frame.surface.view.sP, "facing" to frame.surface.view.facing))
                assertEquals("surface.$k in $o", view.number(k)!!, v, 1e-9)
            assertEquals("surface.scale in $o", surface.number("scale")!!, frame.surface.scale, 1e-9)
            assertEquals("surface.eyes in $o", surface.list("eyes").size, frame.surface.eyes.size)
            surface.list("eyes").forEachIndexed { i, eye ->
                val actual = frame.surface.eyes[i]
                assertMark(eye.jsonObject.getValue("mark").jsonObject, actual.mark)
                assertEquals(eye.jsonObject.number("dx")!!, actual.dx, 1e-9)
                assertEquals(eye.jsonObject.number("dy")!!, actual.dy, 1e-9)
            }
        }
    }

    private fun assertModel(context: String, e: JsonObject, volume: Volume) {
        fun number(name: String, expected: Double?, actual: Double?) {
            assertEquals("$name of $context", expected == null, actual == null)
            if (expected != null) assertEquals("$name of $context", expected, actual!!, 1e-9)
        }
        fun box(name: String, o: JsonObject, b: Bounds) {
            for ((k, v) in mapOf("minX" to b.minX, "minY" to b.minY, "maxX" to b.maxX, "maxY" to b.maxY)) number("$name.$k", o.number(k), v)
        }
        box("bounds", e.getValue("bounds").jsonObject, volume.bounds)
        assertEquals("eyePart of $context", e.getValue("eyePart").jsonPrimitive.int, volume.eyePart)
        number("eyeScale", e.number("eyeScale"), volume.eyeScale)
        e.list("eyeZ").forEachIndexed { i, z -> number("eyeZ", z.jsonPrimitive.double, volume.eyeZ[i]) }
        e.list("eyePlanes").map { it.jsonObject }.forEachIndexed { i, plane ->
            val actual = volume.eyePlanes[i]
            for ((k, v) in mapOf("x" to actual.x, "y" to actual.y, "z" to actual.z, "dzdx" to actual.dzdx, "dzdy" to actual.dzdy))
                number("eyePlanes.$k", plane.number(k), v)
        }
        assertEquals("parts of $context", e.list("parts").size, volume.parts.size)
        e.list("parts").map { it.jsonObject }.forEachIndexed { i, part ->
            val actual = volume.parts[i]
            assertEquals("kind of $context", part.text("kind")!!.uppercase().replace('-', '_'), actual.kind.name)
            box("part.bounds", part.getValue("bounds").jsonObject, actual.bounds)
            number("center.x", part.list("center")[0].jsonPrimitive.double, actual.center.x)
            number("center.y", part.list("center")[1].jsonPrimitive.double, actual.center.y)
            for ((k, v) in mapOf("thickness" to actual.thickness, "depth" to actual.depth, "rxy" to actual.rxy, "rz" to actual.rz, "axis" to actual.axis))
                number(k, part.number(k), v)
            val slices = actual.zSlices + actual.xSlices
            assertEquals("slices of $context", part.list("slices").size, slices.size)
            part.list("slices").forEachIndexed { k, slice ->
                val s = slices[k]
                listOf(s.at, s.inset, s.box.x, s.box.y, s.box.w, s.box.h).forEachIndexed { n, v -> number("slice $k[$n]", slice.jsonArray[n].jsonPrimitive.double, v) }
            }
            val b = actual.bounds
            val at = listOf(0.2, 0.35, 0.5, 0.65, 0.8)
            val heights = at.flatMap { v -> at.map { h -> actual.height(b.minX + h * (b.maxX - b.minX), b.minY + v * (b.maxY - b.minY)) } }
            part.list("heights").forEachIndexed { k, h -> number("height $k", h.jsonPrimitive.double, heights[k]) }
        }
    }

    @Test
    fun volumesMatchUpstream() {
        for (case in fixtures.list("volumes").map { it.jsonObject }) {
            val body = analyzeBody(definitions.getValue(case.text("id")!!).path)
            val volume = buildVolume(definitions.getValue(case.text("id")!!).eyes, case.number("depth")!! / DEFAULT_DEPTH, body)
            assertModel("${case.text("id")} at ${case.text("depth")}", case.getValue("model").jsonObject, volume)
        }
    }

    @Test
    fun outlineModelsMatchUpstream() {
        for (case in fixtures.list("outlines").map { it.jsonObject }) {
            val box = case.getValue("box").jsonObject
            val d = case.text("d")!!
            val model = OutlineModel.of(d, PathBox(box.number("x")!!, box.number("y")!!, box.number("w")!!, box.number("h")!!))
            val e = case["model"] as? JsonObject
            assertEquals(d, e == null, model == null)
            if (e == null || model == null) continue
            assertSameMarkup("key of $d", e.text("key")!!, model.key)
            for ((k, v) in mapOf("scale" to model.scale, "cx" to model.cx, "cy" to model.cy)) assertEquals("$k of $d", e.number(k)!!, v, 1e-9)
            assertModel(d, e.getValue("volume").jsonObject, model.volume)
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
