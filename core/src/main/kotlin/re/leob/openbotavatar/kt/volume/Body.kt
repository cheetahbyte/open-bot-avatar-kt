package re.leob.openbotavatar.kt.volume

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.geometry.Polygons
import re.leob.openbotavatar.kt.geometry.bounds
import re.leob.openbotavatar.kt.geometry.filledArea
import re.leob.openbotavatar.kt.geometry.flattenPath
import re.leob.openbotavatar.kt.geometry.insideRow
import re.leob.openbotavatar.kt.geometry.parsePath
import re.leob.openbotavatar.kt.geometry.sampleRange
import re.leob.openbotavatar.kt.geometry.serializePath
import re.leob.openbotavatar.kt.geometry.splitSubpaths
import kotlin.math.floor

internal class BodySubpath(
    val d: String,
    val polys: Polygons,
    /** A point inside the cut-out, on subpaths that cut a hole rather than adding fill. */
    val holeProbe: Point?,
) {
    val hole: Boolean get() = holeProbe != null
}

internal class BodyAnalysis(
    val polys: Polygons,
    val bounds: Bounds,
    val area: Int,
    val subpaths: List<BodySubpath>,
)

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
        var cutProbe: Point? = null
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
                if (!alone && !all[k]) {
                    cut++
                    if (cutProbe == null) cutProbe = Point(xs[k], y)
                }
            }
        }
        val hole = inside > 0 && cut.toDouble() / inside > 0.6
        subpaths.add(BodySubpath(serializePath(sub), own, if (hole) cutProbe else null))
    }
    return BodyAnalysis(polys, b, filledArea(polys, b), subpaths)
}
