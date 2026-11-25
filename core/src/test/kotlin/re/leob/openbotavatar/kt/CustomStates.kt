package re.leob.openbotavatar.kt

import kotlin.Double.Companion.NEGATIVE_INFINITY
import kotlin.Double.Companion.NaN
import kotlin.Double.Companion.POSITIVE_INFINITY
import kotlin.math.sin

/** A question mark's hook about its own center, sized in [u]. */
internal fun hookPath(u: Double): String {
    fun arc(r: Double, large: Int, sweep: Int, x: Double, y: Double) = "A${r * u} ${r * u} 0 $large $sweep ${x * u} ${y * u}"
    return "M${-0.26 * u} ${-0.062 * u}" + arc(0.26, 1, 1, 0.13, 0.163) + arc(0.08, 0, 0, 0.09, 0.232) +
        arc(0.09, 0, 1, -0.09, 0.232) + arc(0.26, 0, 1, 0.04, 0.007) + arc(0.08, 1, 0, -0.08, -0.062) +
        arc(0.09, 0, 1, -0.26, -0.062) + "Z"
}

/** The question mark of the README. */
internal val question = AvatarMark("question") { ctx ->
    val u = ctx.unit
    val w = 0.52 * u
    val h = 0.645 * u
    val r = 0.115 * u
    val gap = 0.1 * u
    val top = -(h + gap + 2 * r) / 2
    val turn = 0.12 * sin(2.4 * ctx.time)
    listOf(
        MarkPart("question-hook", hookPath(u), PathBox(-w / 2, -h / 2, w, h), y = top + h / 2, turn = turn),
        MarkPart("question-dot", "M$r 0A$r $r 0 1 1 ${-r} 0A$r $r 0 1 1 $r 0Z", PathBox(-r, -r, 2 * r, 2 * r), y = -top - r, turn = turn, round = true),
    )
}

internal const val SQUARE = "M-9 -9H9V9H-9Z"
internal const val SMALL = "M-5 -5H5V5H-5Z"

internal fun part(shape: String, d: String = SQUARE, box: PathBox = PathBox(-9.0, -9.0, 18.0, 18.0)) = MarkPart(shape, d, box)

internal fun mark(id: String, vararg parts: MarkPart) = AvatarMark(id) { parts.toList() }

private fun showing(id: String, vararg parts: MarkPart) = mark("custom", *parts).let { m ->
    AvatarStateDefinition(id) { it.base.copy(marks = listOf(PoseMark(m, 1.0))) }
}

private fun giving(id: String, vararg marks: PoseMark) = AvatarStateDefinition(id) { it.base.copy(marks = marks.toList()) }

private val third = mark("third", part("third"))

/** The states scripts/sync-upstream.ts defines in TypeScript, by the key the fixtures name them with. */
internal val customStates: Map<String, AvatarStateDefinition> = mapOf(
    "asking" to giving("asking", PoseMark(question, 1.0)),
    "listening" to AvatarStateDefinition("listening") { it.rest().copy(roll = 6 + it.wave(4.0), squintL = 0.3, squintR = 0.3) },
    "context" to AvatarStateDefinition("context") {
        it.rest(2.0).copy(
            lift = -it.live * it.sway,
            roll = (it.stateAge ?: -1.0) + it.time / 10,
            squintL = if (it.mood == AvatarMood.HAPPY) 0.5 else 0.0,
            squintR = it.t / 100,
            yaw = it.base.yaw + (it.seed.toUInt() % 7u).toInt() / 10.0,
        )
    },
    "idleLean" to AvatarStateDefinition("idle") { it.rest().copy(roll = 12.0) },
    "variant" to AvatarStateDefinition("variant") { stateDefinition(AvatarState.BLOCKED).pose(it).copy(marks = listOf(PoseMark(question, 1.0))) },
    "late" to AvatarStateDefinition("late", startAge = 2.0) { it.base.copy(roll = it.stateAge ?: 0.0) },
    "startNaN" to AvatarStateDefinition("startNaN", startAge = NaN) { it.base },
    "startEarly" to AvatarStateDefinition("startEarly", startAge = -5.0) { it.base },
    "lean2" to AvatarStateDefinition("lean") { it.base.copy(roll = 2.0) },
    "lean12" to AvatarStateDefinition("lean") { it.base.copy(roll = 12.0) },
    "third" to giving("third", PoseMark(third, 1.0)),
    "thirds" to giving("thirds", PoseMark(BANG, 1.0 / 3), PoseMark(DOTS, 1.0 / 3), PoseMark(third, 1.0 / 3)),
    "eyes" to AvatarStateDefinition("eyes") {
        it.base.copy(eyes = EyeLook(0.3, 0.5), blink = 0.4, gazeX = 1.4, ribbons = 0.8, sweep = 2.0, cage = 1.0)
    },
    "oddPose" to AvatarStateDefinition("oddPose") {
        Pose(
            yaw = NaN, pitch = POSITIVE_INFINITY, roll = 4.0, lift = NEGATIVE_INFINITY, sx = NaN, sy = NaN, gazeX = NaN, gazeY = 0.5,
            blink = NaN, squintL = NaN, squintR = 0.2, eyes = EyeLook(1.0, NaN), ribbons = NaN, cage = NaN, sweep = NaN,
        )
    },
    "oddMarks" to giving(
        "oddMarks", PoseMark(third, NaN), PoseMark(third, 0.0), PoseMark(third, -1.0),
        PoseMark(DOTS, 7.0), PoseMark(BANG, 0.5), PoseMark(BANG, 0.2),
    ),
    "oddParts" to showing(
        "oddParts", part("a", ""), part("b", box = PathBox(0.0, 0.0, NaN, 4.0)), part("c", box = PathBox(0.0, 0.0, 0.0, 4.0)),
        part("d", box = PathBox(POSITIVE_INFINITY, 0.0, 4.0, 4.0)), part("whole"),
        part("slip").copy(x = NaN, y = POSITIVE_INFINITY, turn = NaN, scale = NEGATIVE_INFINITY),
        part("moved").copy(x = 3.0, y = -4.0, turn = 0.5, scale = 2.0, round = true),
    ),
    "names" to showing(
        "names", part("0"), part("12"), part("a b"), part("a/b", SMALL), part("line\n"), part(""),
        part("same"), part("same", SMALL), part("same"),
    ),
    "markup" to showing("markup", part("\"><script>alert(1)</script>", "M0 0L5 5Z\"/><script>alert(2)</script>")),
    "markOne" to giving("markOne", PoseMark(mark("m", part("one")), 1.0)),
    "markTwo" to giving("markTwo", PoseMark(mark("m", part("two", SMALL)), 1.0)),
)
