package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.fixed
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** One part of a mark: a fixed outline about its own center, and where that center is this frame. */
@ExperimentalOpenBotAvatarApi
public data class MarkPart(
    /**
     * Stable name of the outline, such as "dot": it names the outline's path in a frame. Keep an
     * outline as it is from frame to frame and move the part with [x], [y], [turn] and [scale]: a
     * live renderer keeps what it builds per outline (a material, a whole model of it).
     */
    val shape: String,
    /** The outline as SVG path data, about its own center. */
    val d: String,
    /** The bounds of [d]. */
    val box: PathBox,
    /** Center, viewBox units. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    /** Rotation about the mark's center, radians. */
    val turn: Double = 0.0,
    /** Scale about the part's own center. */
    val scale: Double = 1.0,
    /**
     * The outline is a circle or an upright capsule that fills [box]. A material draws such a part
     * from a ready ball; for any other outline it builds a model in the outline's shape, once per
     * outline.
     */
    val round: Boolean = false,
)

/** What a mark is laid out from. */
@ExperimentalOpenBotAvatarApi
public class MarkContext internal constructor(
    /** The length every mark is sized in, viewBox units: a share of the body's smaller side. */
    public val unit: Double,
    /** The animation clock, seconds, offset per avatar so several marks do not move together. 0 when [still]. */
    public val time: Double,
    /** Hold the mark at rest (reduced motion). */
    public val still: Boolean,
)

/** What a body can give way to: a value that lays its parts out about its own center. */
@SubclassOptInRequired(ExperimentalOpenBotAvatarApi::class)
public interface AvatarMark {
    /** Names the mark; a pose lists its marks in the order of their ids. */
    public val id: String

    /** The mark's parts fully grown, with their places measured from the mark's center. */
    @ExperimentalOpenBotAvatarApi
    public fun parts(ctx: MarkContext): List<MarkPart>
}

/** A mark of the host's own. */
@ExperimentalOpenBotAvatarApi
public fun AvatarMark(id: String, parts: (MarkContext) -> List<MarkPart>): AvatarMark = FunctionMark(id, parts)

private class FunctionMark(override val id: String, private val layOut: (MarkContext) -> List<MarkPart>) : AvatarMark {
    override fun parts(ctx: MarkContext) = layOut(ctx)
}

/** A mark in a pose, and how far the body has given way to it, 0 to 1. */
public data class PoseMark(val mark: AvatarMark, val weight: Double)

private fun f(n: Double) = fixed(n, 2)

private fun circle(r: Double) = "M${f(r)} 0A${f(r)} ${f(r)} 0 1 1 ${f(-r)} 0A${f(r)} ${f(r)} 0 1 1 ${f(r)} 0Z"

private fun capsule(w: Double, h: Double): String {
    val r = w / 2
    val y = h / 2 - r
    return "M${f(-r)} ${f(-y)}A${f(r)} ${f(r)} 0 0 1 ${f(r)} ${f(-y)}L${f(r)} ${f(y)}A${f(r)} ${f(r)} 0 0 1 ${f(-r)} ${f(y)}Z"
}

/** The exclamation mark: it jumps and shakes every couple of seconds. */
internal val BANG: AvatarMark = AvatarMark("bang") { ctx ->
    val u = ctx.unit
    val barW = 0.21 * u
    val barH = 0.5 * u
    val dot = 0.115 * u
    val gap = 0.1 * u
    val total = barH + gap + 2 * dot
    // One alarm every 2.6 s: a jump, with a shake that dies away as it lands.
    val p = if (ctx.still) 1.0 else ((ctx.time % 2.6) + 2.6) % 2.6 / 2.6
    val alarm = if (p < 0.3) p / 0.3 else 1.0
    val jump = if (ctx.still) 0.0 else sin(PI * alarm)
    val turn = if (ctx.still) 0.0 else 0.2 * sin(2 * PI * 3 * alarm) * (1 - alarm)
    val lift = -0.09 * u * jump
    val barY = -total / 2 + barH / 2
    val dotY = total / 2 - dot
    // The dot lags the bar on the way up, so the mark stretches as it jumps.
    listOf(
        MarkPart("bar", capsule(barW, barH), PathBox(-barW / 2, -barH / 2, barW, barH), y = barY + lift, turn = turn, round = true),
        MarkPart("dot", circle(dot), PathBox(-dot, -dot, 2 * dot, 2 * dot), y = dotY + lift * 0.45, turn = turn, round = true),
    )
}

/** The three dots: they take turns hopping. */
internal val DOTS: AvatarMark = AvatarMark("dots") { ctx ->
    val u = ctx.unit
    val r = 0.1 * u
    val step = 0.31 * u
    List(3) { i ->
        // Each dot hops in turn, a beat after the one before it.
        val p = if (ctx.still) 0.0 else (((ctx.time - i * 0.17) % 1.25) + 1.25) % 1.25 / 1.25
        val hop = if (p < 0.4) sin(PI * (p / 0.4)) else 0.0
        MarkPart("dot3", circle(r), PathBox(-r, -r, 2 * r, 2 * r), x = (i - 1) * step, y = -0.16 * u * hop, scale = 1 + 0.12 * hop, round = true)
    }
}

/**
 * The marks of a pose part way between two, each weight mixed (a mark only one side shows counts as
 * 0 on the other), in the order of their ids so the result does not depend on which side came first.
 * A mark that has gone altogether is left out.
 */
internal fun blendMarks(a: List<PoseMark>, b: List<PoseMark>, mix: (Double, Double) -> Double): List<PoseMark> {
    if (a.isEmpty() && b.isEmpty()) return emptyList()
    fun weight(list: List<PoseMark>, id: String) = list.firstOrNull { it.mark.id == id }?.weight ?: 0.0
    // A mark both sides name is the one being changed to, should the two differ under one id.
    return (b + a).distinctBy { it.mark.id }
        .map { (mark) -> PoseMark(mark, mix(weight(a, mark.id), weight(b, mark.id))) }
        .filter { it.weight > 0 }
        .sortedBy { it.mark.id }
}

/**
 * The marks a state's pose gave, as a pose holds them: one per id, in the order of their ids, each
 * weight within 0..1. An entry without a weight that counts is left out.
 */
internal fun poseMarks(given: List<PoseMark>): List<PoseMark> =
    given.filter { it.weight > 0 }
        .distinctBy { it.mark.id }
        .map { it.copy(weight = min(1.0, it.weight)) }
        .sortedBy { it.mark.id }

/**
 * The parts a mark laid out, as the renderer can draw them. A mark is a host's code, as a state's
 * pose is: a part without an outline or a box of some size is left out, and a place, turn or scale
 * that is not finite is that of a part at rest in the mark's center, so a slip in one field cannot
 * put NaN into a frame.
 */
internal fun markPartsOf(given: List<MarkPart>): List<MarkPart> =
    given.filter { (_, d, box) -> d.isNotEmpty() && box.x.isFinite() && box.y.isFinite() && box.w.isFinite() && box.h.isFinite() && box.w > 0 && box.h > 0 }
        .map { it.copy(x = it.x.finiteOr(0.0), y = it.y.finiteOr(0.0), turn = it.turn.finiteOr(0.0), scale = it.scale.finiteOr(1.0)) }

internal fun Double.finiteOr(other: Double): Double = if (isFinite()) this else other
