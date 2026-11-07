package com.pixellab.core.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleFilterTest {

    @Test
    fun `scale one returns the frame itself for every filter`() {
        val frame = TestArt.rows(
            "kkw.",
            "k.ww",
        )
        for (filter in UpscaleFilter.entries) {
            assertTrue("$filter must be identity at scale 1", filter.upscale(frame, 1) === frame)
        }
    }

    @Test
    fun `scale bounds are validated`() {
        val frame = TestArt.solid(2, 2, TestArt.R)
        for (filter in UpscaleFilter.entries) {
            try {
                filter.upscale(frame, 0)
                error("expected IAE for scale 0 in $filter")
            } catch (expected: IllegalArgumentException) {
            }
            try {
                filter.upscale(frame, 17)
                error("expected IAE for scale 17 in $filter")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `every filter hits the exact target dimensions for every scale`() {
        val frame = TestArt.rows(
            "kkww",
            "kkww",
            "rrkk",
        )
        for (filter in UpscaleFilter.entries) {
            for (scale in 2..16) {
                val out = filter.upscale(frame, scale)
                assertEquals("$filter at $scale width", frame.width * scale, out.width)
                assertEquals("$filter at $scale height", frame.height * scale, out.height)
            }
        }
    }

    @Test
    fun `nearest is bit-exact duplication`() {
        val frame = TestArt.rows(
            "kw",
            "wk",
        )
        val out = UpscaleFilter.NEAREST.upscale(frame, 8)
        // Every 8x8 block must be uniform and equal to its source pixel.
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val color = frame[x, y]
                for (dy in 0 until 8) {
                    for (dx in 0 until 8) {
                        assertEquals(color, out[x * 8 + dx, y * 8 + dy])
                    }
                }
            }
        }
    }

    @Test
    fun `scale2x exact factor matches the direct algorithm`() {
        val frame = TestArt.rows(
            "kkw.",
            "k.ww",
            "rkk.",
        )
        val direct = Scale2x.scale2x(frame)
        val routed = UpscaleFilter.SCALE2X.upscale(frame, 2)
        assertTrue(direct.pixels.contentEquals(routed.pixels))
    }

    @Test
    fun `xbrz6x exact factor matches the direct algorithm`() {
        val frame = TestArt.rows(
            "kkw.",
            "k.ww",
            "rkk.",
        )
        val direct = XbrzScale.xbrz6x(frame)
        val routed = UpscaleFilter.XBRZ6X.upscale(frame, 6)
        assertTrue(direct.pixels.contentEquals(routed.pixels))
    }

    @Test
    fun `hybrid keeps flat interiors flat`() {
        // A fully uniform frame must stay visually uniform through the
        // hybrid supersample + box-reduction path. The box filter's
        // premultiply/un-premultiply round-trip can drift each channel by
        // ±1 on non-integer coverage, so the assertion tolerates exactly
        // that (anything more would be a real leak of foreign color).
        val frame = TestArt.solid(3, 3, TestArt.G)
        val out = UpscaleFilter.HYBRID_AA.upscale(frame, 5)
        assertEquals(15, out.width)
        val g = TestArt.G
        fun near(c: Int): Boolean {
            fun ch(shift: Int) = kotlin.math.abs(((c ushr shift) and 0xFF) - ((g ushr shift) and 0xFF))
            return ch(24) == 0 && ch(16) <= 1 && ch(8) <= 1 && ch(0) <= 1
        }
        assertTrue("hybrid on uniform frame must stay uniform (±1)", out.pixels.all { near(it) })
    }

    @Test
    fun `hybrid produces sub-pixel shades on diagonal edges`() {
        // The whole point of HYBRID_AA: a diagonal edge at a non-matching
        // scale gets area-averaged coverage, i.e. shades beyond the pure
        // xBRZ bevel table.
        val frame = TestArt.rows(
            "kkk.",
            "kk.w",
            "k.ww",
            ".www",
        )
        val out = UpscaleFilter.HYBRID_AA.upscale(frame, 3)
        val shades = out.pixels.filter { it != TestArt.K && it != TestArt.W && it != 0 }.toSet()
        assertTrue("expected coverage shades, got none", shades.isNotEmpty())
        // All shades still lie between k and w in luma terms.
        val luma: (Int) -> Int = { c -> 299 * (c ushr 16 and 0xFF) + 587 * (c ushr 8 and 0xFF) + 114 * (c and 0xFF) }
        val lumaK = luma(TestArt.K)
        val lumaW = luma(TestArt.W)
        assertTrue(shades.all { luma(it) in minOf(lumaK, lumaW)..maxOf(lumaK, lumaW) })
    }

    @Test
    fun `deterministic across calls`() {
        val frame = TestArt.rows(
            "kkw.",
            "k.ww",
            "rkk.",
        )
        for (filter in UpscaleFilter.entries) {
            val a = filter.upscale(frame, 7)
            val b = filter.upscale(frame, 7)
            assertTrue("$filter must be deterministic", a.pixels.contentEquals(b.pixels))
        }
    }
}
