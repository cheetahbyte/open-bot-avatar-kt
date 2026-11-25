package re.leob.openbotavatar.kt.compose

import androidx.compose.ui.graphics.Color
import re.leob.openbotavatar.kt.FramePath
import re.leob.openbotavatar.kt.ViewBasis
import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.volume.OutlineModel
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow

/** Sprites are rendered a little larger than they are drawn, for the top of a hop. */
private const val HEADROOM = 1.12
private const val MAX_SPRITES = 32

private val FRONT = ViewBasis(cY = 1.0, sY = 0.0, cP = 1.0, sP = 0.0)

/** A part of a mark in a material, and the square it covers in the part's own units. */
internal class MarkSprite(val image: MaterialImage, val cx: Double, val cy: Double, val radius: Double)

/**
 * A model is named by its aspect (a capsule) or its path (an outline): a mark only ever moves,
 * squashes and fades, so one image per model, color and size does for all of it.
 */
private data class SpriteKey(val model: Any, val color: Color, val density: Double)

private class Sprites : LinkedHashMap<SpriteKey, MaterialImage>() {
    override fun removeEldestEntry(eldest: Map.Entry<SpriteKey, MaterialImage>) = size > MAX_SPRITES
}

/** Each material's sprites, shared by every avatar it paints. Main thread only, as the sections are. */
private val sprites = WeakHashMap<AvatarMaterial, Sprites>()

/** Whole powers of two, so a mark that grows or shrinks keeps the same image. */
private fun densityFor(scale: Double) = 2.0.pow(ceil(log2(HEADROOM * scale))).coerceIn(0.5, 8.0)

private fun imageOf(material: AvatarMaterial, name: Any, shape: MaterialShape, color: Color, density: Double): MaterialImage? {
    val kept = sprites.getOrPut(material) { Sprites() }
    val key = SpriteKey(name, color, density)
    return kept[key] ?: material.render(shape, FRONT, MaterialLook(color, color, emptyList()), density)?.also { kept[key] = it }
}

/**
 * One part of a mark in a material, at [scale] pixels per viewBox unit. Null where the material can't
 * draw it (it returned no image, or no volume can be built from the outline): the caller fills the
 * part's path.
 */
internal fun markSprite(material: AvatarMaterial, part: FramePath, scale: Double, color: Color): MarkSprite? =
    if (part.round) capsuleSprite(material, part, scale, color) else outlineSprite(material, part, scale, color)

/** A part that is a ball or a capsule filling its box, from the ready model of that aspect. */
private fun capsuleSprite(material: AvatarMaterial, part: FramePath, scale: Double, color: Color): MarkSprite? {
    val radius = OutlineModel.RADIUS
    val aspect = Math.round(1000 * part.box.h / part.box.w) / 1000.0
    val shape = MaterialShape.Capsule(Bounds(-radius, -radius * aspect, radius, radius * aspect), radius)
    // Drawn units per model unit.
    val unit = part.box.w / (2 * radius)
    val image = imageOf(material, aspect, shape, color, densityFor(scale * unit)) ?: return null
    return MarkSprite(image, 0.0, 0.0, image.radius * unit)
}

/** A part of any outline in its own shape, from a volume built from the outline. */
private fun outlineSprite(material: AvatarMaterial, part: FramePath, scale: Double, color: Color): MarkSprite? {
    val model = OutlineModel.of(part.d, part.box) ?: return null
    val image = imageOf(material, model.key, MaterialShape.Volume(model.volume), color, densityFor(scale / model.scale)) ?: return null
    return MarkSprite(
        image,
        model.cx + (image.cx - OutlineModel.CENTER) / model.scale,
        model.cy + (image.cy - OutlineModel.CENTER) / model.scale,
        image.radius / model.scale,
    )
}
