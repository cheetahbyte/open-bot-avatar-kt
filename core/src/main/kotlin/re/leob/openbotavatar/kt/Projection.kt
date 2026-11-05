package re.leob.openbotavatar.kt

import re.leob.openbotavatar.kt.geometry.Bounds
import re.leob.openbotavatar.kt.geometry.Point
import re.leob.openbotavatar.kt.volume.SliceAxis
import re.leob.openbotavatar.kt.volume.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal class ViewBasis(yaw: Double, pitch: Double) {
    val cY = cos(yaw)
    val sY = sin(yaw)
    val cP = cos(pitch)
    val sP = sin(pitch)

    val activeStack: SliceAxis get() = if (abs(cY) >= abs(sY)) SliceAxis.Z else SliceAxis.X

    fun facing(n: Vec3) = sP * n.y + cP * (-sY * n.x + cY * n.z)

    fun plane(z: Double, pivot: Point) = AvatarMatrix(
        cY,
        sY * sP,
        0.0,
        cP,
        pivot.x - cY * pivot.x + sY * z,
        pivot.y - cP * pivot.y - sY * sP * pivot.x - cY * sP * z,
    )

    /** Projection of the side plane x = [x0], whose local coordinates are (u = -z, y). */
    fun sidePlane(x0: Double, pivot: Point) = AvatarMatrix(
        -sY,
        cY * sP,
        0.0,
        cP,
        pivot.x + cY * (x0 - pivot.x),
        pivot.y - cP * pivot.y + sY * sP * (x0 - pivot.x),
    )

    fun depthOf(x: Double, y: Double, z: Double, pivot: Point) =
        sP * (y - pivot.y) + cP * (-sY * (x - pivot.x) + cY * z)
}

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
