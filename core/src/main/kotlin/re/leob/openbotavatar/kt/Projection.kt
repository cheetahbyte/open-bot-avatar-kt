package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.volume.EyePlane
import re.leob.openbotavatar.kt.volume.SliceAxis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A view of the body: the cosines and sines of its yaw and pitch.
 *
 * A point (x, y, z) about the body's middle is seen `cY * x + sY * z` to the right and
 * `sY * sP * x + cP * y - cY * sP * z` down, with `cP * (cY * z - sY * x) + sP * y` toward the viewer.
 */
public data class ViewBasis(val cY: Double, val sY: Double, val cP: Double, val sP: Double) {
    /** Above 0 when the front faces the viewer. */
    public val facing: Double get() = cY * cP

    /** How fast the depth of a side plane grows along x. */
    internal val sideFacing: Double get() = -sY * cP

    internal val activeStack: SliceAxis get() = if (abs(cY) >= abs(sY)) SliceAxis.Z else SliceAxis.X

    /** Whether an eye's plane faces the viewer: the projected area of a unit of it goes to zero at the limb. */
    internal fun sees(plane: EyePlane): Boolean =
        sP * -plane.dzdy + cP * (-sY * -plane.dzdx + cY) > 1e-6

    internal fun plane(z: Double, pivot: Point) = AvatarMatrix(
        cY,
        sY * sP,
        0.0,
        cP,
        pivot.x - cY * pivot.x + sY * z,
        pivot.y - cP * pivot.y - sY * sP * pivot.x - cY * sP * z,
    )

    /** Projection of the side plane x = [x0], whose local coordinates are (u = -z, y). */
    internal fun sidePlane(x0: Double, pivot: Point) = AvatarMatrix(
        -sY,
        cY * sP,
        0.0,
        cP,
        pivot.x + cY * (x0 - pivot.x),
        pivot.y - cP * pivot.y + sY * sP * (x0 - pivot.x),
    )

    /** Projection of the plane an eye lies in, tangent to the surface under it. */
    internal fun eyePlane(plane: EyePlane, pivot: Point): AvatarMatrix {
        val m = plane(plane.z - plane.x * plane.dzdx - plane.y * plane.dzdy, pivot)
        return m.copy(
            a = m.a + sY * plane.dzdx,
            b = m.b - cY * sP * plane.dzdx,
            c = m.c + sY * plane.dzdy,
            d = m.d - cY * sP * plane.dzdy,
        )
    }
}

internal fun viewBasis(yaw: Double, pitch: Double) = ViewBasis(cos(yaw), sin(yaw), cos(pitch), sin(pitch))

internal fun pivotOf(b: Bounds) = Point((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2)

internal fun rootMatrix(pose: Pose, b: Bounds): AvatarMatrix {
    val bx = (b.minX + b.maxX) / 2
    val by = b.maxY
    val r = (pose.roll * PI) / 180
    val rotation = AvatarMatrix(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
    val squash = AvatarMatrix(pose.sx, 0.0, 0.0, pose.sy, 0.0, 0.0)
    val about = AvatarMatrix.translate(bx, by) * ((rotation * squash) * AvatarMatrix.translate(-bx, -by))
    return AvatarMatrix.translate(0.0, pose.lift) * about
}
