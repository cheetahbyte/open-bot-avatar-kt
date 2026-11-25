package re.leob.openbotavatar.kt.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Row-major samples of a scalar field; the outermost ring should be below every level. */
internal class Field(
    val values: DoubleArray,
    val nx: Int,
    val ny: Int,
    val x0: Double,
    val y0: Double,
    val step: Double,
)

/** Cells per run of a row that a caller of [Contours.outlines] can rule out at once (see [RunRange]). */
internal const val RUN = 8

/** Runs in a row of cells of a field [nx] samples wide. */
internal fun runsOf(nx: Int): Int = ceil((nx - 1) / RUN.toDouble()).toInt()

/**
 * Smallest and largest sample around each run of cells: over both rows of samples the run's cells
 * touch. A run whose values all lie on one side of a level has no crossing at that level.
 */
internal class RunRange(values: DoubleArray, nx: Int, ny: Int) {
    val min: DoubleArray
    val max: DoubleArray

    init {
        val runs = runsOf(nx)
        val lo = DoubleArray(runs * ny)
        val hi = DoubleArray(runs * ny)
        for (j in 0 until ny) for (r in 0 until runs) {
            var a = Double.POSITIVE_INFINITY
            var b = Double.NEGATIVE_INFINITY
            for (k in j * nx + r * RUN..j * nx + min(nx - 1, (r + 1) * RUN)) {
                val v = values[k]
                if (v < a) a = v
                if (v > b) b = v
            }
            lo[j * runs + r] = a
            hi[j * runs + r] = b
        }
        val cells = runs * max(0, ny - 1)
        min = DoubleArray(cells) { min(lo[it], lo[it + runs]) }
        max = DoubleArray(cells) { max(hi[it], hi[it + runs]) }
    }
}

/**
 * A comparison that plain products decide by more than this, relative, comes out the same through
 * [hypot2] or atan2: both are accurate to many orders below it. Closer calls are left to the
 * function itself, so the decision is always the one it would have made.
 */
private const val CLEAR = 1e-9

/** Traces and simplifies outlines, keeping its scratch between calls. One per build, on one thread. */
internal class Contours {
    // Per cell edge its two links (-1: none) and whether a loop has passed it. Every trace leaves
    // them as it found them.
    private var linkA = IntArray(0)
    private var linkB = IntArray(0)
    private var passed = BooleanArray(0)

    /** Linked edges in the order they were first linked; each has at most two links, one per cell. */
    private var order = IntArray(256)
    private var linked = 0

    // The traced loops' points, x and y in turn, one loop after another, and where each loop ends.
    private var traced = DoubleArray(1024)
    private var ends = IntArray(16)

    // Which points stay in a simplified loop, and the chains still to split.
    private var kept = BooleanArray(256)
    private var chains = IntArray(64)

    /**
     * Closed loops where the field crosses [level] (inside is a value at or above it), each
     * simplified to [tolerance], without those left with under three points. [flat] marks runs of
     * cells (RUN per row, row-major) that are known not to cross it; their samples are not read.
     */
    fun outlines(field: Field, level: Double, tolerance: Double, flat: BooleanArray? = null): List<List<Point>> {
        val loops = ArrayList<List<Point>>()
        var start = 0
        for (k in 0 until trace(field, level, flat)) {
            val end = ends[k]
            val points = (end - start) / 2
            if (points >= 3) {
                val loop = simplified(traced, start, points, tolerance)
                if (loop.size >= 3) loops.add(loop)
            }
            start = end
        }
        return loops
    }

    /** Ramer-Douglas-Peucker on a closed loop of [n] points at `xy[start]`, x and y in turn. */
    fun simplified(xy: DoubleArray, start: Int, n: Int, tolerance: Double): List<Point> {
        val all = n <= 8
        if (!all) simplify(xy, start, n, tolerance)
        val loop = ArrayList<Point>(if (all) n else 32)
        for (k in 0 until n) if (all || kept[k]) loop.add(Point(xy[start + 2 * k], xy[start + 2 * k + 1]))
        return loop
    }

    private fun join(p: Int, q: Int) {
        if (linkA[p] < 0) {
            if (linked == order.size) order = order.copyOf(2 * linked)
            order[linked++] = p
            linkA[p] = q
        } else {
            linkB[p] = q
        }
    }

    private fun link(p: Int, q: Int) {
        join(p, q)
        join(q, p)
    }

    /** Marching squares into [traced] and [ends]; returns how many loops, some with fewer than three points. */
    private fun trace(field: Field, level: Double, flat: BooleanArray?): Int {
        val values = field.values
        val nx = field.nx
        val ny = field.ny
        // Edge keys: horizontal edge (i,j)-(i+1,j) is 2*(j*nx+i), vertical (i,j)-(i,j+1) is 2*(j*nx+i)+1.
        if (linkA.size < 2 * nx * ny) {
            linkA = IntArray(2 * nx * ny) { -1 }
            linkB = IntArray(2 * nx * ny) { -1 }
            passed = BooleanArray(2 * nx * ny)
        }
        linked = 0
        val runs = runsOf(nx)
        for (j in 0 until ny - 1) {
            val row = j * nx
            for (r in 0 until runs) {
                if (flat != null && flat[j * runs + r]) continue
                var tl = values[row + r * RUN] - level >= 0
                var bl = values[row + nx + r * RUN] - level >= 0
                for (i in r * RUN until min(nx - 1, (r + 1) * RUN)) {
                    val tr = values[row + i + 1] - level >= 0
                    val br = values[row + nx + i + 1] - level >= 0
                    if (tl != tr || tr != br || br != bl) {
                        val top = 2 * (row + i)
                        val bottom = 2 * (row + nx + i)
                        val left = top + 1
                        val right = top + 3
                        if (tl == br && tr == bl) {
                            val center = ((values[row + i] - level) + (values[row + i + 1] - level) +
                                (values[row + nx + i + 1] - level) + (values[row + nx + i] - level)) / 4 >= 0
                            // Diagonal corners: the center decides whether they connect through the cell.
                            if (tl == center) {
                                link(top, right)
                                link(bottom, left)
                            } else {
                                link(left, top)
                                link(right, bottom)
                            }
                        } else if (tl != tr) {
                            link(top, if (tr != br) right else if (br != bl) bottom else left)
                        } else if (tr != br) {
                            link(right, if (br != bl) bottom else left)
                        } else {
                            link(bottom, left)
                        }
                    }
                    tl = tr
                    bl = br
                }
            }
        }

        // Every linked edge is passed once, so its crossing is one point of one loop.
        if (traced.size < 2 * linked) traced = DoubleArray(4 * linked)
        var n = 0
        var loops = 0
        for (k in 0 until linked) {
            if (passed[order[k]]) continue
            var prev = -1
            var cur = order[k]
            while (!passed[cur]) {
                passed[cur] = true
                val idx = cur shr 1
                val i = idx % nx
                val j = (idx - i) / nx
                val v = values[idx] - level
                if (cur and 1 != 0) {
                    val t = v / (v - (values[idx + nx] - level))
                    traced[n++] = field.x0 + i * field.step
                    traced[n++] = field.y0 + (j + t) * field.step
                } else {
                    val t = v / (v - (values[idx + 1] - level))
                    traced[n++] = field.x0 + (i + t) * field.step
                    traced[n++] = field.y0 + j * field.step
                }
                // The first link that does not lead back.
                var next = linkA[cur]
                if (next == prev || passed[next]) {
                    next = linkB[cur]
                    if (next < 0 || next == prev || passed[next]) break
                }
                prev = cur
                cur = next
            }
            if (loops == ends.size) ends = ends.copyOf(2 * loops)
            ends[loops++] = n
        }
        for (k in 0 until linked) {
            val edge = order[k]
            linkA[edge] = -1
            linkB[edge] = -1
            passed[edge] = false
        }
        return loops
    }

    /** Flags in [kept] the points of a closed loop that stay. */
    private fun simplify(xy: DoubleArray, start: Int, n: Int, tolerance: Double) {
        if (kept.size < n) kept = BooleanArray(2 * n)
        kept.fill(false, 0, n)
        // Split at the point farthest from the first so the closed loop becomes two open chains: the
        // first point with the largest norm. A single clear winner by squares is that point.
        val x0 = xy[start]
        val y0 = xy[start + 1]
        var far = 0
        var top = -1.0
        var next = -1.0
        for (k in 1 until n) {
            val dx = xy[start + 2 * k] - x0
            val dy = xy[start + 2 * k + 1] - y0
            val d = dx * dx + dy * dy
            if (d > top) {
                next = top
                top = d
                far = k
            } else if (d > next) {
                next = d
            }
        }
        if (next >= top * (1 - CLEAR)) {
            var best = -1.0
            far = 0
            for (k in 1 until n) {
                val d = hypot2(xy[start + 2 * k] - x0, xy[start + 2 * k + 1] - y0)
                if (d > best) {
                    best = d
                    far = k
                }
            }
        }
        kept[0] = true
        kept[far] = true
        var open = 0
        chains[open++] = 0
        chains[open++] = far
        chains[open++] = far
        chains[open++] = n
        while (open > 0) {
            val e = chains[--open]
            val s = chains[--open]
            val idx = farthest(xy, start, n, s, e, tolerance)
            if (idx >= 0) {
                kept[idx] = true
                if (open + 4 > chains.size) chains = chains.copyOf(2 * chains.size)
                chains[open++] = s
                chains[open++] = idx
                chains[open++] = idx
                chains[open++] = e
            }
        }
    }

    /**
     * The first point between [s] and [e] that is farthest from the line through them (from [s], if
     * they coincide), if that is over the tolerance, else -1. Each distance is a cross product over
     * the line's length, so the largest product names the point, if it is clear of the next largest
     * and of the tolerance.
     */
    private fun farthest(xy: DoubleArray, start: Int, n: Int, s: Int, e: Int, tolerance: Double): Int {
        val ax = xy[start + 2 * s]
        val ay = xy[start + 2 * s + 1]
        val dx = xy[start + 2 * (e % n)] - ax
        val dy = xy[start + 2 * (e % n) + 1] - ay
        var idx = -1
        var lead = -1.0
        var second = -1.0
        for (k in s + 1 until e) {
            val d = abs(dx * (ay - xy[start + 2 * k + 1]) - dy * (ax - xy[start + 2 * k]))
            if (d > lead) {
                second = lead
                lead = d
                idx = k
            } else if (d > second) {
                second = d
            }
        }
        val bar = tolerance * sqrt(dx * dx + dy * dy)
        if (lead <= bar * (1 - CLEAR) && bar > 0) return -1
        if (lead > bar * (1 + CLEAR) && second < lead * (1 - CLEAR)) return idx
        val len = hypot2(dx, dy)
        var max = tolerance
        idx = -1
        for (k in s + 1 until e) {
            val px = xy[start + 2 * k]
            val py = xy[start + 2 * k + 1]
            val d = if (len == 0.0) hypot2(px - ax, py - ay) else abs(dx * (ay - py) - dy * (ax - px)) / len
            if (d > max) {
                max = d
                idx = k
            }
        }
        return idx
    }
}

/** Vertices snap to even hundredths so edge midpoints land exactly on the path-number grid. */
private const val UNIT = 100L

/** Sharper turns and turns whose curve would stray over SAG from the outline stay corners. */
private const val CORNER = 0.6
private const val SAG = 0.1
private val TAN_CORNER = tan(CORNER)

/** Compact path data: a repeat of the last command goes without its letter, a number without a needless separator. */
private class PathWriter {
    private val out = StringBuilder()
    private var command = ' '
    private var numbers = 0
    private var lastHasPoint = false

    fun emit(c: Char, x: Long, y: Long) {
        if (c != command) {
            out.append(c)
            command = c
            numbers = 0
        }
        number(x)
        number(y)
    }

    fun close() {
        out.append('Z')
        command = 'Z'
    }

    /** A grid value as a path number, as short as SVG allows: no leading zero, no `-0`. */
    private fun number(k: Long) {
        val a = abs(k)
        val whole = a / UNIT
        val frac = (a % UNIT).toInt()
        val bare = whole == 0L && frac != 0
        // A separator is only needed where the next number could be read as part of the last one.
        if (numbers++ > 0 && k >= 0 && !(bare && lastHasPoint)) out.append(' ')
        if (k < 0) out.append('-')
        if (!bare) out.append(whole)
        if (frac != 0) {
            out.append('.').append((frac / 10).digitToChar())
            if (frac % 10 != 0) out.append((frac % 10).digitToChar())
        }
        lastHasPoint = frac != 0
    }

    override fun toString() = out.toString()
}

/** Whether the loop's vertex [p] between [a] and [b] stays sharp. Decided from products where they are clear. */
private fun isCorner(a: Point, p: Point, b: Point): Boolean {
    val cross = (p.x - a.x) * (b.y - p.y) - (p.y - a.y) * (b.x - p.x)
    val dot = (p.x - a.x) * (b.x - p.x) + (p.y - a.y) * (b.y - p.y)
    val turn = abs(cross)
    val steep = dot * TAN_CORNER
    val sharp = if (dot > 0) {
        turn > steep * (1 + CLEAR) || (turn >= steep * (1 - CLEAR) && abs(atan2(cross, dot)) > CORNER)
    } else {
        abs(atan2(cross, dot)) > CORNER
    }
    if (sharp) return true
    // The curve passes a quarter of p's height over the chord a-b away from p.
    val dx = b.x - a.x
    val dy = b.y - a.y
    val chord = (4 * SAG) * (4 * SAG) * (dx * dx + dy * dy)
    return turn * turn > chord * (1 + CLEAR) || (turn * turn >= chord * (1 - CLEAR) && turn > 4 * SAG * hypot2(dx, dy))
}

/** Compact closed paths: gentle facets become midpoint quadratics; real outline corners stay sharp. */
internal fun loopsToPath(loops: List<List<Point>>): String {
    val path = PathWriter()
    for (loop in loops) {
        val n = loop.size
        val xs = LongArray(n) { 2 * Math.round(loop[it].x * UNIT / 2) }
        val ys = LongArray(n) { 2 * Math.round(loop[it].y * UNIT / 2) }
        val corner = BooleanArray(n) { k -> isCorner(loop[(k + n - 1) % n], loop[k], loop[(k + 1) % n]) }
        // The path runs between the midpoints of the edges: from vertex k to the next one.
        var cx = (xs[0] + xs[1 % n]) / 2
        var cy = (ys[0] + ys[1 % n]) / 2
        path.emit('M', cx, cy)
        var smooth = false
        for (k in 1..n) {
            val at = k % n
            val after = (k + 1) % n
            val px = xs[at]
            val py = ys[at]
            val mx = (px + xs[after]) / 2
            val my = (py + ys[after]) / 2
            if (corner[at]) {
                // A midpoint between two corners lies on their straight edge, so it is only kept to start a curve.
                path.emit('l', px - cx, py - cy)
                cx = px
                cy = py
                if (k < n && !corner[after]) {
                    path.emit('l', mx - cx, my - cy)
                    cx = mx
                    cy = my
                }
                smooth = false
            } else {
                // Reflection through the shared midpoint makes the next vertex the smooth control.
                if (smooth) {
                    path.emit('t', mx - cx, my - cy)
                } else {
                    path.emit('q', px - cx, py - cy)
                    path.emit('q', mx - cx, my - cy)
                }
                cx = mx
                cy = my
                smooth = true
            }
        }
        path.close()
    }
    return path.toString()
}
