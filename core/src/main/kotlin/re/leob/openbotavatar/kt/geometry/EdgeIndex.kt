package re.leob.openbotavatar.kt.geometry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Margin on every bound that lets an edge be skipped, viewBox units. */
private const val SLACK = 1e-6

/** Side of a bucket of the coarse edge grid, viewBox units. */
private const val CELL = 4.0

/** Side, in samples, of the tiles a sample grid is cut into before each is halved down to samples. */
private const val TILE = 16

/** Candidate lists a tile needs at once: its own, then a shorter one per halving on the way down to a sample. */
private const val LISTS = 6

/** A regular grid of sample points: x0 + i * step, y0 + j * step. */
internal class SampleGrid(val x0: Double, val y0: Double, val step: Double, val nx: Int, val ny: Int)

/** How far the samples i0..i1 by j0..j1 reach from the middle one. */
private fun radiusOf(step: Double, i0: Int, i1: Int, j0: Int, j1: Int): Double {
    val im = (i0 + i1) shr 1
    val jm = (j0 + j1) shr 1
    return step * hypot2(max(im - i0, i1 - im).toDouble(), max(jm - j0, j1 - jm).toDouble())
}

/**
 * Distance from points to the nearest edge of a set of polygons, with the edges bucketed on a
 * coarse grid made for outlines in the 0..100 viewBox.
 *
 * Every answer is the smallest value of one expression (the squared distance to a segment) over all
 * edges, then its square root. A minimum does not depend on the order or the number of values it is
 * taken over, as long as the smallest one is among them. So any edge that is provably farther than
 * the nearest may be skipped, and the result is still the same bits a pass over every edge would
 * give. The proofs keep a margin (SLACK) far wider than rounding error and far narrower than
 * anything it could cost.
 *
 * Immutable once built, so safe from any thread; a walk over a grid has its own scratch.
 */
internal class EdgeIndex(polys: Polygons, b: Bounds) {
    private val count = polys.sumOf { it.size }

    /** ax, ay, dx, dy, dx² + dy² per edge: the terms of a distance that do not depend on the point. */
    private val e = DoubleArray(5 * count)
    private val ox = b.minX - 4
    private val oy = b.minY - 4
    private val span = ceil((max(b.maxX - b.minX, b.maxY - b.minY) + 8) / CELL).toInt() + 1
    private val start = IntArray(span * span + 1)
    private val items: IntArray

    init {
        var k = 0
        forEachEdge(polys) { p, q ->
            val dx = q.x - p.x
            val dy = q.y - p.y
            e[k] = p.x
            e[k + 1] = p.y
            e[k + 2] = dx
            e[k + 3] = dy
            e[k + 4] = dx * dx + dy * dy
            k += 5
        }
        // Counting sort of the edges into every bucket they pass through: count, then place.
        forEachBucket(polys) { bucket, _ -> start[bucket + 1]++ }
        for (q in 0 until span * span) start[q + 1] += start[q]
        items = IntArray(start[span * span])
        val next = start.copyOf(span * span)
        forEachBucket(polys) { bucket, edge -> items[next[bucket]++] = edge }
    }

    private inline fun forEachBucket(polys: Polygons, action: (bucket: Int, edge: Int) -> Unit) {
        var k = 0
        forEachEdge(polys) { p, q ->
            val dx = q.x - p.x
            val dy = q.y - p.y
            val cy0 = cellY(min(p.y, q.y))
            val cy1 = cellY(max(p.y, q.y))
            for (cy in cy0..cy1) {
                // The stretch of the edge inside this row of buckets, a little generously, so a long
                // slanted edge is not listed in every bucket of its box. The outermost rows also hold
                // whatever lies beyond them, so there the whole edge counts.
                var x0 = min(p.x, q.x)
                var x1 = max(p.x, q.x)
                if (cy0 < cy1 && cy > 0 && cy < span - 1) {
                    val t0 = clamp((oy + cy * CELL - SLACK - p.y) / dy, 0.0, 1.0)
                    val t1 = clamp((oy + (cy + 1) * CELL + SLACK - p.y) / dy, 0.0, 1.0)
                    x0 = min(p.x + t0 * dx, p.x + t1 * dx) - SLACK
                    x1 = max(p.x + t0 * dx, p.x + t1 * dx) + SLACK
                }
                for (cx in cellX(x0)..cellX(x1)) action(cy * span + cx, k)
            }
            k += 5
        }
    }

    private fun cellX(x: Double) = max(0, min(span - 1, floor((x - ox) / CELL).toInt()))
    private fun cellY(y: Double) = max(0, min(span - 1, floor((y - oy) / CELL).toInt()))

    /** Squared distance from a point to edge [k]. `Walk.survey` writes the same expression out. */
    private fun dist2(k: Int, x: Double, y: Double): Double {
        val ax = e[k]
        val ay = e[k + 1]
        val dx = e[k + 2]
        val dy = e[k + 3]
        val len = e[k + 4]
        var t = if (len > 0) ((x - ax) * dx + (y - ay) * dy) / len else 0.0
        t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
        val ex = ax + t * dx - x
        val ey = ay + t * dy - y
        return ex * ex + ey * ey
    }

    /** Distance to the nearest edge, searching rings of buckets outward until no closer edge can exist. */
    fun distance(x: Double, y: Double): Double {
        val bx = cellX(x)
        val by = cellY(y)
        var best = Double.POSITIVE_INFINITY
        for (r in 0 until span) {
            for (cy in max(0, by - r)..min(span - 1, by + r)) {
                val edgeRow = cy == by - r || cy == by + r
                val stride = if (edgeRow || r == 0) 1 else 2 * r
                var cx = bx - r
                while (cx <= bx + r) {
                    if (cx in 0 until span) {
                        val bucket = cy * span + cx
                        for (q in start[bucket] until start[bucket + 1]) {
                            val d = dist2(items[q], x, y)
                            if (d < best) best = d
                        }
                    }
                    cx += stride
                }
            }
            // Every edge not seen yet lies in a bucket more than r cells away (best is squared).
            val reach = r * CELL - SLACK
            if (reach > 0 && best <= reach * reach) break
        }
        return sqrt(best)
    }

    /** Distance to the nearest edge at every sample of a grid, row-major. */
    fun distances(g: SampleGrid): DoubleArray = Walk(g).distances()

    /**
     * The largest distance to the nearest edge over the grid's samples that are inside the fill
     * ([EdgeBands.insideRow] of each sample's row), or 0 if none is. Same as the largest inside value
     * of [distances], but most of the grid is ruled out without being measured.
     */
    fun widest(g: SampleGrid, rows: EdgeBands): Double = Walk(g).widest(rows)

    private inner class Walk(private val g: SampleGrid) {
        /** Per edge: the last [collect] that listed it, so an edge in several buckets is listed once. */
        private val stamp = IntArray(count)
        private var tag = 0

        /** Candidate lists of the tile in hand, one after another (see [narrow]). */
        private val lists = IntArray(LISTS * count)

        /** Per candidate of the last [survey]: squared distance from its point and the vector to the nearest point. */
        private val d2 = DoubleArray(count)
        private val vx = DoubleArray(count)
        private val vy = DoubleArray(count)

        /** The last survey's vector to the nearest point of all. */
        private var bx = 0.0
        private var by = 0.0

        private fun atX(i: Int) = g.x0 + i * g.step
        private fun atY(j: Int) = g.y0 + j * g.step

        fun distances(): DoubleArray {
            val out = DoubleArray(g.nx * g.ny)
            for (j0 in 0 until g.ny step TILE) {
                for (i0 in 0 until g.nx step TILE) {
                    val i1 = min(g.nx, i0 + TILE) - 1
                    val j1 = min(g.ny, j0 + TILE) - 1
                    val x = atX((i0 + i1) shr 1)
                    val y = atY((j0 + j1) shr 1)
                    fill(out, i0, i1, j0, j1, 0, collect(x, y, distance(x, y) + 2 * radiusOf(g.step, i0, i1, j0, j1) + SLACK))
                }
            }
            return out
        }

        fun widest(rows: EdgeBands): Double {
            val across = (g.nx + TILE - 1) / TILE
            val down = (g.ny + TILE - 1) / TILE
            // Start from the best middle sample of a tile, so most tiles are ruled out before they are opened.
            val middles = DoubleArray(across * down)
            var best = 0.0
            for (k in middles.indices) {
                val i0 = k % across * TILE
                val j0 = k / across * TILE
                val x = atX((i0 + min(g.nx, i0 + TILE) - 1) shr 1)
                val y = atY((j0 + min(g.ny, j0 + TILE) - 1) shr 1)
                val d = distance(x, y)
                middles[k] = d
                if (d > best && rows.insideRowAt(x, y)) best = d
            }
            for (k in middles.indices) {
                val i0 = k % across * TILE
                val j0 = k / across * TILE
                val i1 = min(g.nx, i0 + TILE) - 1
                val j1 = min(g.ny, j0 + TILE) - 1
                val x = atX((i0 + i1) shr 1)
                val y = atY((j0 + j1) shr 1)
                val d = middles[k]
                val radius = radiusOf(g.step, i0, i1, j0, j1)
                // No sample of the tile is farther from the outline than its middle plus its radius.
                if (d + radius + SLACK < best) continue
                // If the outline does not reach into the tile, it is inside or outside as a whole.
                val whole = d > radius + SLACK
                if (whole && !rows.insideRowAt(x, y)) continue
                best = peak(rows, best, whole, i0, i1, j0, j1, 0, collect(x, y, d + 2 * radius + SLACK))
            }
            return best
        }

        /** Lists every edge with a point in the square of [reach] around (x, y) at the start of [lists]. */
        private fun collect(x: Double, y: Double, reach: Double): Int {
            tag++
            var n = 0
            for (cy in cellY(y - reach)..cellY(y + reach)) {
                for (cx in cellX(x - reach)..cellX(x + reach)) {
                    val bucket = cy * span + cx
                    for (q in start[bucket] until start[bucket + 1]) {
                        val k = items[q]
                        if (stamp[k / 5] == tag) continue
                        stamp[k / 5] = tag
                        lists[n++] = k
                    }
                }
            }
            return n
        }

        /** Squared distance from (x, y) to the nearest of the edges `lists[lo until hi]`. */
        private fun nearest(lo: Int, hi: Int, x: Double, y: Double): Double {
            var best = Double.POSITIVE_INFINITY
            for (q in lo until hi) {
                val d = dist2(lists[q], x, y)
                if (d < best) best = d
            }
            return best
        }

        /** [nearest], keeping for [narrow] each edge's squared distance and the vector to its nearest point. */
        private fun survey(lo: Int, hi: Int, x: Double, y: Double): Double {
            var best = Double.POSITIVE_INFINITY
            for (q in lo until hi) {
                val k = lists[q]
                val ax = e[k]
                val ay = e[k + 1]
                val dx = e[k + 2]
                val dy = e[k + 3]
                val len = e[k + 4]
                var t = if (len > 0) ((x - ax) * dx + (y - ay) * dy) / len else 0.0
                t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
                val ex = ax + t * dx - x
                val ey = ay + t * dy - y
                val d = ex * ex + ey * ey
                d2[q - lo] = d
                vx[q - lo] = ex
                vy[q - lo] = ey
                if (d < best) {
                    best = d
                    bx = ex
                    by = ey
                }
            }
            return best
        }

        /**
         * After a survey of `lists[lo until hi]` from a point at distance [d] from the nearest edge:
         * lists, from [hi] on, the edges that can be nearest to some point within [radius] of it.
         * Returns the end of that list.
         *
         * An edge nearest to such a point p is no farther from p than the surveyed point's nearest
         * point n is. With u and w the unit directions from n and from the edge's nearest point to the
         * surveyed point m, and v = p - m, that gives D + w·v <= |p - n| <= d + u·v + |v|² / 2d. So the
         * edge's distance D from m exceeds d by at most |u - w| r + r² / 2d, and never by more than 2r.
         */
        private fun narrow(lo: Int, hi: Int, d: Double, radius: Double): Int {
            val ball = d + 2 * radius + SLACK
            val bend = if (d > 0) (radius * radius) / (2 * d) else Double.POSITIVE_INFINITY
            val ux = if (d > 0) bx / d else 0.0
            val uy = if (d > 0) by / d else 0.0
            var end = hi
            for (q in lo until hi) {
                val far2 = d2[q - lo]
                if (far2 > ball * ball) continue
                if (bend < 2 * radius) {
                    val far = sqrt(far2)
                    val wx = vx[q - lo] / far - ux
                    val wy = vy[q - lo] / far - uy
                    if (far - d > sqrt(wx * wx + wy * wy) * radius + bend + SLACK) continue
                }
                lists[end++] = lists[q]
            }
            return end
        }

        /**
         * Measures the samples i0..i1 by j0..j1 into [out]. `lists[lo until hi]` holds every edge that
         * is nearest to some point of the block. The block's middle sample is measured against all of
         * them; that rules most of them out for the rest of the block, and each quarter goes on with
         * the shorter list.
         */
        private fun fill(out: DoubleArray, i0: Int, i1: Int, j0: Int, j1: Int, lo: Int, hi: Int) {
            if ((i1 - i0 < 2 && j1 - j0 < 2) || hi - lo < 2) {
                for (j in j0..j1) for (i in i0..i1) out[j * g.nx + i] = sqrt(nearest(lo, hi, atX(i), atY(j)))
                return
            }
            val im = (i0 + i1) shr 1
            val jm = (j0 + j1) shr 1
            val d = sqrt(survey(lo, hi, atX(im), atY(jm)))
            out[jm * g.nx + im] = d
            val end = narrow(lo, hi, d, radiusOf(g.step, i0, i1, j0, j1))
            fill(out, i0, im, j0, jm, hi, end)
            if (im < i1) fill(out, im + 1, i1, j0, jm, hi, end)
            if (jm < j1) {
                fill(out, i0, im, jm + 1, j1, hi, end)
                if (im < i1) fill(out, im + 1, i1, jm + 1, j1, hi, end)
            }
        }

        /**
         * [fill] for [widest]: returns the larger of [known] and the largest distance of an inside
         * sample of the block, skipping blocks that cannot hold one. [inside]: the block is known to
         * be inside as a whole.
         */
        private fun peak(rows: EdgeBands, known: Double, inside: Boolean, i0: Int, i1: Int, j0: Int, j1: Int, lo: Int, hi: Int): Double {
            var best = known
            var whole = inside
            if ((i1 - i0 < 2 && j1 - j0 < 2) || hi - lo < 2) {
                for (j in j0..j1) for (i in i0..i1) {
                    val d = sqrt(nearest(lo, hi, atX(i), atY(j)))
                    if (d > best && (whole || rows.insideRowAt(atX(i), atY(j)))) best = d
                }
                return best
            }
            val im = (i0 + i1) shr 1
            val jm = (j0 + j1) shr 1
            val x = atX(im)
            val y = atY(jm)
            val d = sqrt(survey(lo, hi, x, y))
            val radius = radiusOf(g.step, i0, i1, j0, j1)
            // The same two ways out as for a tile (see widest).
            if (d + radius + SLACK < best) return best
            if (!whole && d > radius + SLACK) {
                if (!rows.insideRowAt(x, y)) return best
                whole = true
            }
            if (d > best && (whole || rows.insideRowAt(x, y))) best = d
            val end = narrow(lo, hi, d, radius)
            best = peak(rows, best, whole, i0, im, j0, jm, hi, end)
            if (im < i1) best = peak(rows, best, whole, im + 1, i1, j0, jm, hi, end)
            if (jm < j1) {
                best = peak(rows, best, whole, i0, im, jm + 1, j1, hi, end)
                if (im < i1) best = peak(rows, best, whole, im + 1, i1, jm + 1, j1, hi, end)
            }
            return best
        }
    }
}
