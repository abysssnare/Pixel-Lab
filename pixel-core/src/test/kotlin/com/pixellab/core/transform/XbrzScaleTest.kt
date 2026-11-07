package com.pixellab.core.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XbrzScaleTest {

    @Test
    fun `flat frame stays bit-exact at 4x and 6x`() {
        val frame = TestArt.solid(5, 4, TestArt.R)
        for (factor in intArrayOf(4, 6)) {
            val out = if (factor == 4) XbrzScale.xbrz4x(frame) else XbrzScale.xbrz6x(frame)
            assertEquals(5 * factor, out.width)
            assertEquals(4 * factor, out.height)
            assertTrue("flat $factor x output must be uniform", out.pixels.all { it == TestArt.R })
        }
    }

    @Test
    fun `straight vertical edge never invents colors`() {
        // Left half k, right half w: the boundary is a straight line, so
        // every quadrant adjacent to it sees an inline neighbor equal to E
        // and stays pure.
        val frame = TestArt.rows(
            "kkww",
            "kkww",
            "kkww",
            "kkww",
        )
        val out = XbrzScale.xbrz4x(frame)
        assertTrue(
            "straight-edge output must only contain k/w",
            out.pixels.all { it == TestArt.K || it == TestArt.W },
        )
    }

    @Test
    fun `straight horizontal edge never invents colors`() {
        val frame = TestArt.rows(
            "kkkk",
            "kkkk",
            "wwww",
            "wwww",
        )
        val out = XbrzScale.xbrz4x(frame)
        assertTrue(out.pixels.all { it == TestArt.K || it == TestArt.W })
    }

    @Test
    fun `diagonal edge gains bevel shades strictly between the two colors`() {
        // A fully opaque 45° staircase between k (top-left triangle) and
        // w (bottom-right): xBRZ-family scalers bevel exactly these
        // corners. No transparent pixels participate, so every invented
        // shade must be a k/w mix.
        val frame = TestArt.rows(
            "kkkw",
            "kkww",
            "kwww",
            "wwww",
        )
        val out = XbrzScale.xbrz4x(frame)
        val bevels = out.pixels.filter { it != TestArt.K && it != TestArt.W }.toSet()
        assertTrue("expected bevel shades on the staircase", bevels.isNotEmpty())
        // Every bevel shade must be an exact per-mille mix of the two
        // meeting colors — never a third color.
        val allowed = setOf(
            XbrzScale.mix(TestArt.K, TestArt.W, 750),
            XbrzScale.mix(TestArt.K, TestArt.W, 250),
            XbrzScale.mix(TestArt.K, TestArt.W, 500),
        )
        assertTrue(
            "bevel shades must be k/w mixes, found ${bevels.map { Integer.toHexString(it) }.toSet()}",
            bevels.all { it in allowed },
        )
    }

    @Test
    fun `alpha follows the blend for transparent edges`() {
        // k region on transparent background with a diagonal step: the
        // bevel pixels must be semi-transparent k (alpha < 255).
        val frame = TestArt.rows(
            "kk.",
            "k.k",
            ".kk",
        )
        val out = XbrzScale.xbrz4x(frame)
        val semi = out.pixels.filter { (it ushr 24) in 1..254 }
        assertTrue("expected semi-transparent bevels", semi.isNotEmpty())
        assertTrue(
            "semi pixels must keep k's RGB",
            semi.all { (it and 0x00_FF_FF_FF) == (TestArt.K and 0x00_FF_FF_FF) },
        )
    }

    @Test
    fun `one-by-one frame expands to uniform block`() {
        val out = XbrzScale.xbrz6x(TestArt.solid(1, 1, TestArt.B))
        assertEquals(6, out.width)
        assertEquals(6, out.height)
        assertTrue(out.pixels.all { it == TestArt.B })
    }

    @Test
    fun `deterministic across calls`() {
        val frame = TestArt.rows(
            "kkk.",
            "kk.w",
            "k.ww",
            ".www",
        )
        val a = XbrzScale.xbrz4x(frame)
        val b = XbrzScale.xbrz4x(frame)
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun `mix produces the exact midpoint`() {
        val mid = XbrzScale.mix(TestArt.K, TestArt.W, 500)
        assertEquals(0xFF808080.toInt(), mid)
        assertEquals(TestArt.K, XbrzScale.mix(TestArt.K, TestArt.W, 0))
        assertEquals(TestArt.W, XbrzScale.mix(TestArt.K, TestArt.W, 1000))
    }

    @Test
    fun `yuv distance prefers luma-closer colors`() {
        // K vs dark gray is luma-close; K vs pure white is luma-far.
        val gray = 0xFF404040.toInt()
        assertTrue(XbrzScale.yuvDistance(TestArt.K, gray) < XbrzScale.yuvDistance(TestArt.K, TestArt.W))
    }
}
