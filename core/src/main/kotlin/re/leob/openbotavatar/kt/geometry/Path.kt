package re.leob.openbotavatar.kt.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Restricted SVG path geometry: parser, normalizer and flattener. Accepts M L H V C S Q T A Z,
 * absolute and relative, and nothing else. Output is re-serialized from numbers, so model-written
 * text never reaches a renderer.
 */
internal sealed interface Segment {
    data class Move(val x: Double, val y: Double) : Segment
    data class Line(val x: Double, val y: Double) : Segment
    data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : Segment
    data class Quad(val x1: Double, val y1: Double, val x: Double, val y: Double) : Segment
    data class Arc(val rx: Double, val ry: Double, val rot: Double, val large: Boolean, val sweep: Boolean, val x: Double, val y: Double) : Segment
    data object Close : Segment
}

internal class PathError(message: String) : Exception(message)

private object PathLimits {
    const val MAX_CHARS = 6000
    const val MAX_SEGMENTS = 400
    const val MAX_SUBPATHS = 16
    const val COORD_MIN = -20.0
    const val COORD_MAX = 120.0
}

private val ARG_COUNT = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7)
private val UNSUPPORTED = Regex("[^MmLlHhVvCcSsQqTtAaZz0-9.,eE+\\-\\s]")
private val TOKEN = Regex("([MmLlHhVvCcSsQqTtAaZz])|([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)|([\\s,]+)")

private sealed interface Token {
    data class Command(val c: Char) : Token
    data class Number(val v: Double) : Token
}

private fun tokenize(d: String): List<Token> {
    UNSUPPORTED.find(d)?.let {
        throw PathError("unsupported character \"${it.value}\" (allowed commands: M L H V C S Q T A Z)")
    }
    val tokens = ArrayList<Token>()
    var i = 0
    while (i < d.length) {
        val m = TOKEN.matchAt(d, i)
            ?: throw PathError("cannot parse path near \"${d.substring(i, minOf(d.length, i + 12))}\"")
        val command = m.groups[1]
        val number = m.groups[2]
        if (command != null) tokens.add(Token.Command(command.value[0]))
        else if (number != null) tokens.add(Token.Number(number.value.toDouble()))
        i = m.range.last + 1
    }
    return tokens
}

internal fun parsePath(d: String): List<Segment> {
    if (d.isBlank()) throw PathError("path is empty")
    if (d.length > PathLimits.MAX_CHARS) throw PathError("path longer than ${PathLimits.MAX_CHARS} characters")
    val tokens = tokenize(d)
    val out = ArrayList<Segment>()
    var i = 0
    var cx = 0.0
    var cy = 0.0
    var sx = 0.0
    var sy = 0.0
    var prevC: Point? = null
    var prevQ: Point? = null
    var cmd: Char? = null
    var subpaths = 0
    var open = false

    while (i < tokens.size) {
        val t = tokens[i]
        if (t is Token.Command) {
            cmd = t.c
            i++
            if (cmd == 'Z' || cmd == 'z') {
                if (!open) throw PathError("Z without an open subpath")
                out.add(Segment.Close)
                cx = sx
                cy = sy
                prevC = null
                prevQ = null
                open = false
                continue
            }
        } else if (cmd == null) {
            throw PathError("path must start with M")
        } else if (cmd == 'Z' || cmd == 'z') {
            throw PathError("numbers after Z")
        }
        val current = cmd
        val up = current.uppercaseChar()
        val rel = current != up
        if (out.isEmpty() && up != 'M') throw PathError("path must start with M")
        val n = ARG_COUNT.getValue(up)
        val args = DoubleArray(n)
        for (k in 0 until n) {
            val v = tokens.getOrNull(i + k) as? Token.Number ?: throw PathError("command $current expects $n numbers")
            if (!v.v.isFinite()) throw PathError("non-finite number")
            args[k] = v.v
        }
        i += n
        val ox = if (rel) cx else 0.0
        val oy = if (rel) cy else 0.0
        var nextC: Point? = null
        var nextQ: Point? = null
        when (up) {
            'M' -> {
                if (open) out.add(Segment.Close)
                cx = args[0] + ox
                cy = args[1] + oy
                sx = cx
                sy = cy
                out.add(Segment.Move(cx, cy))
                subpaths++
                open = true
                cmd = if (rel) 'l' else 'L'
            }
            'L' -> {
                cx = args[0] + ox
                cy = args[1] + oy
                out.add(Segment.Line(cx, cy))
            }
            'H' -> {
                cx = args[0] + ox
                out.add(Segment.Line(cx, cy))
            }
            'V' -> {
                cy = args[0] + oy
                out.add(Segment.Line(cx, cy))
            }
            'C' -> {
                val s = Segment.Cubic(args[0] + ox, args[1] + oy, args[2] + ox, args[3] + oy, args[4] + ox, args[5] + oy)
                out.add(s)
                nextC = Point(s.x2, s.y2)
                cx = s.x
                cy = s.y
            }
            'S' -> {
                val x1 = prevC?.let { 2 * cx - it.x } ?: cx
                val y1 = prevC?.let { 2 * cy - it.y } ?: cy
                val s = Segment.Cubic(x1, y1, args[0] + ox, args[1] + oy, args[2] + ox, args[3] + oy)
                out.add(s)
                nextC = Point(s.x2, s.y2)
                cx = s.x
                cy = s.y
            }
            'Q' -> {
                val s = Segment.Quad(args[0] + ox, args[1] + oy, args[2] + ox, args[3] + oy)
                out.add(s)
                nextQ = Point(s.x1, s.y1)
                cx = s.x
                cy = s.y
            }
            'T' -> {
                val x1 = prevQ?.let { 2 * cx - it.x } ?: cx
                val y1 = prevQ?.let { 2 * cy - it.y } ?: cy
                val s = Segment.Quad(x1, y1, args[0] + ox, args[1] + oy)
                out.add(s)
                nextQ = Point(x1, y1)
                cx = s.x
                cy = s.y
            }
            'A' -> {
                val (rx, ry) = args
                val la = args[3]
                val sw = args[4]
                if (rx < 0 || ry < 0) throw PathError("arc radii must be non-negative")
                if ((la != 0.0 && la != 1.0) || (sw != 0.0 && sw != 1.0)) {
                    throw PathError("arc flags must be 0 or 1, written as separate numbers")
                }
                cx = args[5] + ox
                cy = args[6] + oy
                out.add(Segment.Arc(rx, ry, args[2], la == 1.0, sw == 1.0, cx, cy))
            }
        }
        prevC = nextC
        prevQ = nextQ
        if (out.size > PathLimits.MAX_SEGMENTS) throw PathError("more than ${PathLimits.MAX_SEGMENTS} segments")
    }
    if (open) out.add(Segment.Close)
    if (subpaths > PathLimits.MAX_SUBPATHS) throw PathError("more than ${PathLimits.MAX_SUBPATHS} subpaths")
    for (s in out) for (v in coordinates(s)) {
        if (v < PathLimits.COORD_MIN || v > PathLimits.COORD_MAX) {
            throw PathError("coordinate ${plain(v)} far outside the 0..100 viewBox")
        }
    }
    return out
}

private fun coordinates(s: Segment): DoubleArray = when (s) {
    is Segment.Move -> doubleArrayOf(s.x, s.y)
    is Segment.Line -> doubleArrayOf(s.x, s.y)
    is Segment.Cubic -> doubleArrayOf(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
    is Segment.Quad -> doubleArrayOf(s.x1, s.y1, s.x, s.y)
    is Segment.Arc -> doubleArrayOf(s.rx, s.ry, s.x, s.y)
    Segment.Close -> DoubleArray(0)
}

private fun fmt(n: Double): String = fixed(n, 2)

internal fun serializePath(segments: List<Segment>): String = buildString {
    for (s in segments) {
        when (s) {
            is Segment.Move -> append("M${fmt(s.x)} ${fmt(s.y)}")
            is Segment.Line -> append("L${fmt(s.x)} ${fmt(s.y)}")
            is Segment.Cubic -> append("C${fmt(s.x1)} ${fmt(s.y1)} ${fmt(s.x2)} ${fmt(s.y2)} ${fmt(s.x)} ${fmt(s.y)}")
            is Segment.Quad -> append("Q${fmt(s.x1)} ${fmt(s.y1)} ${fmt(s.x)} ${fmt(s.y)}")
            is Segment.Arc -> append(
                "A${fmt(s.rx)} ${fmt(s.ry)} ${fmt(s.rot)} ${if (s.large) 1 else 0} ${if (s.sweep) 1 else 0} ${fmt(s.x)} ${fmt(s.y)}",
            )
            Segment.Close -> append('Z')
        }
    }
}

/** Endpoint arc to sampled points (SVG implementation notes, F.6.5). */
private fun sampleArc(x0: Double, y0: Double, s: Segment.Arc, out: MutableList<Point>) {
    var rx = s.rx
    var ry = s.ry
    val x = s.x
    val y = s.y
    if (rx == 0.0 || ry == 0.0 || (x0 == x && y0 == y)) {
        out.add(Point(x, y))
        return
    }
    val phi = (s.rot * PI) / 180
    val cos = cos(phi)
    val sin = sin(phi)
    val dx = (x0 - x) / 2
    val dy = (y0 - y) / 2
    val x1p = cos * dx + sin * dy
    val y1p = -sin * dx + cos * dy
    val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if (lambda > 1) {
        val k = sqrt(lambda)
        rx *= k
        ry *= k
    }
    val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    var coef = sqrt(max(0.0, num / den))
    if (s.large == s.sweep) coef = -coef
    val cxp = (coef * rx * y1p) / ry
    val cyp = (-coef * ry * x1p) / rx
    val cx = cos * cxp - sin * cyp + (x0 + x) / 2
    val cy = sin * cxp + cos * cyp + (y0 + y) / 2
    fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if (!s.sweep && dt > 0) dt -= 2 * PI
    if (s.sweep && dt < 0) dt += 2 * PI
    val steps = max(4, ceil((abs(dt) / (PI / 2)) * 6).toInt())
    for (k in 1..steps) {
        val t = t1 + (dt * k) / steps
        val ex = rx * cos(t)
        val ey = ry * sin(t)
        out.add(Point(cos * ex - sin * ey + cx, sin * ex + cos * ey + cy))
    }
}

internal fun flattenPath(segments: List<Segment>): Polygons {
    val polys = ArrayList<List<Point>>()
    var cur = ArrayList<Point>()
    var px = 0.0
    var py = 0.0
    for (s in segments) {
        when (s) {
            is Segment.Move -> {
                if (cur.isNotEmpty()) polys.add(cur)
                cur = arrayListOf(Point(s.x, s.y))
                px = s.x
                py = s.y
            }
            is Segment.Line -> {
                cur.add(Point(s.x, s.y))
                px = s.x
                py = s.y
            }
            is Segment.Cubic -> {
                val n = 12
                for (k in 1..n) {
                    val t = k.toDouble() / n
                    val u = 1 - t
                    cur.add(
                        Point(
                            u * u * u * px + 3 * u * u * t * s.x1 + 3 * u * t * t * s.x2 + t * t * t * s.x,
                            u * u * u * py + 3 * u * u * t * s.y1 + 3 * u * t * t * s.y2 + t * t * t * s.y,
                        ),
                    )
                }
                px = s.x
                py = s.y
            }
            is Segment.Quad -> {
                val n = 8
                for (k in 1..n) {
                    val t = k.toDouble() / n
                    val u = 1 - t
                    cur.add(Point(u * u * px + 2 * u * t * s.x1 + t * t * s.x, u * u * py + 2 * u * t * s.y1 + t * t * s.y))
                }
                px = s.x
                py = s.y
            }
            is Segment.Arc -> {
                sampleArc(px, py, s, cur)
                px = s.x
                py = s.y
            }
            Segment.Close -> {
                polys.add(cur)
                px = cur[0].x
                py = cur[0].y
                cur = ArrayList()
            }
        }
    }
    if (cur.isNotEmpty()) polys.add(cur)
    return polys.filter { it.size >= 3 }
}

internal fun splitSubpaths(segments: List<Segment>): List<List<Segment>> {
    val out = ArrayList<MutableList<Segment>>()
    for (s in segments) {
        if (s is Segment.Move) out.add(ArrayList())
        out.lastOrNull()?.add(s)
    }
    return out
}
