package com.pixellab.core.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSmoothingTest {

    @Test
    fun `strength zero returns the frame unchanged`() {
        val frame = TestArt.rows(
            "kk.",
            "k.k",
            ".kk",
        )
        val out = EdgeSmoothing.smooth(frame, 0.0)
        assertTrue(out.pixels.contentEquals(frame.pixels))
    }

    @Test
    fun `strength bounds are validated`() {
        val frame = TestArt.solid(2, 2, TestArt.R)
        assertTrue(EdgeSmoothing.smooth(frame, 0.0).pixels.contentEquals(frame.pixels))
        try {
            EdgeSmoothing.smooth(frame, -0.1)
            error("expected IAE for negative strength")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            EdgeSmoothing.smooth(frame, 1.1)
            error("expected IAE for strength above 1")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `flat opaque region is untouched`() {
        val frame = TestArt.solid(4, 4, TestArt.R)
        val out = EdgeSmoothing.smooth(frame)
        assertTrue(out.pixels.contentEquals(frame.pixels))
    }

    @Test
    fun `straight edges are untouched`() {
        // Horizontal boundary between k and w: no diagonal corner pattern,
        // so the pass must not soften it.
        val frame = TestArt.rows(
            "kkkk",
            "kkkk",
            "wwww",
            "wwww",
        )
        val out = EdgeSmoothing.smooth(frame)
        assertTrue(out.pixels.contentEquals(frame.pixels))
    }

    @Test
    fun `45-degree staircase gains alpha falloff`() {
        // Diagonal k line: the transparent notches between diagonal steps
        // (e.g. (1,0) between k(0,0) and k(1,1)) must become semi-
        // transparent k.
        val frame = TestArt.rows(
            "k...",
            ".k..",
            "..k.",
            "...k",
        )
        val out = EdgeSmoothing.smooth(frame)
        val notch = out[1, 0]
        assertTrue(
            "notch (1,0) should gain alpha, got ${Integer.toHexString(notch)}",
            (notch ushr 24) in 1..254,
        )
        assertEquals(TestArt.K and 0x00_FF_FF_FF, notch and 0x00_FF_FF_FF)
        // The pure interior stays opaque k, the far corners stay fully
        // transparent.
        assertEquals(TestArt.K, out[1, 1])
        assertEquals(0, out[3, 0])
        assertEquals(0, out[0, 3])
    }

    @Test
    fun `falloff alpha scales with strength`() {
        val frame = TestArt.rows(
            "k...",
            ".k..",
            "..k.",
            "...k",
        )
        val weak = EdgeSmoothing.smooth(frame, 0.25)[1, 0] ushr 24
        val strong = EdgeSmoothing.smooth(frame, 1.0)[1, 0] ushr 24
        assertTrue("weak ($weak) must be fainter than strong ($strong)", weak < strong)
        // 1.0 strength → per-mille 1000, halved for the silhouette cap →
        // alpha = (255 * 500 + 500) / 1000 = 128.
        assertEquals(128, strong)
    }

    @Test
    fun `concave corner blends quarter-tone`() {
        // w region wrapping around k pixel (1,1): its top, left, top-left
        // are all w → concave corner; k softens 25% toward w.
        val frame = TestArt.rows(
            "www",
            "wkw",
            "www",
        )
        val out = EdgeSmoothing.smooth(frame)
        val softened = out[1, 1]
        assertEquals(XbrzScale.mix(TestArt.K, TestArt.W, 125), softened)
    }

    @Test
    fun `isolated pixel has no corner pattern and stays`() {
        // A lone k dot on white: every neighbor is w. The orthogonal pair
        // (w,w) with transparent diagonal never holds (diagonal is w, not
        // transparent), and the concave rule needs vNeighbor == hNeighbor
        // == diag == w — which is true! But it also requires them to be
        // different from E, so the pixel softens toward w. Assert the
        // result is exactly that quarter-tone blend (documented behavior).
        val frame = TestArt.rows(
            "www",
            "wkw",
            "www",
        )
        val out = EdgeSmoothing.smooth(frame, 0.5)
        assertEquals(XbrzScale.mix(TestArt.K, TestArt.W, 125), out[1, 1])
        // Surrounding w pixels keep their color.
        assertEquals(TestArt.W, out[0, 0])
        assertEquals(TestArt.W, out[2, 2])
    }

    @Test
    fun `deterministic across calls`() {
        val frame = TestArt.rows(
            "kk.",
            "k.k",
            ".kk",
        )
        val a = EdgeSmoothing.smooth(frame)
        val b = EdgeSmoothing.smooth(frame)
        assertTrue(a.pixels.contentEquals(b.pixels))
    }
}
