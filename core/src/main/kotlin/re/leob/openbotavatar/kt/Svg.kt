package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.clamp
import re.leob.openbotavatar.kt.geometry.fixed

private fun f(n: Double) = fixed(n, 2)

private fun AvatarMatrix.css(): String =
    "matrix(${fixed(a, 4)} ${fixed(b, 4)} ${fixed(c, 4)} ${fixed(d, 4)} ${f(e)} ${f(this.f)})"

private fun escape(s: String): String = buildString {
    for (c in s) if (c in "&<>\"'") append("&#${c.code};") else append(c)
}

private fun EyeMark.svg(ink: String): String = when (this) {
    is EyeMark.Path -> """<path d="$d" fill="$ink"/>"""
    is EyeMark.Arc -> """<path d="$d" fill="none" stroke="$ink" stroke-width="${f(strokeWidth)}" stroke-linecap="round"/>"""
    is EyeMark.Pill -> """<rect x="${f(x)}" y="${f(y)}" width="${f(width)}" height="${f(height)}" rx="${f(radius)}" fill="$ink"/>"""
}

private fun FrameShape.Ribbon.svg(): String {
    val g = gradient
    val key = "${f(g.x1)},${f(g.y1)},${f(g.x2)},${f(g.y2)},${g.stops.joinToString(",")}"
    val id = "oba-g${hashString(key).toUInt().toString(36)}"
    val stops = g.stops.mapIndexed { i, color -> """<stop offset="${f(i.toDouble() / (g.stops.size - 1))}" stop-color="$color"/>""" }.joinToString("")
    return """<linearGradient id="$id" gradientUnits="userSpaceOnUse" x1="${f(g.x1)}" y1="${f(g.y1)}" x2="${f(g.x2)}" y2="${f(g.y2)}">$stops</linearGradient><path d="$d" fill="url(#$id)"/>"""
}

/** The frame as a standalone SVG string, built only from numbers and the renderer's own markup. */
public fun AvatarFrame.toSvg(size: Double? = null): String {
    val dims = size?.takeIf { it.isFinite() && it > 0 }?.let {
        val px = f(clamp(it, 8.0, 4096.0))
        """ width="$px" height="$px""""
    }.orEmpty()
    val label = escape(name)
    return buildString {
        append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"$dims role="img" aria-label="$label" overflow="visible">""")
        append("<title>$label</title><defs>")
        for (p in paths) {
            // A mark's outline is the host's own text: it is escaped. Every other path is serialized from numbers.
            append("""<path id="${p.id}" d="${escape(p.d)}"${if (p.evenOdd) " fill-rule=\"evenodd\"" else ""}/>""")
        }
        append("""</defs><g transform="${root.css()}" fill="$color">""")
        for (s in shapes) {
            when (s) {
                is FrameShape.Slice -> append("""<use href="#${s.path}" transform="${s.matrix.css()}"/>""")
                is FrameShape.Eye -> append("""<g transform="${s.matrix.css()}">${s.mark.svg(ink)}</g>""")
                is FrameShape.Ribbon -> append("""<g transform="${s.matrix.css()}">${s.svg()}</g>""")
            }
        }
        append("</g></svg>")
    }
}
