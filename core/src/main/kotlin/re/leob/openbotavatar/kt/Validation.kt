package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.PathError
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.filledArea
import re.leob.openbotavatar.kt.geometry.flattenPath
import re.leob.openbotavatar.kt.geometry.insideRow
import re.leob.openbotavatar.kt.geometry.isInside
import re.leob.openbotavatar.kt.geometry.parsePath
import re.leob.openbotavatar.kt.geometry.sampleRange
import re.leob.openbotavatar.kt.geometry.serializePath
import re.leob.openbotavatar.kt.geometry.splitSubpaths
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Neutral eye size at scale 1, viewBox units. */
internal const val EYE_W = 4.0
internal const val EYE_H = 11.0

/** Hard limits; the contract's soft target for the body is 8..92. */
private const val BODY_MIN = 6.0
private const val BODY_MAX = 94.0
private const val EYE_MARGIN = 1.2

/** Max gaze travel of the eyes at scale 1; small, so the enlarged eyes stay well inside the face. */
internal const val GAZE_X = 1.5
internal const val GAZE_Y = 1.1

/** Happy-eye arc, as fractions of the drawn eye size. */
internal object HappyArc {
    const val HALF_WIDTH = 0.72
    const val BASE = 0.1
    const val PEAK = 0.42
    const val STROKE = 0.6
}

/** Gap the renderer keeps between every drawn eye shape and the outline while the eyes look around. */
private const val EYE_CLEARANCE = 1.5

private val TOP_KEYS = listOf("version", "id", "name", "body", "eyes")
private val EYE_KEYS = listOf("x", "y", "spacing", "scale")
private val ID = Regex("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
private val NAME_FORBIDDEN = Regex("[\\u0000-\\u001f<>]")

public sealed interface ValidationResult {
    /** [value] is the normalized definition: only parsed numbers, re-serialized. */
    public data class Valid(val value: AvatarDefinition) : ValidationResult

    public data class Invalid(val errors: List<String>) : ValidationResult
}

internal class BodySubpath(
    val d: String,
    val polys: Polygons,
    /** True when the subpath cuts a hole rather than adding fill. */
    val hole: Boolean,
    /** A point inside the subpath's own fill; for holes, inside the cut-out. */
    val probe: Point?,
)

internal class BodyAnalysis(
    val polys: Polygons,
    val bounds: Bounds,
    val area: Int,
    val subpaths: List<BodySubpath>,
)

internal data class GazeRange(val left: Double, val right: Double, val up: Double, val down: Double)

/** Points on the outline of an eye pill expanded by [margin]. */
private fun eyeOutline(cx: Double, cy: Double, scale: Double, margin: Double): List<Point> {
    val hw = (EYE_W * scale) / 2 + margin
    val hh = (EYE_H * scale) / 2 + margin
    val pts = ArrayList<Point>()
    val straight = hh - hw
    for (k in 0 until 16) {
        val a = (k.toDouble() / 16) * PI * 2
        pts.add(Point(cx + cos(a) * hw, cy + sin(a) * hw + sign(sin(a)) * max(0.0, straight)))
    }
    for (f in doubleArrayOf(-0.5, 0.0, 0.5)) {
        pts.add(Point(cx - hw, cy + f * straight))
        pts.add(Point(cx + hw, cy + f * straight))
    }
    pts.add(Point(cx, cy))
    return pts
}

/** Outline points of every shape the renderer may draw for one eye, expanded by [margin]. */
private fun drawnEyeOutline(cx: Double, cy: Double, scale: Double, margin: Double): List<Point> {
    val pts = ArrayList(eyeOutline(cx, cy, scale, margin))
    val w = EYE_W * scale
    val h = EYE_H * scale
    val x0 = cx - HappyArc.HALF_WIDTH * w
    val x1 = cx + HappyArc.HALF_WIDTH * w
    val yb = cy + HappyArc.BASE * h
    val yc = cy - HappyArc.PEAK * h
    val r = (HappyArc.STROKE * w) / 2 + margin
    for (i in 0..8) {
        val t = i.toDouble() / 8
        val m = 1 - t
        val x = m * m * x0 + 2 * m * t * cx + t * t * x1
        val y = (m * m + t * t) * yb + 2 * m * t * yc
        for (k in 0 until 8) {
            val a = (k.toDouble() / 8) * PI * 2
            pts.add(Point(x + cos(a) * r, y + sin(a) * r))
        }
    }
    return pts
}

internal fun eyeCenters(eyes: AvatarEyes): List<Point> =
    listOf(Point(eyes.x - eyes.spacing / 2, eyes.y), Point(eyes.x + eyes.spacing / 2, eyes.y))

private fun eyesFit(polys: Polygons, eyes: AvatarEyes, margin: Double): Point? {
    for (c in eyeCenters(eyes)) {
        for (p in eyeOutline(c.x, c.y, eyes.scale, margin)) if (!isInside(polys, p.x, p.y)) return p
    }
    return null
}

/** The first point of everything the renderer draws (pill and wider happy arc) outside the body. */
internal fun drawnEyesMiss(polys: Polygons, eyes: AvatarEyes, dx: Double, dy: Double, margin: Double): Point? {
    for (c in eyeCenters(eyes)) {
        for (p in drawnEyeOutline(c.x + dx, c.y + dy, eyes.scale, margin)) if (!isInside(polys, p.x, p.y)) return p
    }
    return null
}

/** How far (0..1 of the gaze travel) the drawn eyes can go each way and keep their clearance. */
internal fun gazeRange(polys: Polygons, eyes: AvatarEyes): GazeRange {
    fun reach(ux: Double, uy: Double): Double {
        for (k in doubleArrayOf(1.0, 0.8, 0.6, 0.4, 0.2)) {
            val dx = ux * k * GAZE_X * eyes.scale
            val dy = uy * k * GAZE_Y * eyes.scale
            if (drawnEyesMiss(polys, eyes, dx, dy, EYE_CLEARANCE) == null) return k
        }
        return 0.0
    }
    return GazeRange(reach(-1.0, 0.0), reach(1.0, 0.0), reach(0.0, -1.0), reach(0.0, 1.0))
}

internal fun analyzeBody(path: String): BodyAnalysis {
    val segments = parsePath(path)
    val polys = flattenPath(segments)
    val b = bounds(polys)
    val subpaths = ArrayList<BodySubpath>()
    for (sub in splitSubpaths(segments)) {
        val own = flattenPath(sub)
        if (own.isEmpty()) continue
        val ob = bounds(own)
        var inside = 0
        var cut = 0
        var probe: Point? = null
        var cutProbe: Point? = null
        // Small subpaths (buttons, sockets) need a fine grid; big ones classify fine on a coarser one.
        val step = if ((ob.maxX - ob.minX) * (ob.maxY - ob.minY) < 900) 0.5 else 1.0
        val alone = own.size == polys.size
        val xs = sampleRange(floor(ob.minX) + step / 2, ob.maxX, step)
        val mine = BooleanArray(xs.size)
        val all = BooleanArray(xs.size)
        for (y in sampleRange(floor(ob.minY) + step / 2, ob.maxY, step)) {
            insideRow(own, y, xs, mine)
            if (!alone) insideRow(polys, y, xs, all)
            for (k in xs.indices) {
                if (!mine[k]) continue
                inside++
                if (probe == null) probe = Point(xs[k], y)
                if (!alone && !all[k]) {
                    cut++
                    if (cutProbe == null) cutProbe = Point(xs[k], y)
                }
            }
        }
        // A subpath that is mostly empty under the full fill rule is a cut-out.
        val hole = inside > 0 && cut.toDouble() / inside > 0.6
        subpaths.add(BodySubpath(serializePath(sub), own, hole, if (hole) cutProbe else probe))
    }
    return BodyAnalysis(polys, b, filledArea(polys, b), subpaths)
}

private fun r1(n: Double) = fixed(n, 1)

private fun JsonElement?.finiteNumber(): Double? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Check a parsed definition against the contract. Render imported definitions only after this. */
public fun validateAvatar(input: JsonElement): ValidationResult {
    if (input !is JsonObject) return ValidationResult.Invalid(listOf("definition must be a JSON object"))
    val errors = ArrayList<String>()

    for (k in input.keys) if (k !in TOP_KEYS) errors.add("unknown field \"$k\" (allowed: ${TOP_KEYS.joinToString(", ")})")
    if (input["version"].finiteNumber() != AVATAR_CONTRACT_VERSION.toDouble()) errors.add("version must be 1")

    val id = input["id"].string()
    if (id == null || !ID.matches(id)) errors.add("id must be a lowercase slug of 1-40 chars [a-z0-9-]")
    val name = input["name"].string()?.trim().orEmpty()
    if (name.isEmpty() || name.length > 40 || NAME_FORBIDDEN.containsMatchIn(name)) {
        errors.add("name must be 1-40 printable characters without < or >")
    }

    var path: String? = null
    val body = input["body"]
    if (body !is JsonObject) {
        errors.add("body must be an object { \"path\": \"...\" }")
    } else {
        for (k in body.keys) if (k != "path") errors.add("unknown field \"body.$k\" (only \"path\" is allowed)")
        path = body["path"].string()
        if (path == null) errors.add("body.path must be a string")
    }

    var eyes: AvatarEyes? = null
    val rawEyes = input["eyes"]
    if (rawEyes !is JsonObject) {
        errors.add("eyes must be an object { x, y, spacing, scale }")
    } else {
        for (k in rawEyes.keys) if (k !in EYE_KEYS) errors.add("unknown field \"eyes.$k\" (allowed: ${EYE_KEYS.joinToString(", ")})")
        val bad = EYE_KEYS.filter { rawEyes[it].finiteNumber() == null }
        if (bad.isNotEmpty()) {
            errors.add("eyes.${bad.joinToString(", eyes.")} must be finite numbers")
        } else {
            val (x, y, spacing, scale) = EYE_KEYS.map { rawEyes.getValue(it).finiteNumber()!! }
            eyes = AvatarEyes(x, y, spacing, scale)
            if (scale < 0.6 || scale > 1.8) errors.add("eyes.scale ${plain(scale)} out of range 0.6..1.8")
            val minSpacing = EYE_W * scale * 1.75
            if (spacing < minSpacing || spacing > 40) {
                errors.add("eyes.spacing ${plain(spacing)} out of range ${r1(minSpacing)}..40 for scale ${plain(scale)}")
            }
        }
    }

    var normalized = ""
    var analysis: BodyAnalysis? = null
    if (path != null) {
        try {
            normalized = serializePath(parsePath(path))
            analysis = analyzeBody(normalized)
        } catch (error: PathError) {
            errors.add("body.path: ${error.message}")
        }
    }

    if (analysis != null) {
        val b = analysis.bounds
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
        if (analysis.area < 600) errors.add("body fill area ${analysis.area} is too thin to carry eyes; must be at least 600 units²")
        if (eyes != null) {
            eyesFit(analysis.polys, eyes, EYE_MARGIN)?.let { miss ->
                errors.add(
                    "eyes must sit inside the body with ${plain(EYE_MARGIN)} units margin; point (${r1(miss.x)}, ${r1(miss.y)}) " +
                        "is outside or in a hole. Move eyes.x/y toward the widest solid area, reduce spacing or scale.",
                )
            }
        }
    }

    if (errors.isNotEmpty() || eyes == null || id == null) return ValidationResult.Invalid(errors)
    return ValidationResult.Valid(AvatarDefinition(id, name, normalized, eyes))
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
