package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.clamp
import kotlin.math.*

internal data class Rgb(val r: Double, val g: Double, val b: Double)
private fun rgbMix(a: Rgb, b: Rgb, t: Double) = Rgb(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t)
internal fun parseHex(input: String?): Rgb? {
    val s = input?.trim()?.takeIf { Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$").matches(it) } ?: return null
    val h = s.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
    return Rgb(h.substring(0, 2).toInt(16).toDouble(), h.substring(2, 4).toInt(16).toDouble(), h.substring(4, 6).toInt(16).toDouble())
}
internal fun toHex(c: Rgb): String {
    fun h(v: Double) = Math.round(clamp(v, 0.0, 255.0)).toString(16).padStart(2, '0')
    return "#${h(c.r)}${h(c.g)}${h(c.b)}"
}
private fun toOklch(c: Rgb): Triple<Double, Double, Double> {
    fun linear(v: Double): Double { val s = v / 255; return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    val r = linear(c.r); val g = linear(c.g); val b = linear(c.b)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val light = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s
    val a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
    val hue = atan2(bb, a) * 180 / PI
    return Triple(light, hypot(a, bb), if (hue < 0) hue + 360 else hue)
}
private fun oklchLinear(light: Double, chroma: Double, hue: Double): Rgb {
    val a = chroma * cos(hue * PI / 180); val b = chroma * sin(hue * PI / 180)
    val l = (light + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (light - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (light - 0.0894841775 * a - 1.291485548 * b).pow(3)
    return Rgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s)
}
private fun oklch(light: Double, chroma: Double, hue: Double): Rgb {
    fun fits(c: Double): Boolean { val v = oklchLinear(light, c, hue); return listOf(v.r, v.g, v.b).all { it >= -1e-4 && it <= 1 + 1e-4 } }
    var lo = 0.0; var hi = chroma
    if (!fits(hi)) { repeat(20) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }; hi = lo }
    val c = oklchLinear(light, hi, hue)
    fun srgb(v: Double) = 255 * if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    return Rgb(srgb(c.r), srgb(c.g), srgb(c.b))
}
private fun turnToward(h: Double, to: Double, by: Double): Double {
    val d = ((to - h + 540) % 360) - 180
    return (h + sign(d) * min(abs(d), by) + 360) % 360
}
private val palettes = LinkedHashMap<Pair<String?, Int>, List<List<Rgb>>>()
internal fun ribbonPalette(color: String?, seed: Int): List<List<Rgb>> = synchronized(palettes) {
    val key = color to seed
    palettes[key]?.let { return@synchronized it }
    val (light, chroma, hue) = toOklch(parseHex(color) ?: parseHex(DEFAULT_COLOR)!!)
    val neutral = chroma < 0.04
    val base = if (neutral) (130 + noise(seed, 500) * 310) % 360 else hue
    val c = if (neutral) 0.025 else max(chroma, 0.17)
    val from = clamp(light, 0.36, 0.9)
    val steps = ArrayList<Int>()
    for (k in 1 until 10) {
        if (steps.size >= 5) break
        for (step in listOf(k, -k)) {
            val l = from + step * 0.09
            if (steps.size < 5 && l >= 0.36 - 1e-9 && l <= 0.9 + 1e-9) steps.add(step)
        }
    }
    while (steps.size < 5) steps.add(steps.lastOrNull() ?: 1)
    val shades = steps.map { step ->
        val l = from + step * 0.09
        val h = turnToward(base, if (step > 0) 100.0 else 280.0, 14.0 * abs(step))
        val away = if (step > 0) 1 else -1
        List(5) { n ->
            val t = n / 4.0
            val hh = turnToward(h, if (away > 0) 100.0 else 280.0, 25 * t)
            oklch(clamp(l + away * 0.13 * t, 0.26, 0.9), c, hh)
        }
    }
    val palette = listOf(listOf(0, 1, 2), listOf(2, 0, 3), listOf(1, 2, 0)).flatMap { pick ->
        pick.sortedByDescending { steps[it] }.map { shades[it] }
    }
    if (palettes.size >= 64) palettes.clear()
    palettes[key] = palette
    palette
}
internal fun runAt(run: List<Rgb>, t: Double): String {
    val x = clamp(t, 0.0, 1.0) * (run.size - 1); val n = min(floor(x).toInt(), run.size - 2)
    return toHex(rgbMix(run[n], run[n + 1], x - n))
}
