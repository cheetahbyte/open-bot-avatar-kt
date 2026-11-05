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
    is EyeMark.Arc -> """<path d="$d" fill="none" stroke="$ink" stroke-width="${f(strokeWidth)}" stroke-linecap="round"/>"""
    is EyeMark.Pill -> """<rect x="${f(x)}" y="${f(y)}" width="${f(width)}" height="${f(height)}" rx="${f(radius)}" fill="$ink"/>"""
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
            append("""<path id="${p.id}" d="${p.d}"${if (p.evenOdd) " fill-rule=\"evenodd\"" else ""}/>""")
        }
        append("""</defs><g transform="${root.css()}" fill="$color">""")
        for (s in shapes) {
            when (s) {
                is FrameShape.Slice -> append("""<use href="#${s.path}" transform="${s.matrix.css()}"/>""")
                is FrameShape.Eye -> append("""<g transform="${s.matrix.css()}">${s.mark.svg(ink)}</g>""")
            }
        }
        append("</g></svg>")
    }
}
