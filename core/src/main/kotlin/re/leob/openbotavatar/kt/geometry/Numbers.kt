package re.leob.openbotavatar.kt.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))

internal fun smoothstep(t: Double): Double {
    val x = clamp(t, 0.0, 1.0)
    return x * x * (3 - 2 * x)
}

/** Orders like JS `a - b`: unlike [Double.compareTo], -0 and 0 tie, so stable sorts keep upstream's order. */
internal val numericOrder: Comparator<Double> = Comparator { a, b -> if (a < b) -1 else if (a > b) 1 else 0 }

internal fun fixed(n: Double, decimals: Int): String {
    if (!n.isFinite()) return n.toString()
    val factor = 10.0.pow(decimals).toLong()
    val scaled = Math.round(n * factor)
    val whole = abs(scaled) / factor
    val fraction = (abs(scaled) % factor).toString().padStart(decimals, '0').trimEnd('0')
    val sign = if (scaled < 0) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

/** A double as a JS template string would print it, for messages and ids. */
internal fun plain(n: Double): String =
    if (n == Math.rint(n) && abs(n) < 1e15) n.toLong().toString() else n.toString()
