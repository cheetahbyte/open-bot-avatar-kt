package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.*
import kotlin.math.*

private const val UNIT = 80 / 228.541
private fun f(n: Double) = fixed(n, 2)
private fun mix(a: Double, b: Double, u: Double) = a + (b - a) * u

/** Directions a half turn is cut into for finding the edges a line through the center can cross. */
private const val RIM_FANS = 180

internal class Rim(val cx: Double, val cy: Double, val at: (Double) -> Double)

/**
 * How far [polys] reach from (cx, cy) along a direction (radians), either way, exactly.
 *
 * A line through the center only crosses the edges that span its direction as seen from there, so
 * the edges are listed by the directions they span, with a fan to spare on either side, and a line
 * is tested against the edges of its own fan. An edge left out lies a whole fan off the line, far
 * more than the test's rounding, so it would not have counted: the answer is the same as from
 * testing every edge.
 */
internal fun reachFinder(polys: Polygons, cx: Double, cy: Double): (Double) -> Double {
    val count = polys.sumOf { it.size }
    // x1, y1 and the edge vector, per edge.
    val e = DoubleArray(4 * count)
    // The fans each edge spans: first and how many more. Edges at the center span them all.
    val first = IntArray(count)
    val more = IntArray(count) { RIM_FANS - 1 }
    val start = IntArray(RIM_FANS + 1)
    var n = 0
    forEachEdge(polys) { p, q ->
        val k = n++
        e[4 * k] = p.x
        e[4 * k + 1] = p.y
        e[4 * k + 2] = q.x - p.x
        e[4 * k + 3] = q.y - p.y
        val px = p.x - cx
        val py = p.y - cy
        val qx = q.x - cx
        val qy = q.y - cy
        // The turn from p to q about the center, the short way round.
        val from = atan2(py, px)
        val turn = atan2(px * qy - py * qx, px * qx + py * qy)
        val lo = floor(min(from, from + turn) / PI * RIM_FANS) - 1
        val hi = floor(max(from, from + turn) / PI * RIM_FANS) + 1
        val near = px * px + py * py < 1e-6 || qx * qx + qy * qy < 1e-6 || abs(turn) > PI - 0.1
        if (!near && hi - lo < RIM_FANS) {
            first[k] = lo.toInt().mod(RIM_FANS)
            more[k] = (hi - lo).toInt()
        }
        for (fan in 0..more[k]) start[(first[k] + fan) % RIM_FANS + 1]++
    }
    for (fan in 0 until RIM_FANS) start[fan + 1] += start[fan]
    val items = IntArray(start[RIM_FANS])
    val next = start.copyOf(RIM_FANS)
    for (k in 0 until count) for (fan in 0..more[k]) items[next[(first[k] + fan) % RIM_FANS]++] = 4 * k

    return { a ->
        val ux = cos(a)
        val uy = sin(a)
        // The fan of the direction itself, so an angle of any size lands in the right one.
        val turns = atan2(uy, ux) / PI
        val fan = floor((turns - floor(turns)) * RIM_FANS)
        // A direction that is in no fan (not a number) is tested against every edge.
        val known = fan >= 0 && fan < RIM_FANS
        var far = 0.0
        for (q in (if (known) start[fan.toInt()] else 0) until (if (known) start[fan.toInt() + 1] else count)) {
            val at = if (known) items[q] else 4 * q
            val ex = e[at + 2]
            val ey = e[at + 3]
            val den = ux * ey - uy * ex
            if (abs(den) < 1e-12) continue
            // Where the line through the center crosses this edge: t along the line, s along the edge.
            val t = ((e[at] - cx) * ey - (e[at + 1] - cy) * ex) / den
            val s = ((e[at] - cx) * uy - (e[at + 1] - cy) * ux) / den
            if (s >= 0 && s <= 1) far = max(far, abs(t))
        }
        far
    }
}

/** Hoops follow the silhouette in every lean and stretch the state takes. */
internal fun ribbonRims(polys: Polygons, b: Bounds): Pair<Rim, Rim> {
    val cx = (b.minX + b.maxX) / 2; val cy = (b.minY + b.maxY) / 2
    fun posed(rolls: List<Double>, sys: List<Double>): Rim {
        val outlines = rolls.flatMap { roll ->
            val r = roll * PI / 180; val c = cos(r); val sn = sin(r)
            sys.flatMap { k -> polys.map { poly -> poly.map { (x, y) ->
                val lx = cx + c * (x - cx) - sn * (y - b.maxY)
                val ly = b.maxY + sn * (x - cx) + c * (y - b.maxY)
                Point(cx + 1.01 * (lx - cx), cy + k * (ly - cy))
            } } }
        }
        return Rim(cx, cy, reachFinder(outlines, cx, cy))
    }
    return posed(listOf(2.4, 4.5, 6.6), listOf(0.98, 1.0)) to posed(listOf(-1.7, 0.0, 1.7), listOf(0.99, 1.055))
}

internal fun ribbonsFollow(rim: Rim, pose: Pose, b: Bounds): Rim {
    val bx = (b.minX + b.maxX) / 2
    return Rim(bx + pose.sx * (rim.cx - bx), b.maxY + pose.sy * (rim.cy - b.maxY) + pose.lift, rim.at)
}

internal data class RibbonPiece(
    val d: String, val front: Boolean, val box: PathBox, val ribbon: Int,
    val along: Pair<Double, Double>, val from: Point, val to: Point,
)

internal fun ribbonPieces(presence: Double, seed: Int, sweep: Double, cage: Double, rim: Rim): List<RibbonPiece> {
    val k = clamp(presence, 0.0, 1.0); val more = if (cage >= 0.5) 1.0 else 0.0
    if (k <= 0.01) return emptyList()
    val cx = rim.cx; val cy = rim.cy
    val rolls = List(3) { i -> 0.157 + i * PI / 3 + (noise(seed, 450 + i) - 0.5) * 0.24 }
    val cageRing = rolls.maxOf { rim.at(it) } + 2
    val dir = if (sweep < 0) -1 else 1
    val since = abs(sweep) / mix(6.3, 15.0, more)
    val pieces = ArrayList<RibbonPiece>()
    for (i in 0 until if (more > 0) 3 else 1) {
        val roll = rolls[i]; val tilt = 0.386 + (noise(seed, 460 + i) - 0.5) * 0.08
        val ring = if (more > 0) cageRing else rim.at(roll) + 2
        val lanes = if (i != 0 || more > 0) 3 else Math.round(mix(3.0, 5.0, noise(seed, 47))).toInt()
        for (l in 0 until lanes) {
            fun n(m: Int) = noise(seed, 500 + (i * 3 + l) * 11 + m)
            val order = if (more > 0) l * 3 + i else l
            val at = order * mix(0.055, 0.105, n(7)); val life = since - at
            if (life <= 0) continue
            val before = if (since > 0) abs(sweep) * (at / since) else 0.0
            val flat = sin(tilt); val depth = cos(tilt); val cr = cos(roll); val sr = sin(roll)
            val r = ring + l * 38 * UNIT / (lanes - 1) + mix(0.0, 2.5, n(2)) * UNIT * life
            val travel = (abs(sweep) - before) * mix(0.74, 0.94, n(3)) + mix(0.5, 1.1, n(4)) * life
            val head = dir * (before + travel - 0.18 * n(5))
            val ret = 1 - clamp(k * 1.25, 0.0, 1.0)
            val length = min(mix(2.2, 3.4, n(6)), travel) * (1 - ret * ret * (3 - 2 * ret))
            if (length <= 0.02) continue
            val range = when (lanes) { 3 -> 8.0 to 10.5; 4 -> 6.6 to 8.6; else -> 5.6 to 7.4 }
            val size = mix(range.first, range.second, n(8)) * (1 - more) + mix(5.6, 7.4, n(8)) * more
            val nearHead = clamp(cos(head) * depth, 0.0, 1.0)
            var full = size * (1.7 * 1.98) * UNIT * (0.72 + (1 - 0.72) * nearHead) * smoothstep(life / 0.34) * (1 - 0.72 * ret * ret)
            fun lamAt(u: Double) = head - dir * length * (1 - u)
            fun point(lam: Double): Point {
                val a = sin(lam); val y = -cos(lam) * flat
                return Point(cx + r * (a * cr - y * sr), cy + r * (a * sr + y * cr))
            }
            val ss = (0..24).map { it / 24.0 }.toMutableList()
            val lo = min(head, head - dir * length); val hi = max(head, head - dir * length)
            var sideIndex = ceil((lo - PI / 2) / PI).toInt()
            while (PI / 2 + sideIndex * PI < hi) {
                val u = 1 - (head - (PI / 2 + sideIndex * PI)) / (dir * length)
                for (d in listOf(-0.02 / length, 0.0, 0.02 / length)) if (u + d > 0 && u + d < 1) ss.add(u + d)
                sideIndex++
            }
            ss.sortWith(numericOrder)
            val near = ss.map { cos(lamAt(it) + dir * 1e-6) > 0 }
            val mid = ArrayList<Point>(); val halfs = ArrayList<Double>(); val norm = ArrayList<Point>(); val bend = ArrayList<Double>()
            for (u in ss) {
                val lam = lamAt(u); val nx = sin(lam) * flat; val ny = -cos(lam)
                val nl = hypot(nx, ny).takeUnless { it == 0.0 } ?: 1.0
                val ux = nx / nl; val uy = ny / nl
                mid.add(point(lam)); norm.add(Point(ux * cr - uy * sr, ux * sr + uy * cr)); halfs.add(0.5 + 0.5 * u)
                val c = cos(lam); val sn = sin(lam)
                bend.add(r * (c * c + flat * flat * sn * sn).pow(1.5) / flat)
            }
            val last = ss.lastIndex
            var onScreen = 0.0
            for (j in 1..last) onScreen += hypot(mid[j].x - mid[j - 1].x, mid[j].y - mid[j - 1].y)
            full = min(full, 0.34 * onScreen)
            for (j in 0..last) halfs[j] = min(halfs[j] * (full / 2), 0.9 * bend[j])
            fun side(j: Int, sign: Double) = Point(mid[j].x + sign * norm[j].x * halfs[j], mid[j].y + sign * norm[j].y * halfs[j])
            fun cap(j: Int, ahead: Boolean): List<Point> {
                val o = if (ahead) mid[j - 1] else mid[j + 1]
                var tx = if (ahead) mid[j].x - o.x else o.x - mid[j].x
                var ty = if (ahead) mid[j].y - o.y else o.y - mid[j].y
                val tl = hypot(tx, ty).takeUnless { it == 0.0 } ?: 1.0
                tx /= tl; ty /= tl
                val (nx, ny) = norm[j]; val h = halfs[j]
                return (1 until 7).map { c ->
                    val t = PI * c / 7; val e = if (ahead) cos(t) else -cos(t); val g = if (ahead) sin(t) else -sin(t)
                    Point(mid[j].x + (nx * e + tx * g) * h, mid[j].y + (ny * e + ty * g) * h)
                }
            }
            var from = 0
            for (j in 1..last) {
                val turns = j < last && near[j] != near[from]
                if (j < last && !turns && j - from < 6) continue
                val behind = !near[from]
                val end = if (j < last && (!turns || behind)) j + 1 else j
                val start = if (from > 0 && behind && near[from - 1]) from - 1 else from
                val pts = ArrayList<Point>()
                for (q in start..end) pts.add(side(q, 1.0))
                if (end == last) pts.addAll(cap(last, true))
                for (q in end downTo start) pts.add(side(q, -1.0))
                if (start == 0) pts.addAll(cap(0, false))
                val x = pts.minOf { it.x }; val y = pts.minOf { it.y }
                pieces.add(RibbonPiece("M" + pts.joinToString("L") { "${f(it.x)} ${f(it.y)}" } + "Z", near[from],
                    PathBox(x, y, pts.maxOf { it.x } - x, pts.maxOf { it.y } - y), i * 3 + l, ss[from] to ss[j], mid[from], mid[j]))
                from = j
            }
        }
    }
    return pieces
}

/** A mark starts to grow at this weight, just before the body is gone, so something is always on screen. */
private const val MARK_FROM = 0.45

private fun grown(weight: Double) = smoothstep((clamp(weight, 0.0, 1.0) - MARK_FROM) / (1 - MARK_FROM))

/** The unit every mark is sized in: a share of the body's smaller side, within limits that read at any size. */
private fun markUnit(b: Bounds) = clamp(min(b.maxX - b.minX, b.maxY - b.minY), 34.0, 78.0)

/**
 * The parts of a pose's marks at one moment, placed on the body. A mark's weight 0..1 is how far the
 * body has given way to it: the mark grows in as the body goes, from the body's center.
 */
internal fun markParts(b: Bounds, marks: List<PoseMark>, seed: Int, time: Double, still: Boolean): List<MarkPart> {
    val cx = (b.minX + b.maxX) / 2
    val cy = (b.minY + b.maxY) / 2
    val ctx = MarkContext(markUnit(b), if (still) 0.0 else time + noise(seed, 9) * 10, still)
    return marks.flatMap { (mark, weight) ->
        val g = grown(weight)
        if (g > 0) markPartsOf(mark.parts(ctx)).map { it.copy(x = cx + it.x * g, y = cy + it.y * g, scale = it.scale * g) } else emptyList()
    }
}

/**
 * How much of its size the body keeps while it gives way to its marks: gone a little past half way.
 * Several marks that each weigh too little to show (when a change between two is interrupted by a
 * third) would leave nothing on screen: until one of them shows, the body stays as small as it is
 * when a mark first does.
 */
internal fun bodyScale(marks: List<PoseMark>): Double {
    fun left(weight: Double) = 1 - smoothstep(clamp(weight, 0.0, 1.0) / 0.55)
    val scale = left(marks.sumOf { it.weight })
    return if (marks.any { grown(it.weight) > 0 }) scale else max(scale, left(MARK_FROM))
}
