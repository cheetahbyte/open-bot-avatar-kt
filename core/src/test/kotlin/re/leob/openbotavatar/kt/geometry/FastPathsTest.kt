package re.leob.openbotavatar.kt.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.reachFinder
import re.leob.openbotavatar.kt.volume.PartKind
import re.leob.openbotavatar.kt.volume.analyzeBody
import re.leob.openbotavatar.kt.volume.buildVolume

/**
 * The volume build's fast paths must return exactly what the plain definitions do: the same numbers,
 * bit for bit. Each test checks one of them against its definition, written out here.
 */
class FastPathsTest {
    private val outlines: List<Pair<String, Polygons>> = AvatarCatalog.all.flatMap { def ->
        val body = analyzeBody(def.path)
        listOf(def.id to body.polys) + body.subpaths.mapIndexed { k, s -> "${def.id}/$k" to s.polys }
    } + listOf(
        // Long slanted edges that cross the whole body, over and over: each passes through many buckets.
        "zigzag" to listOf(List(60) { k -> Point(if (k % 2 == 1) 88 - k / 3.0 else 12 + k / 3.0, 10 + (k * 80) / 59.0) }),
    )

    private fun winding(polys: Polygons, x: Double, y: Double): Int {
        var w = 0
        forEachEdge(polys) { a, b ->
            val cross = (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y)
            if (a.y <= y) {
                if (b.y > y && cross > 0) w++
            } else if (b.y <= y && cross < 0) {
                w--
            }
        }
        return w
    }

    private fun insideRow(polys: Polygons, y: Double, xs: DoubleArray): BooleanArray {
        val hits = ArrayList<Pair<Double, Int>>()
        forEachEdge(polys) { a, b ->
            val x = a.x + ((y - a.y) * (b.x - a.x)) / (b.y - a.y)
            if (a.y <= y && b.y > y) hits.add(x to 1) else if (b.y <= y && a.y > y) hits.add(x to -1)
        }
        return BooleanArray(xs.size) { k -> hits.filter { it.first < xs[k] }.sumOf { it.second } != 0 }
    }

    private fun edgeDistance(polys: Polygons, x: Double, y: Double): Double {
        var best = Double.POSITIVE_INFINITY
        forEachEdge(polys) { a, b ->
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = dx * dx + dy * dy
            var t = if (len > 0) ((x - a.x) * dx + (y - a.y) * dy) / len else 0.0
            t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
            val ex = a.x + t * dx - x
            val ey = a.y + t * dy - y
            best = min(best, sqrt(ex * ex + ey * ey))
        }
        return best
    }

    /** Points that land on the outline (its corners, the middles of its edges, rows and columns through its bounds) and off it. */
    private fun probes(polys: Polygons): List<Point> {
        val b = bounds(polys)
        val out = ArrayList<Point>()
        for (poly in polys) for (i in poly.indices step 7) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            out += listOf(p, Point((p.x + q.x) / 2, (p.y + q.y) / 2), Point(p.x + 0.3, p.y), Point(p.x, p.y - 0.2))
        }
        for (y in sampleRange(b.minY - 1, b.maxY + 1, 2.5)) for (x in sampleRange(b.minX - 1, b.maxX + 1, 2.5)) out.add(Point(x, y))
        for (y in sampleRange(b.minY + 0.37, b.maxY, 3.1)) for (x in sampleRange(b.minX + 0.21, b.maxX, 2.9)) out.add(Point(x, y))
        return out
    }

    @Test
    fun bandedEdgesCountTheSameWinding() {
        for ((id, polys) in outlines) {
            val rows = EdgeBands(polys)
            for ((x, y) in probes(polys)) assertEquals("$id at ($x, $y)", winding(polys, x, y), rows.winding(x, y))
        }
    }

    @Test
    fun bandedEdgesFlagTheSamePointsOfARow() {
        for ((id, polys) in outlines) {
            val rows = EdgeBands(polys)
            val b = bounds(polys)
            // Rows through the bounds and through corners, with points on vertical edges among them.
            val ys = listOf(b.minY, b.maxY, b.minY - 1, (b.minY + b.maxY) / 2) + polys[0].filterIndexed { k, _ -> k % 11 == 0 }.map { it.y }
            val xs = DoubleArray(ceil((b.maxX - b.minX + 2) / 0.5).toInt() + 1) { b.minX - 1 + it * 0.5 }
            val fast = BooleanArray(xs.size)
            for (y in ys) {
                val plain = insideRow(polys, y, xs)
                rows.insideRow(y, xs, fast)
                assertEquals("$id row $y", plain.toList(), fast.toList())
                for (k in xs.indices step 5) assertEquals("$id (${xs[k]}, $y)", plain[k], rows.insideRowAt(xs[k], y))
            }
        }
    }

    @Test
    fun theEdgeIndexMeasuresAPointExactly() {
        for ((id, polys) in outlines) {
            val index = EdgeIndex(polys, bounds(polys))
            for ((x, y) in probes(polys)) assertEquals("$id at ($x, $y)", edgeDistance(polys, x, y), index.distance(x, y), 0.0)
            // Far outside the buckets too.
            assertEquals(id, edgeDistance(polys, -300.0, 410.0), index.distance(-300.0, 410.0), 0.0)
        }
    }

    @Test
    fun theEdgeIndexFillsAGridAndFindsItsWidestInsideSample() {
        for ((id, polys) in outlines) {
            val b = bounds(polys)
            val index = EdgeIndex(polys, b)
            for (step in listOf(0.9, 1.7)) {
                val nx = ceil((b.maxX - b.minX + 2) / step).toInt() + 1
                val ny = ceil((b.maxY - b.minY + 2) / step).toInt() + 1
                val g = SampleGrid(b.minX - 1, b.minY - 1, step, nx, ny)
                val fast = index.distances(g)
                val xs = DoubleArray(nx) { g.x0 + it * step }
                var widest = 0.0
                for (j in 0 until ny) {
                    val y = g.y0 + j * step
                    val inside = insideRow(polys, y, xs)
                    for (i in 0 until nx) {
                        val d = edgeDistance(polys, xs[i], y)
                        assertEquals("$id sample $i, $j", d, fast[j * nx + i], 0.0)
                        if (inside[i]) widest = max(widest, d)
                    }
                }
                assertEquals("$id step $step", widest, index.widest(g, EdgeBands(polys)), 0.0)
            }
        }
    }

    @Test
    fun aPartHasTheHeightItsOutlineAndSurfaceDefine() {
        for (def in AvatarCatalog.examples) for (part in buildVolume(def.eyes, 1.0, analyzeBody(def.path)).parts) {
            for ((x, y) in probes(part.polys).filterIndexed { k, _ -> k % 3 == 0 }) {
                if (winding(part.polys, x, y) == 0) {
                    assertEquals(def.id, 0.0, part.height(x, y), 0.0)
                } else if (part.kind == PartKind.INFLATE) {
                    val dist = edgeDistance(part.polys, x, y)
                    val half = part.depth / 2
                    val k = 1 - max(0.0, dist) / part.rxy
                    val expected = if (dist >= part.rxy) half else half - part.rz + sqrt(max(0.0, 1 - k * k)) * part.rz
                    assertEquals(def.id, expected, part.height(x, y), 0.0)
                }
            }
        }
    }

    /** Marching squares as written out in full, with a map of links. */
    private fun contourLoops(field: Field, level: Double): List<List<Point>> {
        val nx = field.nx
        fun at(i: Int, j: Int) = field.values[j * nx + i] - level
        val links = LinkedHashMap<Int, MutableList<Int>>()
        fun link(p: Int, q: Int) {
            links.getOrPut(p) { ArrayList(2) }.add(q)
            links.getOrPut(q) { ArrayList(2) }.add(p)
        }
        fun point(key: Int): Point {
            val idx = key shr 1
            val i = idx % nx
            val j = (idx - i) / nx
            val a = at(i, j)
            return if (key and 1 != 0) Point(field.x0 + i * field.step, field.y0 + (j + a / (a - at(i, j + 1))) * field.step)
            else Point(field.x0 + (i + a / (a - at(i + 1, j))) * field.step, field.y0 + j * field.step)
        }
        for (j in 0 until field.ny - 1) for (i in 0 until nx - 1) {
            val tl = at(i, j) >= 0
            val tr = at(i + 1, j) >= 0
            val br = at(i + 1, j + 1) >= 0
            val bl = at(i, j + 1) >= 0
            val top = 2 * (j * nx + i)
            val bottom = 2 * ((j + 1) * nx + i)
            if (tl == br && tr == bl && tl != tr) {
                val center = (at(i, j) + at(i + 1, j) + at(i + 1, j + 1) + at(i, j + 1)) / 4 >= 0
                if (tl == center) { link(top, top + 3); link(bottom, top + 1) } else { link(top + 1, top); link(top + 3, bottom) }
                continue
            }
            val crossed = listOfNotNull(top.takeIf { tl != tr }, (top + 3).takeIf { tr != br }, bottom.takeIf { br != bl }, (top + 1).takeIf { bl != tl })
            if (crossed.size == 2) link(crossed[0], crossed[1])
        }
        val seen = HashSet<Int>()
        return links.keys.filter { it !in seen }.mapNotNull { start ->
            val loop = ArrayList<Point>()
            var cur: Int? = start
            while (cur != null && seen.add(cur)) {
                loop.add(point(cur))
                cur = links.getValue(cur).firstOrNull { it !in seen }
            }
            loop.takeIf { it.size >= 3 }
        }
    }

    /** Ramer-Douglas-Peucker as written out in full: every distance through the norm. */
    private fun simplifyLoop(loop: List<Point>, tolerance: Double): List<Point> {
        if (loop.size <= 8) return loop
        val far = (1 until loop.size).maxBy { hypot2(loop[it].x - loop[0].x, loop[it].y - loop[0].y) }
        val keep = BooleanArray(loop.size)
        keep[0] = true
        keep[far] = true
        val stack = ArrayDeque(listOf(0 to far, far to loop.size))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            val a = loop[s]
            val b = loop[e % loop.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot2(dx, dy)
            var idx = -1
            var most = tolerance
            for (k in s + 1 until e) {
                val p = loop[k]
                val d = if (len == 0.0) hypot2(p.x - a.x, p.y - a.y) else abs(dx * (a.y - p.y) - dy * (a.x - p.x)) / len
                if (d > most) { most = d; idx = k }
            }
            if (idx >= 0) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return loop.filterIndexed { k, _ -> keep[k] }
    }

    /** A ring with a wavy rim on a grid, so its contours have curves, corners and a hole. */
    private fun field(nx: Int, ny: Int) = Field(
        DoubleArray(nx * ny) { k ->
            val x = k % nx - nx / 2.0
            val y = k / nx - ny / 2.0
            val r = hypot2(x, y)
            min(r - 7, 0.38 * nx + 2.5 * sin(5 * atan2(y, x)) - r)
        },
        nx, ny, 3.0, -2.0, 0.45,
    )

    @Test
    fun tracedOutlinesAreTheContourLoopsSimplified() {
        val contours = Contours()
        for ((nx, ny) in listOf(61 to 61, 90 to 47, 17 to 23)) {
            val f = field(nx, ny)
            for (level in listOf(0.0, 1.3, 4.0, 9.5, -2.0)) {
                val plain = contourLoops(f, level).map { simplifyLoop(it, 0.04) }.filter { it.size >= 3 }
                assertEquals("${nx}x$ny at $level", plain, contours.outlines(f, level, 0.04))
                // Runs of cells that cannot cross the level are skipped without changing a point.
                val range = RunRange(f.values, nx, ny)
                val flat = BooleanArray(runsOf(nx) * (ny - 1)) { range.min[it] - level >= 0 || range.max[it] - level < 0 }
                assertTrue(flat.any { it })
                assertEquals(plain, contours.outlines(f, level, 0.04, flat))
            }
        }
    }

    @Test
    fun loopsSimplifyAsThePlainAlgorithmDoesTiesIncluded() {
        val half = List(40) { k -> Point(3 + 2 * sin(k / 3.0) + k / 9.0, k * 0.45) }
        val loops = contourLoops(field(61, 61), 1.3) + listOf(
            // Mirror images tie exactly; so do the points of a square and of a loop that returns on itself.
            half + half.map { Point(-it.x, it.y) }.reversed(),
            List(36) { k -> if (k < 9) Point(k.toDouble(), 0.0) else if (k < 18) Point(9.0, k - 9.0) else if (k < 27) Point(27.0 - k, 9.0) else Point(0.0, 36.0 - k) },
            half + half.reversed(),
            List(12) { Point(5.0, 5.0) },
        )
        val contours = Contours()
        for (loop in loops) for (tolerance in listOf(0.04, 0.3, 0.0)) {
            val xy = loop.flatMap { listOf(it.x, it.y) }.toDoubleArray()
            assertEquals(simplifyLoop(loop, tolerance), contours.simplified(xy, 0, loop.size, tolerance))
        }
    }

    @Test
    fun ribbonRimsReachAsFarAsTestingEveryEdgeFinds() {
        fun reference(polys: Polygons, cx: Double, cy: Double, a: Double): Double {
            val ux = cos(a)
            val uy = sin(a)
            var far = 0.0
            forEachEdge(polys) { p, q ->
                val ex = q.x - p.x
                val ey = q.y - p.y
                val den = ux * ey - uy * ex
                if (!(abs(den) < 1e-12)) {
                    val t = ((p.x - cx) * ey - (p.y - cy) * ex) / den
                    val s = ((p.x - cx) * uy - (p.y - cy) * ux) / den
                    if (s >= 0 && s <= 1) far = max(far, abs(t))
                }
            }
            return far
        }
        val angles = listOf(0.0, PI / 2, PI, -PI / 2, 7.3, -11.9, 1e-9, PI - 1e-9, -1e-20, 1e20, -3.7e15, Double.NaN, Double.POSITIVE_INFINITY) +
            List(60) { it * PI / 60 } + List(40) { 0.1234 + it * 0.777 }
        for (def in AvatarCatalog.all) {
            val body = analyzeBody(def.path)
            val b = body.bounds
            val corner = body.polys[0][0]
            for ((cx, cy) in listOf(Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2), corner, Point(b.minX - 5, b.maxY + 3))) {
                val reach = reachFinder(body.polys, cx, cy)
                for (a in angles) assertEquals("${def.id} along $a from ($cx, $cy)", reference(body.polys, cx, cy, a), reach(a), 0.0)
            }
        }
    }
}
