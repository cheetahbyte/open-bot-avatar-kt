package re.leob.openbotavatar.kt.geometry

import kotlin.math.abs
import kotlin.math.hypot

/** Row-major samples of a scalar field; the outermost ring should be below every level. */
internal class Field(
    val values: DoubleArray,
    val nx: Int,
    val ny: Int,
    val x0: Double,
    val y0: Double,
    val step: Double,
)

/** Closed loops where the field crosses [level] (marching squares; inside is value at or above level). */
internal fun contourLoops(field: Field, level: Double): List<List<Point>> {
    val nx = field.nx
    val ny = field.ny
    fun at(i: Int, j: Int) = field.values[j * nx + i] - level

    // Edge keys: horizontal edge (i,j)-(i+1,j) is 2*(j*nx+i), vertical (i,j)-(i,j+1) is 2*(j*nx+i)+1.
    val points = HashMap<Int, Point>()
    val links = LinkedHashMap<Int, MutableList<Int>>()
    fun point(key: Int) {
        if (key in points) return
        val idx = key shr 1
        val i = idx % nx
        val j = (idx - i) / nx
        val a = at(i, j)
        points[key] = if (key and 1 != 0) {
            val t = a / (a - at(i, j + 1))
            Point(field.x0 + i * field.step, field.y0 + (j + t) * field.step)
        } else {
            val t = a / (a - at(i + 1, j))
            Point(field.x0 + (i + t) * field.step, field.y0 + j * field.step)
        }
    }
    fun link(p: Int, q: Int) {
        point(p)
        point(q)
        links.getOrPut(p) { ArrayList(2) }.add(q)
        links.getOrPut(q) { ArrayList(2) }.add(p)
    }

    for (j in 0 until ny - 1) {
        for (i in 0 until nx - 1) {
            val tl = at(i, j) >= 0
            val tr = at(i + 1, j) >= 0
            val br = at(i + 1, j + 1) >= 0
            val bl = at(i, j + 1) >= 0
            val code = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            if (code == 0 || code == 15) continue
            val top = 2 * (j * nx + i)
            val bottom = 2 * ((j + 1) * nx + i)
            val left = 2 * (j * nx + i) + 1
            val right = 2 * (j * nx + i + 1) + 1
            if (code == 10 || code == 5) {
                val center = (at(i, j) + at(i + 1, j) + at(i + 1, j + 1) + at(i, j + 1)) / 4 >= 0
                if ((code == 10) == center) {
                    link(top, right)
                    link(bottom, left)
                } else {
                    link(left, top)
                    link(right, bottom)
                }
                continue
            }
            val crossed = ArrayList<Int>(2)
            if (tl != tr) crossed.add(top)
            if (tr != br) crossed.add(right)
            if (br != bl) crossed.add(bottom)
            if (bl != tl) crossed.add(left)
            link(crossed[0], crossed[1])
        }
    }

    val loops = ArrayList<List<Point>>()
    val seen = HashSet<Int>()
    for (start in links.keys) {
        if (start in seen) continue
        val loop = ArrayList<Point>()
        var cur = start
        while (cur !in seen) {
            seen.add(cur)
            loop.add(points.getValue(cur))
            cur = links.getValue(cur).firstOrNull { it !in seen } ?: break
        }
        if (loop.size >= 3) loops.add(loop)
    }
    return loops
}

private fun perpendicular(p: Point, a: Point, b: Point): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len == 0.0) return hypot(p.x - a.x, p.y - a.y)
    return abs(dx * (a.y - p.y) - dy * (a.x - p.x)) / len
}

/** Ramer-Douglas-Peucker on a closed loop. */
internal fun simplifyLoop(loop: List<Point>, tolerance: Double): List<Point> {
    if (loop.size <= 8) return loop
    var far = 0
    var best = -1.0
    for (k in 1 until loop.size) {
        val d = hypot(loop[k].x - loop[0].x, loop[k].y - loop[0].y)
        if (d > best) {
            best = d
            far = k
        }
    }
    val keep = BooleanArray(loop.size)
    keep[0] = true
    keep[far] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to far)
    stack.addLast(far to loop.size)
    while (stack.isNotEmpty()) {
        val (s, e) = stack.removeLast()
        val end = loop[e % loop.size]
        var idx = -1
        var max = tolerance
        for (k in s + 1 until e) {
            val d = perpendicular(loop[k], loop[s], end)
            if (d > max) {
                max = d
                idx = k
            }
        }
        if (idx >= 0) {
            keep[idx] = true
            stack.addLast(s to idx)
            stack.addLast(idx to e)
        }
    }
    return loop.filterIndexed { k, _ -> keep[k] }
}

internal fun loopsToPath(loops: List<List<Point>>): String = buildString {
    for (loop in loops) {
        append("M${fixed(loop[0].x, 1)} ${fixed(loop[0].y, 1)}L")
        for (k in 1 until loop.size) {
            if (k > 1) append(' ')
            append("${fixed(loop[k].x, 1)} ${fixed(loop[k].y, 1)}")
        }
        append('Z')
    }
}
