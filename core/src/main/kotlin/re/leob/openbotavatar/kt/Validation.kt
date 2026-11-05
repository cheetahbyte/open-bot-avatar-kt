package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.PathError
import re.leob.openbotavatar.kt.geometry.fixed
import re.leob.openbotavatar.kt.geometry.parsePath
import re.leob.openbotavatar.kt.geometry.plain
import re.leob.openbotavatar.kt.geometry.serializePath
import re.leob.openbotavatar.kt.volume.BodyAnalysis
import re.leob.openbotavatar.kt.volume.EYE_W
import re.leob.openbotavatar.kt.volume.analyzeBody
import re.leob.openbotavatar.kt.volume.eyesFit
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Hard limits; the contract's soft target for the body is 8..92. */
private const val BODY_MIN = 6.0
private const val BODY_MAX = 94.0
private const val EYE_MARGIN = 1.2

private val TOP_KEYS = listOf("version", "id", "name", "body", "eyes")
private val EYE_KEYS = listOf("x", "y", "spacing", "scale")
private val ID = Regex("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
private val NAME_FORBIDDEN = Regex("[\\u0000-\\u001f<>]")

public sealed interface ValidationResult {
    /** [value] is the normalized definition: only parsed numbers, re-serialized. */
    public data class Valid(val value: AvatarDefinition) : ValidationResult

    public data class Invalid(val errors: List<String>) : ValidationResult
}

private fun r1(n: Double) = fixed(n, 1)

private fun JsonElement?.finiteNumber(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun readEyes(raw: JsonElement?, errors: MutableList<String>): AvatarEyes? {
    if (raw !is JsonObject) {
        errors.add("eyes must be an object { x, y, spacing, scale }")
        return null
    }
    for (k in raw.keys) if (k !in EYE_KEYS) errors.add("unknown field \"eyes.$k\" (allowed: ${EYE_KEYS.joinToString(", ")})")
    val bad = EYE_KEYS.filter { raw[it].finiteNumber() == null }
    if (bad.isNotEmpty()) {
        errors.add("eyes.${bad.joinToString(", eyes.")} must be finite numbers")
        return null
    }
    val (x, y, spacing, scale) = EYE_KEYS.map { raw.getValue(it).finiteNumber()!! }
    if (scale < 0.6 || scale > 1.8) errors.add("eyes.scale ${plain(scale)} out of range 0.6..1.8")
    val minSpacing = EYE_W * scale * 1.75
    if (spacing < minSpacing || spacing > 40) {
        errors.add("eyes.spacing ${plain(spacing)} out of range ${r1(minSpacing)}..40 for scale ${plain(scale)}")
    }
    return AvatarEyes(x, y, spacing, scale)
}

private fun readPath(raw: JsonElement?, errors: MutableList<String>): String? {
    if (raw !is JsonObject) {
        errors.add("body must be an object { \"path\": \"...\" }")
        return null
    }
    for (k in raw.keys) if (k != "path") errors.add("unknown field \"body.$k\" (only \"path\" is allowed)")
    return raw["path"].string().also { if (it == null) errors.add("body.path must be a string") }
}

private fun checkBody(body: BodyAnalysis, eyes: AvatarEyes?, errors: MutableList<String>) {
    val b = body.bounds
    val out = ArrayList<String>()
    if (b.minX < BODY_MIN) out.add("left edge x=${r1(b.minX)}")
    if (b.minY < BODY_MIN) out.add("top edge y=${r1(b.minY)}")
    if (b.maxX > BODY_MAX) out.add("right edge x=${r1(b.maxX)}")
    if (b.maxY > BODY_MAX) out.add("bottom edge y=${r1(b.maxY)}")
    if (out.isNotEmpty()) errors.add("body.path leaves the safe area (${out.joinToString(", ")}); keep geometry within 8..92")
    val w = b.maxX - b.minX
    val h = b.maxY - b.minY
    if (max(w, h) < 40 || min(w, h) < 14) {
        errors.add("body is too small (${r1(w)}x${r1(h)}); use at least 40 units on the long side and 14 on the short side")
    }
    if (body.area < 600) errors.add("body fill area ${body.area} is too thin to carry eyes; must be at least 600 units²")
    if (eyes == null) return
    eyesFit(body.polys, eyes, EYE_MARGIN)?.let { miss ->
        errors.add(
            "eyes must sit inside the body with ${plain(EYE_MARGIN)} units margin; point (${r1(miss.x)}, ${r1(miss.y)}) " +
                "is outside or in a hole. Move eyes.x/y toward the widest solid area, reduce spacing or scale.",
        )
    }
}

internal class CheckedAvatar(val definition: AvatarDefinition, val body: BodyAnalysis)

internal fun checkAvatar(input: JsonElement, errors: MutableList<String>): CheckedAvatar? {
    if (input !is JsonObject) {
        errors.add("definition must be a JSON object")
        return null
    }
    for (k in input.keys) if (k !in TOP_KEYS) errors.add("unknown field \"$k\" (allowed: ${TOP_KEYS.joinToString(", ")})")
    if (input["version"].finiteNumber() != AVATAR_CONTRACT_VERSION.toDouble()) errors.add("version must be $AVATAR_CONTRACT_VERSION")

    val id = input["id"].string()
    if (id == null || !ID.matches(id)) errors.add("id must be a lowercase slug of 1-40 chars [a-z0-9-]")
    val name = input["name"].string()?.trim().orEmpty()
    if (name.isEmpty() || name.length > 40 || NAME_FORBIDDEN.containsMatchIn(name)) {
        errors.add("name must be 1-40 printable characters without < or >")
    }

    val path = readPath(input["body"], errors)
    val eyes = readEyes(input["eyes"], errors)

    var normalized = ""
    var body: BodyAnalysis? = null
    if (path != null) {
        try {
            normalized = serializePath(parsePath(path))
            body = analyzeBody(normalized)
        } catch (error: PathError) {
            errors.add("body.path: ${error.message}")
        }
    }
    if (body != null) checkBody(body, eyes, errors)

    if (errors.isNotEmpty() || id == null || eyes == null || body == null) return null
    return CheckedAvatar(AvatarDefinition(id, name, normalized, eyes), body)
}

/** Check a parsed definition against the contract. Render imported definitions only after this. */
public fun validateAvatar(input: JsonElement): ValidationResult {
    val errors = ArrayList<String>()
    val checked = checkAvatar(input, errors)
    return if (checked != null) ValidationResult.Valid(checked.definition) else ValidationResult.Invalid(errors)
}

/** Check contract JSON text. Unlike upstream, a syntax error is an [ValidationResult.Invalid], not a throw. */
public fun validateAvatar(json: String): ValidationResult {
    val parsed = try {
        Json.parseToJsonElement(json)
    } catch (error: SerializationException) {
        return ValidationResult.Invalid(listOf("definition is not valid JSON: ${error.message}"))
    }
    return validateAvatar(parsed)
}

/** Check a definition built in code. */
public fun validateAvatar(definition: AvatarDefinition): ValidationResult = validateAvatar(definition.toJson())
