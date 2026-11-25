package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.ExperimentalOpenBotAvatarApi
import re.leob.openbotavatar.kt.PathBox
import re.leob.openbotavatar.kt.geometry.EdgeBands
import re.leob.openbotavatar.kt.geometry.PathError
import re.leob.openbotavatar.kt.geometry.Segment
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.filledArea
import re.leob.openbotavatar.kt.geometry.flattenPath
import re.leob.openbotavatar.kt.geometry.forEachEdge
import re.leob.openbotavatar.kt.geometry.hypot2
import re.leob.openbotavatar.kt.geometry.parsePath
import re.leob.openbotavatar.kt.geometry.serializePath
import kotlin.math.max
import kotlin.math.min

/**
 * The outline of a mark's part as a model: a volume in the 100-unit box an avatar's body is built
 * in, the middle of the outline's box at ([CENTER], [CENTER]).
 */
@ExperimentalOpenBotAvatarApi
public class OutlineModel internal constructor(
    /** Equal for outlines that share a model, whatever size they are drawn at. */
    public val key: String,
    public val volume: Volume,
    /** Model units to one of the outline's own. */
    public val scale: Double,
    /** The middle of the outline's box, in its own units. */
    public val cx: Double,
    public val cy: Double,
) {
    public companion object {
        /** Where an outline's middle is in its model, on both axes. */
        public const val CENTER: Double = 50.0

        /** Half the width an outline's stroke is given in its model, and the radius to model a round part with. */
        public const val RADIUS: Double = 12.0

        /** The larger side of an outline's box is at most this long in its model. */
        private const val FIT = 84.0
        private const val CACHE_SIZE = 64

        private fun <K, V> cache() = object : LinkedHashMap<K, V>() {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>) = size > CACHE_SIZE
        }

        private val models = cache<Pair<String, PathBox>, OutlineModel?>()

        /** By the model's own path: one outline at two sizes is one volume. */
        private val volumes = cache<String, Volume>()

        /**
         * The model of the outline [d], or null for one no volume can be built from. [box], the
         * outline's bounds, says where its middle is and how large the model may be. The first build
         * of an outline is slow, as an avatar's is; recent ones are kept.
         */
        public fun of(d: String, box: PathBox): OutlineModel? = synchronized(models) {
            val name = d to box
            if (name !in models) {
                models[name] = try {
                    build(d, box)
                } catch (_: PathError) {
                    null
                }
            }
            models[name]
        }

        private fun build(d: String, box: PathBox): OutlineModel? {
            val segments = parsePath(d, bounded = false)
            val cx = box.x + box.w / 2
            val cy = box.y + box.h / 2
            // Measured at the largest the model may be, whatever size the outline is drawn at: the area
            // that is filled (contours wound opposite ways both count) and the length of its contours.
            val fit = FIT / max(box.w, box.h)
            val polys = flattenPath(moved(segments, cx, cy, fit))
            if (polys.isEmpty()) return null
            var length = 0.0
            forEachEdge(polys) { p, q -> length += hypot2(q.x - p.x, q.y - p.y) }
            // The stroke is made as wide as a round part's model, so a finish reads on both alike.
            val stroke = 2 * filledArea(EdgeBands(polys), bounds(polys)) / length
            val scale = fit * min(1.0, 2 * RADIUS / stroke)
            if (!(stroke > 0) || !scale.isFinite()) return null
            val key = serializePath(moved(segments, cx, cy, scale))
            // A mark has no eyes: the volume only needs somewhere to put them.
            // The outline fits the box; a curve's control points may lie well outside it.
            val volume = volumes.getOrPut(key) { buildVolume(AvatarEyes(CENTER, CENTER, 10.0, 1.0), 1.0, analyzeBody(key, bounded = false)) }
            return OutlineModel(key, volume, scale, cx, cy)
        }

        private fun moved(segments: List<Segment>, cx: Double, cy: Double, scale: Double): List<Segment> {
            fun x(v: Double) = CENTER + (v - cx) * scale
            fun y(v: Double) = CENTER + (v - cy) * scale
            return segments.map { s ->
                when (s) {
                    is Segment.Move -> Segment.Move(x(s.x), y(s.y))
                    is Segment.Line -> Segment.Line(x(s.x), y(s.y))
                    is Segment.Cubic -> Segment.Cubic(x(s.x1), y(s.y1), x(s.x2), y(s.y2), x(s.x), y(s.y))
                    is Segment.Quad -> Segment.Quad(x(s.x1), y(s.y1), x(s.x), y(s.y))
                    is Segment.Arc -> s.copy(rx = s.rx * scale, ry = s.ry * scale, x = x(s.x), y = y(s.y))
                    Segment.Close -> s
                }
            }
        }
    }
}
