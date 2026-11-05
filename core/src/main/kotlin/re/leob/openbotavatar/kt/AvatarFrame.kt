package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.fixed

/** 2D affine matrix in SVG order: x' = a x + c y + e, y' = b x + d y + f. */
public data class AvatarMatrix(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    public operator fun times(n: AvatarMatrix): AvatarMatrix = AvatarMatrix(
        a * n.a + c * n.b,
        b * n.a + d * n.b,
        a * n.c + c * n.d,
        b * n.c + d * n.d,
        a * n.e + c * n.f + e,
        b * n.e + d * n.f + f,
    )

    internal companion object {
        fun translate(x: Double, y: Double) = AvatarMatrix(1.0, 0.0, 0.0, 1.0, x, y)
    }
}

public data class PathBox(val x: Double, val y: Double, val w: Double, val h: Double)

/** How one eye is drawn, in its face plane's coordinates (viewBox units). */
public sealed interface EyeMark {
    public data class Pill(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val radius: Double,
    ) : EyeMark

    /** A quadratic curve, stroked with round caps. */
    public data class Arc(
        val startX: Double,
        val startY: Double,
        val controlX: Double,
        val controlY: Double,
        val endX: Double,
        val endY: Double,
        val strokeWidth: Double,
    ) : EyeMark {
        /** The curve as SVG path data. */
        public val d: String
            get() = "M${fixed(startX, 2)} ${fixed(startY, 2)}Q${fixed(controlX, 2)} ${fixed(controlY, 2)} ${fixed(endX, 2)} ${fixed(endY, 2)}"
    }
}

public sealed interface FrameShape {
    /** Stable across frames for the same cross-section or eye: use it as a key in retained renderers. */
    public val key: String
    public val matrix: AvatarMatrix

    /** [path] is the id of an entry in [AvatarFrame.paths]. */
    public data class Slice(
        override val key: String,
        val path: String,
        override val matrix: AvatarMatrix,
    ) : FrameShape

    public data class Eye(
        override val key: String,
        override val matrix: AvatarMatrix,
        val mark: EyeMark,
    ) : FrameShape
}

/** One cross-section outline, shared by every shape that draws it. Ids are stable for equal geometry and depth. */
public data class FramePath(
    val id: String,
    val d: String,
    val evenOdd: Boolean,
    val box: PathBox,
)

/**
 * One frame flattened onto the screen. Painting [shapes] in order, body paths in [color] and eyes
 * in [ink], inside the [root] transform and a 0 0 100 100 viewBox gives the avatar.
 */
public data class AvatarFrame(
    val name: String,
    val color: String,
    val ink: String,
    val paths: List<FramePath>,
    /** 2D motion (hop, roll, squash) about the base of the body, applied to every shape. */
    val root: AvatarMatrix,
    /** Back to front. */
    val shapes: List<FrameShape>,
)
