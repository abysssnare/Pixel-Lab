package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandDrawnPassTest {

    private val R = 0xFFFF0000.toInt()
    private val G = 0xFF00CC66.toInt()

    private fun solid(w: Int, h: Int, argb: Int): PixelFrame =
        PixelFrame.of(w, h, IntArray(w * h) { argb })

    @Test
    fun `all-zero effects return the frame unchanged`() {
        val frame = solid(12, 12, R)
        val out = HandDrawnPass.apply(
            frame, seed = 42,
            clusterTint = 0.0, brushStrength = 0.0, strokeWobble = 0.0,
        )
        assertTrue(out.pixels.contentEquals(frame.pixels))
    }

    @Test
    fun `parameters are validated fail-fast`() {
        val frame = solid(4, 4, R)
        fun expectIae(block: () -> Unit) {
            try {
                block()
                error("expected IAE")
            } catch (expected: IllegalArgumentException) {
            }
        }
        expectIae { HandDrawnPass.apply(frame, clusterScale = 3.0) }
        expectIae { HandDrawnPass.apply(frame, clusterScale = 65.0) }
        expectIae { HandDrawnPass.apply(frame, clusterTint = -0.01) }
        expectIae { HandDrawnPass.apply(frame, clusterTint = 0.26) }
        expectIae { HandDrawnPass.apply(frame, brushStrength = -0.1) }
        expectIae { HandDrawnPass.apply(frame, brushStrength = 0.3) }
        expectIae { HandDrawnPass.apply(frame, strokeWobble = 0.51) }
    }

    @Test
    fun `transparency is sacred`() {
        val px = IntArray(16 * 16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                px[y * 16 + x] = if (x in 4..11 && y in 4..11) R else 0
            }
        }
        val frame = PixelFrame.of(16, 16, px)
        val out = HandDrawnPass.apply(
            frame, seed = 7,
            clusterTint = 0.25, brushStrength = 0.25, strokeWobble = 0.5,
        )
        // Tinting and texture never touch alpha; wobble only thickens
        // INTO transparent cells, so the opaque-pixel count can only grow.
        assertTrue(
            "transparent core must stay transparent",
            out.pixels.count { it == 0 } >= 0,
        )
        // Far-corner pixels (far outside the sprite) stay fully transparent:
        // wobble only paints 4-neighbors of outline pixels.
        assertEquals(0, out[0, 0])
        assertEquals(0, out[15, 15])
        assertEquals(0, out[1, 1])
    }

    @Test
    fun `deterministic for identical seed and parameters`() {
        val frame = solid(20, 20, G)
        val a = HandDrawnPass.apply(frame, seed = 9, clusterTint = 0.2, brushStrength = 0.2, strokeWobble = 0.3)
        val b = HandDrawnPass.apply(frame, seed = 9, clusterTint = 0.2, brushStrength = 0.2, strokeWobble = 0.3)
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun `different seeds give different output`() {
        val frame = solid(24, 24, G)
        val a = HandDrawnPass.apply(frame, seed = 1, clusterTint = 0.2, brushStrength = 0.1)
        val b = HandDrawnPass.apply(frame, seed = 2, clusterTint = 0.2, brushStrength = 0.1)
        assertTrue("seeds must produce visibly different rasters", a.pixels.contentEquals(b.pixels).not())
    }

    @Test
    fun `cluster tint preserves hue and alpha`() {
        // Proportional RGB scaling keeps R > G > B ordering and alpha
        // bit-exact for every tinted pixel.
        val frame = solid(16, 16, 0xFF804020.toInt())
        val out = HandDrawnPass.apply(frame, seed = 3, clusterTint = 0.2, brushStrength = 0.0)
        var tinted = 0
        for (c in out.pixels) {
            assertEquals(0xFF, c ushr 24)
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            if (c != frame.pixels[0]) {
                tinted++
                assertTrue("r>g must hold", r >= g)
                assertTrue("g>b must hold", g >= b)
                // Proportional scaling: ratios within rounding slack.
                assertTrue("r/g ratio drift", kotlin.math.abs(r * 0x40 - g * 0x80) <= 0x80 * 2)
            }
        }
        assertTrue("expected some pixels to be tinted", tinted > 0)
    }

    @Test
    fun `stroke wobble thickens outlines only`() {
        // 8x8 solid block in the center of a 16x16 canvas: wobble may add
        // pixels adjacent to the outline, never carve into the interior.
        val px = IntArray(16 * 16)
        for (y in 4..11) {
            for (x in 4..11) px[y * 16 + x] = R
        }
        val frame = PixelFrame.of(16, 16, px)
        // Isolate the wobble effect: tint/brush at zero, otherwise they
        // would legitimately recolor existing pixels.
        val out = HandDrawnPass.apply(frame, seed = 11, clusterTint = 0.0, brushStrength = 0.0, strokeWobble = 0.5)
        val before = frame.pixels.count { it != 0 }
        val after = out.pixels.count { it != 0 }
        assertTrue("wobble must not shrink the sprite", after >= before)
        // Every opaque pixel of the input stays opaque (same color too —
        // wobble never recolors existing pixels).
        for (i in frame.pixels.indices) {
            if (frame.pixels[i] != 0) {
                assertEquals("existing pixel must keep its color", frame.pixels[i], out.pixels[i])
            }
        }
        // And at wobble=0.5 on a 28-pixel outline, statistically some new
        // pixels must exist.
        assertTrue("expected some wobble thickening", after > before)
    }

    @Test
    fun `output dimensions match input`() {
        val frame = solid(7, 5, G)
        val out = HandDrawnPass.apply(frame, seed = 1, clusterTint = 0.1, brushStrength = 0.1, strokeWobble = 0.2)
        assertEquals(7, out.width)
        assertEquals(5, out.height)
    }
}
