package re.leob.openbotavatar.kt.compose

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.AvatarFrame
import re.leob.openbotavatar.kt.AvatarMark
import re.leob.openbotavatar.kt.AvatarMoodBlend
import re.leob.openbotavatar.kt.AvatarState
import re.leob.openbotavatar.kt.AvatarStateDefinition
import re.leob.openbotavatar.kt.FrameOptions
import re.leob.openbotavatar.kt.FrameShape
import re.leob.openbotavatar.kt.MarkPart
import re.leob.openbotavatar.kt.MoodState
import re.leob.openbotavatar.kt.PathBox
import re.leob.openbotavatar.kt.PoseMark
import re.leob.openbotavatar.kt.PreparedAvatar
import re.leob.openbotavatar.kt.ViewBasis
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The painter's side of a material: what it asks for, and where it puts what it gets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaterialTest {
    private val mug = PreparedAvatar.of(AvatarCatalog.definition("mug")!!)
    private val red = Color(0xFFE02020)
    private val green = Color(0xFF20C040)

    /** Image pixels per viewBox unit. */
    private val scale = 4

    private class Call(val shape: MaterialShape, val view: ViewBasis, val look: MaterialLook, val scale: Double)

    /** Fills a square about the middle of each shape's bounds, as large as their larger side, if [draws]. */
    private inner class Filler(private val draws: (MaterialShape) -> Boolean = { true }) : AvatarMaterial {
        override val id = "filler"
        val calls = ArrayList<Call>()

        override fun render(shape: MaterialShape, view: ViewBasis, look: MaterialLook, scale: Double): MaterialImage? {
            calls.add(Call(shape, view, look, scale))
            if (!draws(shape)) return null
            // The image is a tile of a larger bitmap, the rest of which must not show.
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            for (y in 8 until 40) for (x in 10 until 42) bitmap.setPixel(x, y, android.graphics.Color.rgb(0x20, 0xC0, 0x40))
            val b = shape.bounds
            val radius = max(b.maxX - b.minX, b.maxY - b.minY) / 2
            return MaterialImage(bitmap.asImageBitmap(), 10, 8, 32, (b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, radius)
        }
    }

    private fun paint(frame: AvatarFrame, material: AvatarMaterial?, color: Color = red, size: Int = 100 * scale): Bitmap {
        val image = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(size.toFloat(), size.toFloat())) {
            drawAvatarFrame(frame, color, Color.Black, material)
        }
        return image.asAndroidBitmap()
    }

    private fun Bitmap.count(color: Color): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (Color(getPixel(x, y)).isNear(color)) n++
        return n
    }

    private fun Color.isNear(o: Color) = alpha > 0.98f && abs(red - o.red) < 0.02f && abs(green - o.green) < 0.02f && abs(blue - o.blue) < 0.02f

    private fun still(state: AvatarStateDefinition, blend: AvatarMoodBlend? = null, avatar: PreparedAvatar = mug) =
        avatar.frame(FrameOptions(state = state, reducedMotion = true, moodBlend = blend))

    /** A state that shows a mark of these parts. */
    private fun showing(vararg parts: MarkPart): AvatarStateDefinition {
        val mark = AvatarMark("custom") { parts.toList() }
        return AvatarStateDefinition("custom") { it.base.copy(marks = listOf(PoseMark(mark, 1.0))) }
    }

    private fun square(d: String = "M-9 -9H9V9H-9Z", box: PathBox = PathBox(-9.0, -9.0, 18.0, 18.0)) = MarkPart("square", d, box)

    /** Whether the bitmap is green all over the square about (cx, cy) in the given coordinates, and not just outside it. */
    private fun assertSquare(image: Bitmap, cx: Double, cy: Double, radius: Double, place: (Double, Double) -> Pair<Double, Double>) {
        fun at(u: Double, v: Double): Color {
            val (x, y) = place(cx + u * radius, cy + v * radius)
            return Color(image.getPixel((x * scale).toInt(), (y * scale).toInt()))
        }
        val inside = 1 - 1 / (radius * scale) * 2.5
        val outside = 1 + 1 / (radius * scale) * 2.5
        for (u in listOf(-1.0, 0.0, 1.0)) for (v in listOf(-1.0, 0.0, 1.0)) {
            assertTrue("inside at $u, $v", at(u * inside, v * inside).isNear(green))
            if (u != 0.0 || v != 0.0) assertFalse("outside at $u, $v", at(u * outside, v * outside).isNear(green))
        }
    }

    private fun placedBy(frame: AvatarFrame, shape: FrameShape? = null): (Double, Double) -> Pair<Double, Double> {
        val m = if (shape == null) frame.root else frame.root * shape.matrix
        return { x, y -> (m.a * x + m.c * y + m.e) to (m.b * x + m.d * y + m.f) }
    }

    @Test
    fun theBodyIsAskedForAsAVolumeWithItsEyes() {
        val material = Filler()
        val frame = mug.frame(FrameOptions(time = 1.2))
        paint(frame, material)
        val call = material.calls.single()
        assertEquals(MaterialShape.Volume(frame.surface.volume!!), call.shape)
        assertEquals(frame.surface.view, call.view)
        assertEquals(MaterialLook(red, Color.Black, frame.surface.eyes), call.look)
        assertEquals(2, call.look.eyes.size)
        assertEquals(scale.toDouble(), call.scale, 0.0)
    }

    @Test
    fun theBodysImageTakesThePlaceOfItsSlicesAndEyes() {
        val frame = mug.frame(FrameOptions(time = 1.2))
        // The root leans and squashes, so this shows the image is under it.
        assertNotEquals(0.0, frame.root.b, 1e-4)
        val material = Filler()
        val image = paint(frame, material)
        assertEquals(0, image.count(red))
        assertEquals(0, image.count(Color.Black))
        assertEquals(0, image.count(Color.Magenta))
        val b = frame.surface.volume!!.bounds
        assertSquare(image, (b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, max(b.maxX - b.minX, b.maxY - b.minY) / 2, placedBy(frame))
    }

    @Test
    fun theBodysImageShrinksAsItGivesWayToAMark() {
        val frame = still(AvatarState.BLOCKED, AvatarMoodBlend(MoodState(state = AvatarState.IDLE), 0.3))
        assertTrue(frame.surface.scale in 0.2..0.8)
        val image = paint(frame, Filler())
        val b = frame.surface.volume!!.bounds
        val radius = max(b.maxX - b.minX, b.maxY - b.minY) / 2 * frame.surface.scale
        assertSquare(image, (b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, radius, placedBy(frame))
    }

    @Test
    fun roundPartsAreOneCapsuleWithoutEyesKeptAsASprite() {
        val material = Filler()
        val color = Color(0xFF0A0B01)
        val frame = still(AvatarState.THINKING)
        val image = paint(frame, material, color)
        val call = material.calls.single()
        assertTrue(call.shape is MaterialShape.Capsule)
        assertEquals(MaterialLook(color, color, emptyList()), call.look)
        assertEquals(ViewBasis(1.0, 0.0, 1.0, 0.0), call.view)
        assertEquals(0, image.count(color))
        for (shape in frame.shapes) {
            val box = frame.paths.single().box
            assertSquare(image, 0.0, 0.0, box.w / 2, placedBy(frame, shape))
        }
        // Through a hop, and in another avatar's painter, the sprite is the same.
        paint(mug.frame(FrameOptions(state = AvatarState.THINKING, time = 0.2)), material, color)
        paint(mug.frame(FrameOptions(state = AvatarState.THINKING, time = 0.4)), material, color)
        assertEquals(1, material.calls.size)
        // Another color, size or material is another sprite.
        paint(frame, material, Color(0xFF0A0B02))
        assertEquals(2, material.calls.size)
        paint(frame, material, color, size = 400 * scale)
        assertEquals(3, material.calls.size)
        val other = Filler()
        paint(frame, other, color)
        assertEquals(1, other.calls.size)
    }

    @Test
    fun theOldestSpriteGoesOnceThereAreTooMany() {
        val material = Filler()
        val frame = still(AvatarState.THINKING)
        for (n in 0..32) paint(frame, material, Color(0xFF000000 + n))
        assertEquals(33, material.calls.size)
        paint(frame, material, Color(0xFF000000 + 32))
        assertEquals(33, material.calls.size)
        paint(frame, material, Color(0xFF000000))
        assertEquals(34, material.calls.size)
    }

    @Test
    fun aCapsuleHasTheAspectOfItsPart() {
        val material = Filler()
        val frame = still(AvatarState.BLOCKED)
        paint(frame, material)
        val (bar, dot) = material.calls.map { it.shape as MaterialShape.Capsule }
        val box = frame.paths[0].box
        assertEquals(12.0, bar.radius, 0.0)
        assertEquals(box.h / box.w, (bar.bounds.maxY - bar.bounds.minY) / (bar.bounds.maxX - bar.bounds.minX), 1e-3)
        assertEquals(12.0, dot.bounds.maxY, 1e-9)
    }

    @Test
    fun anyOtherPartIsAVolumeInTheShapeOfItsOutline() {
        val material = Filler()
        // Off its own center, so its box's middle is not at the origin.
        val frame = still(showing(square("M-4 -9H14V9H-4Z", PathBox(-4.0, -9.0, 18.0, 18.0)), square().copy(shape = "ball", x = 30.0, round = true)))
        val image = paint(frame, material)
        assertEquals(listOf(true, false), material.calls.map { it.shape is MaterialShape.Volume })
        assertEquals(emptyList<Any>(), material.calls[0].look.eyes)
        assertEquals(1, (material.calls[0].shape as MaterialShape.Volume).volume.parts.size)
        assertEquals(0, image.count(red))
        assertSquare(image, 5.0, 0.0, 9.0, placedBy(frame, frame.shapes[0]))
        assertSquare(image, 0.0, 0.0, 9.0, placedBy(frame, frame.shapes[1]))
        paint(frame, material)
        assertEquals(2, material.calls.size)
    }

    @Test
    fun whereAMaterialDrawsNothingThePaintIsFlat() {
        val none = Filler { false }
        val frames = AvatarState.entries.map { mug.frame(FrameOptions(state = it, time = 1.2, stateAge = 1.2)) } +
            still(showing(square())) + still(AvatarState.BLOCKED, AvatarMoodBlend(MoodState(state = AvatarState.IDLE), 0.48))
        for (frame in frames) assertTrue(paint(frame, null).sameAs(paint(frame, none)))
        assertTrue(none.calls.isNotEmpty())
        // Each shape on its own: capsules drawn and volumes flat, and the other way round.
        val balls = Filler { it is MaterialShape.Capsule }
        assertTrue(paint(still(AvatarState.IDLE), balls).sameAs(paint(still(AvatarState.IDLE), null)))
        assertEquals(0, paint(still(AvatarState.THINKING), balls).count(red))
        val bodies = Filler { it is MaterialShape.Volume }
        assertTrue(paint(still(AvatarState.THINKING), bodies).sameAs(paint(still(AvatarState.THINKING), null)))
        assertEquals(0, paint(still(AvatarState.IDLE), bodies).count(red))
    }

    @Test
    fun anOutlineNoVolumeCanBeBuiltFromIsFilledNotAskedFor() {
        val material = Filler()
        val frame = still(showing(square("M0 0L9 9Z", PathBox(0.0, 0.0, 9.0, 9.0))))
        assertTrue(paint(frame, material).sameAs(paint(frame, null)))
        assertEquals(0, material.calls.size)
    }

    @Test
    fun aFlatAvatarHasNoBodyToFinishButItsMarksDo() {
        val flat = PreparedAvatar.of(mug.definition, depth = 0.0)
        val material = Filler()
        val idle = still(AvatarState.IDLE, avatar = flat)
        assertTrue(paint(idle, material).sameAs(paint(idle, null)))
        assertEquals(0, material.calls.size)
        assertEquals(0, paint(still(AvatarState.THINKING, avatar = flat), material).count(red))
    }

    @Test
    fun ribbonsAreThePaintersOwn() {
        val frame = mug.frame(FrameOptions(state = AvatarState.DONE, stateAge = 1.2, time = 8.0, color = "#1688ff"))
        assertTrue(frame.shapes.any { it is FrameShape.Ribbon && it.front })
        val ribbons = frame.copy(shapes = frame.shapes.filter { it is FrameShape.Ribbon && it.front })
        val alone = paint(ribbons, null)
        val painted = paint(frame, Filler())
        // Every opaque pixel of a front ribbon is there over the body's image.
        for (y in 0 until alone.height) for (x in 0 until alone.width) {
            val p = Color(alone.getPixel(x, y))
            if (p.alpha == 1f) assertTrue("$x, $y", Color(painted.getPixel(x, y)).isNear(p))
        }
    }

    @Test
    fun aMarkIsFilledWithTheOutlineItHasNow() {
        fun covered(vararg parts: MarkPart) = paint(still(showing(*parts)), null).count(red)
        val whole = covered(square())
        assertEquals(whole / 2.0, covered(square("M-4.5 -9H4.5V9H-4.5Z")).toDouble(), whole * 0.03)
        // Two outlines under one name in one frame are both drawn.
        val two = covered(square("M-9 -9H0V9H-9Z"), square("M0 -9H9V9H0Z"))
        assertEquals(whole.toDouble(), two.toDouble(), whole * 0.03)
    }

    @Test
    fun anOutlineThatIsNoPathDataDrawsNothing() {
        for (d in listOf("not a path", "M0 0L5 5Z\"/><script>", "M", "ZZZ", "M0 0Lx y")) {
            paint(still(showing(square(d))), null)
            paint(still(showing(square(d))), Filler())
        }
    }
}
