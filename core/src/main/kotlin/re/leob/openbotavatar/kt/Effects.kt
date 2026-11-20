package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.*
import kotlin.math.*

private const val TAU = PI * 2
private const val UNIT = 80 / 228.541
private fun f(n: Double) = fixed(n, 2)
private fun mix(a: Double, b: Double, u: Double) = a + (b - a) * u

internal class Rim(val cx: Double, val cy: Double, val at: (Double) -> Double)

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
        return Rim(cx, cy) { a ->
            val ux = cos(a); val uy = sin(a); var far = 0.0
            for (poly in outlines) for (i in poly.indices) {
                val p = poly[i]; val q = poly[(i + 1) % poly.size]
                val ex = q.x - p.x; val ey = q.y - p.y; val den = ux * ey - uy * ex
                if (abs(den) < 1e-12) continue
                val t = ((p.x - cx) * ey - (p.y - cy) * ex) / den
                val s = ((p.x - cx) * uy - (p.y - cy) * ux) / den
                if (s >= 0 && s <= 1) far = max(far, abs(t))
            }
            far
        }
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

internal data class MarkPart(val shape: String, val d: String, val box: PathBox, val x: Double, val y: Double, val turn: Double, val scale: Double)

private fun circle(r: Double) = "M${f(r)} 0A${f(r)} ${f(r)} 0 1 1 ${f(-r)} 0A${f(r)} ${f(r)} 0 1 1 ${f(r)} 0Z"
private fun capsule(w: Double, h: Double): String {
    val r = w / 2; val y = h / 2 - r
    return "M${f(-r)} ${f(-y)}A${f(r)} ${f(r)} 0 0 1 ${f(r)} ${f(-y)}L${f(r)} ${f(y)}A${f(r)} ${f(r)} 0 0 1 ${f(-r)} ${f(y)}Z"
}

internal fun markParts(b: Bounds, bang: Double, dots: Double, seed: Int, time: Double, still: Boolean): List<MarkPart> {
    val cx = (b.minX + b.maxX) / 2; val cy = (b.minY + b.maxY) / 2
    val u = clamp(min(b.maxX - b.minX, b.maxY - b.minY), 34.0, 78.0)
    val t = if (still) 0.0 else time + noise(seed, 9) * 10
    val parts = ArrayList<MarkPart>()
    fun grown(w: Double) = smoothstep((clamp(w, 0.0, 1.0) - 0.45) / 0.55)
    val gb = grown(bang)
    if (gb > 0) {
        val barW = 0.21 * u; val barH = 0.5 * u; val dot = 0.115 * u; val gap = 0.1 * u
        val total = barH + gap + 2 * dot
        val p = if (still) 1.0 else ((t % 2.6) + 2.6) % 2.6 / 2.6
        val alarm = if (p < 0.3) p / 0.3 else 1.0
        val jump = if (still) 0.0 else sin(PI * alarm)
        val turn = if (still) 0.0 else 0.2 * sin(TAU * 3 * alarm) * (1 - alarm)
        val lift = -0.09 * u * jump
        val barY = -total / 2 + barH / 2; val dotY = total / 2 - dot
        parts.add(MarkPart("bar", capsule(barW, barH), PathBox(-barW / 2, -barH / 2, barW, barH), cx, cy + (barY + lift) * gb, turn, gb))
        parts.add(MarkPart("dot", circle(dot), PathBox(-dot, -dot, 2 * dot, 2 * dot), cx, cy + (dotY + lift * 0.45) * gb, turn, gb))
    }
    val gd = grown(dots)
    if (gd > 0) {
        val r = 0.1 * u; val step = 0.31 * u
        for (i in 0..2) {
            val p = if (still) 0.0 else (((t - i * 0.17) % 1.25) + 1.25) % 1.25 / 1.25
            val hop = if (p < 0.4) sin(PI * (p / 0.4)) else 0.0
            parts.add(MarkPart("dot3", circle(r), PathBox(-r, -r, 2 * r, 2 * r), cx + (i - 1) * step * gd,
                cy - 0.16 * u * hop * gd, 0.0, gd * (1 + 0.12 * hop)))
        }
    }
    return parts
}

internal fun bodyScale(bang: Double, dots: Double): Double = 1 - smoothstep(clamp(bang + dots, 0.0, 1.0) / 0.55)
