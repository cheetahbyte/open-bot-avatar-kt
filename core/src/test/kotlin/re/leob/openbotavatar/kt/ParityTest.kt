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

    private fun moodOf(name: String?) = when (name) {
        "thinking" -> AvatarMood.THINKING
        "happy" -> AvatarMood.HAPPY
        else -> AvatarMood.IDLE
    }

    private fun stateOf(e: JsonElement): MoodState {
        val o = e as? JsonObject ?: return MoodState(moodOf(e.jsonPrimitive.content))
        return MoodState(moodOf(o.text("motion")), blendOf(o["blend"]))
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
        mood = moodOf(o.text("motion")),
        moodBlend = blendOf(o["motionBlend"]),
        reducedMotion = o["reducedMotion"]?.jsonPrimitive?.booleanOrNull == true,
    )

    private val number = Regex("-?\\d+(?:\\.\\d+)?")

    private fun lastDigit(token: String) = 10.0.pow(-token.substringAfter('.', "").length)

    /** Equal text, except that numbers may differ in their last rounded digit: libm results vary by an ulp. */
    private fun assertSameMarkup(context: String, expected: String, actual: String) {
        val a = number.findAll(expected).map { it.value }.toList()
        val b = number.findAll(actual).map { it.value }.toList()
        assertEquals("$context: markup", expected.replace(number, "#"), actual.replace(number, "#"))
        assertEquals("$context: number count", a.size, b.size)
        for (i in a.indices) {
            val digit = minOf(0.1, lastDigit(a[i]), lastDigit(b[i]))
            assertTrue("$context: number $i, ${a[i]} vs ${b[i]}", abs(a[i].toDouble() - b[i].toDouble()) <= digit * 1.001)
        }
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
                assertTrue(input.toString(), actual is ValidationResult.Valid)
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
                    mood = moodOf(i.text("motion")),
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
            )
            for ((name, value) in fields) assertEquals("$name in $i", e.number(name)!!, value, 1e-9)
            assertEquals(e.text("eyeShape"), pose.eyeShape.name.lowercase())
        }
    }

    @Test
    fun animatorMatchesUpstream() {
        val animator = AvatarAnimator()
        for (case in fixtures.list("animator").map { it.jsonObject }) {
            val i = case.getValue("input").jsonObject
            val target = (i["target"] as? JsonObject)?.let { LookDirection(it.number("x")!!, it.number("y")!!) }
            val step = animator.step(
                now = i.number("now")!!,
                mood = moodOf(i.text("motion")),
                target = target,
                hold = i.getValue("hold").jsonPrimitive.boolean,
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
            assertEquals(blendOf(e["motionBlend"])?.t, step.moodBlend?.t)
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
