package re.leob.openbotavatar.kt.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import re.leob.openbotavatar.kt.ExperimentalOpenBotAvatarApi
import re.leob.openbotavatar.kt.SurfaceEye
import re.leob.openbotavatar.kt.ViewBasis
import re.leob.openbotavatar.kt.geometry.Bounds
import kotlin.math.max

/**
 * What a material renders, viewBox units. Equal shapes are the same model, so a material can keep
 * what it builds by its shape.
 */
@ExperimentalOpenBotAvatarApi
public sealed interface MaterialShape {
    public val bounds: Bounds

    /** Front-to-back half extent. */
    public val halfDepth: Double

    /** A body, or a part of a mark in the shape of its outline. */
    public data class Volume(public val volume: re.leob.openbotavatar.kt.volume.Volume) : MaterialShape {
        override val bounds: Bounds get() = volume.bounds
        override val halfDepth: Double get() = max(0.5, volume.parts.maxOfOrNull { it.depth / 2 } ?: 0.0)
    }

    /** A round part of a mark: a ball or an upright capsule centered at the origin. */
    public data class Capsule(override val bounds: Bounds, public val radius: Double) : MaterialShape {
        override val halfDepth: Double get() = radius
    }
}

/** The colors of a shape and the eyes to set into it, left first (none for a mark). */
@ExperimentalOpenBotAvatarApi
public data class MaterialLook(val color: Color, val ink: Color, val eyes: List<SurfaceEye>)

/**
 * A rendered shape. The painter keeps the images of marks and a hardware canvas reads one after it is
 * drawn, so [image] must not change once returned. A tile of a larger image is copied out of it to be
 * drawn; for a body, which is drawn every frame, an image of its own is cheaper.
 */
@ExperimentalOpenBotAvatarApi
public class MaterialImage(
    public val image: ImageBitmap,
    /** The square the shape was rendered to, [size] pixels to the right of and below ([x], [y]). */
    public val x: Int,
    public val y: Int,
    public val size: Int,
    /** The square it covers in the shape's units: [radius] to each side of ([cx], [cy]). */
    public val cx: Double,
    public val cy: Double,
    public val radius: Double,
) {
    /** The whole of a square [image]. */
    public constructor(image: ImageBitmap, cx: Double, cy: Double, radius: Double) :
        this(image, 0, 0, image.width, cx, cy, radius)

    /** The square alone: Android filters across the edge of a source rectangle, into its neighbors. */
    internal val tile: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) {
        if (x == 0 && y == 0 && size == image.width && size == image.height) image
        else Bitmap.createBitmap(image.asAndroidBitmap(), x, y, size, size).asImageBitmap()
    }
}

/**
 * The body's finish. A material is one function: it renders a shape, seen from a view, to an image.
 * The painter asks it for the body (a [MaterialShape.Volume], with the eyes to set into it) and for
 * each part of a state's mark (a [MaterialShape.Capsule] for a round part, a [MaterialShape.Volume]
 * for any other outline, no eyes), and does the rest: it places the images, keeps the marks as
 * sprites and draws the ribbons.
 */
@SubclassOptInRequired(ExperimentalOpenBotAvatarApi::class)
public interface AvatarMaterial {
    public val id: String

    /**
     * How far the body's image reaches outside its projected bounds, viewBox units. Compose paints past
     * layout bounds, so nothing is cut off here; a host that clips reserves this much room.
     */
    public val margin: Double get() = 0.0

    /**
     * Render [shape] at [scale] pixels per viewBox unit. Return null where the material is
     * unavailable or does not draw this kind of shape; the painter falls back to flat paths and eyes.
     * Called while drawing, on every frame for the body.
     */
    @ExperimentalOpenBotAvatarApi
    public fun render(shape: MaterialShape, view: ViewBasis, look: MaterialLook, scale: Double): MaterialImage?
}

/** A material of the host's own. */
@ExperimentalOpenBotAvatarApi
public fun AvatarMaterial(
    id: String,
    margin: Double = 0.0,
    render: (shape: MaterialShape, view: ViewBasis, look: MaterialLook, scale: Double) -> MaterialImage?,
): AvatarMaterial = FunctionMaterial(id, margin, render)

private class FunctionMaterial(
    override val id: String,
    override val margin: Double,
    private val draw: (MaterialShape, ViewBasis, MaterialLook, Double) -> MaterialImage?,
) : AvatarMaterial {
    override fun render(shape: MaterialShape, view: ViewBasis, look: MaterialLook, scale: Double) = draw(shape, view, look, scale)
}
