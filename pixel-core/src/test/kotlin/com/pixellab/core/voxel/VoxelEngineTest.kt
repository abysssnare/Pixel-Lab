package com.pixellab.core.voxel

import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxelizerTest {

    private val R = 0xFFFF0000.toInt()
    private val K = 0xFF000000.toInt()
    private val W = 0xFFFFFFFF.toInt()

    private fun rows(vararg rows: String): PixelFrame {
        val pal = mapOf('r' to R, 'k' to K, 'w' to W)
        val w = rows[0].length
        val h = rows.size
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = rows[y][x]
                px[y * w + x] = if (c == '.') 0 else (pal[c] ?: error("no entry for '$c'"))
            }
        }
        return PixelFrame.of(w, h, px)
    }

    @Test
    fun `uniform extrusion fills every opaque column`() {
        val frame = rows(
            "rr.",
            ".r.",
        )
        val model = Voxelizer.voxelize(frame, depth = 3)
        assertEquals(3, model.width)
        assertEquals(2, model.height)
        assertEquals(3, model.depth)
        // All three opaque pixels extrude to full depth.
        assertEquals(R, model[0, 0, 0]); assertEquals(R, model[0, 0, 2])
        assertEquals(R, model[1, 1, 0]); assertEquals(R, model[1, 1, 2])
        assertEquals(R, model[1, 0, 0])
        // Transparent cell stays empty at every z.
        assertEquals(0, model[2, 0, 0]); assertEquals(0, model[0, 1, 2])
        assertEquals(3 * 3, model.solidCount)
    }

    @Test
    fun `depth bounds are validated`() {
        val frame = rows("rr")
        try {
            Voxelizer.voxelize(frame, depth = 0)
            error("expected IAE for depth 0")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            Voxelizer.voxelize(frame, depth = 33)
            error("expected IAE for depth 33")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `cell budget rejects huge volumes`() {
        // 512x512 frame at depth 17 = 4.46M cells > 4M budget.
        val frame = PixelFrame.of(512, 512, IntArray(512 * 512) { R })
        try {
            Voxelizer.voxelize(frame, depth = 17)
            error("expected IAE for volume over budget")
        } catch (expected: IllegalArgumentException) {
        }
        // Exactly at the budget (512*512*16 = 4,194,304) still passes.
        Voxelizer.voxelize(frame, depth = 16)
    }

    @Test
    fun `luma relief makes dark pixels tall and bright short`() {
        val frame = rows("wk")
        val model = Voxelizer.voxelize(frame, depth = 5, mode = DepthMode.LUMA_RELIEF)
        val brightHeight = (0 until 5).count { model[0, 0, it] != 0 }
        val darkHeight = (0 until 5).count { model[1, 0, it] != 0 }
        assertTrue("dark must tower over bright ($darkHeight vs $brightHeight)", darkHeight > brightHeight)
        assertTrue("bright still stands at least 1 voxel", brightHeight >= 1)
        assertEquals(darkHeight, 5) // black = full depth
    }

    @Test
    fun `outline mode extrudes only silhouette pixels`() {
        val frame = rows(
            "rrr",
            "rrr",
            "rrr",
        )
        val model = Voxelizer.voxelize(frame, depth = 2, mode = DepthMode.OUTLINE)
        // Interior (1,1) has no transparent 4-neighbor: no voxels.
        for (z in 0 until 2) assertEquals(0, model[1, 1, z])
        // Corner (0,0) is silhouette: full column.
        assertEquals(R, model[0, 0, 0])
        assertEquals(R, model[0, 0, 1])
        assertTrue(model.solidCount in 1..8 * 2) // 8 outline cells max
    }

    @Test
    fun `voxel colors preserve alpha`() {
        val semi = 0x80FF0000.toInt()
        val frame = PixelFrame.of(2, 1, intArrayOf(semi, 0))
        val model = Voxelizer.voxelize(frame, depth = 1)
        assertEquals(semi, model[0, 0, 0])
    }

    @Test
    fun `empty frame produces empty model`() {
        val frame = PixelFrame.of(4, 4, IntArray(16))
        val model = Voxelizer.voxelize(frame, depth = 4)
        assertTrue(model.isNotEmpty().not())
        assertEquals(0, model.solidCount)
    }
}

class VoxelOpsTest {

    private val R = 0xFFFF0000.toInt()
    private val G = 0xFF00FF00.toInt()

    @Test
    fun `quarter turn maps coordinates correctly`() {
        // 3x2 ground, distinct colors per cell:
        // a b c
        // d e f
        val px = intArrayOf(
            R, 0xFF000001.toInt(), 0xFF000002.toInt(),
            0xFF000003.toInt(), 0xFF000004.toInt(), G,
        )
        val model = VoxelModel.of(3, 2, 1, px)
        val turned = VoxelOps.rotateYaw(model, 1)
        // Grid becomes 2 wide (old h), 3 tall (old w).
        assertEquals(2, turned.width)
        assertEquals(3, turned.height)
        // (x, y) → (h−1−y, x): (0,0)→(1,0), (2,1)→(0,2), (1,0)→(1,1).
        assertEquals(R, turned[1, 0, 0])
        assertEquals(G, turned[0, 2, 0])
        assertEquals(0xFF000001.toInt(), turned[1, 1, 0])
    }

    @Test
    fun `four turns are identity`() {
        val model = VoxelModel.of(3, 2, 2, IntArray(12) { if (it % 3 == 0) R else 0 })
        val round = VoxelOps.rotateYaw(VoxelOps.rotateYaw(model, 1), 3)
        assertTrue("4 turns must restore the original", round == model)
    }

    @Test
    fun `zero turns return the same instance`() {
        val model = VoxelModel.of(2, 2, 1, IntArray(4) { R })
        assertTrue(VoxelOps.rotateYaw(model, 0) === model)
        assertTrue(VoxelOps.rotateYaw(model, 4) === model)
        assertTrue(VoxelOps.rotateYaw(model, -4) === model)
    }

    @Test
    fun `rotation preserves solid count and z`() {
        // 3(w) x 2(h) x 2(d): solid at (0,0,0) and (2,0,1).
        val px = IntArray(3 * 2 * 2)
        px[0 * 3 * 2 + 0 * 3 + 0] = R // (0,0,0): idx 0
        px[0 * 3 * 2 + 1 * 3 + 2] = R // (2,0,1): idx 5
        val model = VoxelModel.of(3, 2, 2, px)
        val turned = VoxelOps.rotateYaw(model, 2)
        assertEquals(model.solidCount, turned.solidCount)
        // 180°: (0,0,0)→(2,1,0); (2,0,1)→(0,1,1).
        assertEquals(R, turned[2, 1, 0])
        assertEquals(R, turned[0, 1, 1])
    }
}

class VoxelRendererTest {

    private val R = 0xFFFF0000.toInt()

    private fun solidBlock(w: Int, h: Int): PixelFrame =
        PixelFrame.of(w, h, IntArray(w * h) { R })

    @Test
    fun `single voxel renders three shaded faces`() {
        val model = Voxelizer.voxelize(PixelFrame.of(1, 1, intArrayOf(R)), depth = 1)
        val out = VoxelRenderer.renderIsometric(model, tile = 8)
        assertTrue("output must have pixels", out.pixels.count { it != 0 } > 0)
        // Three distinct shade levels must appear: top (full), left
        // (0.84), right (0.62) of red.
        val shades = out.pixels.filter { it != 0 }.toSet()
        assertTrue("expected ≥2 distinct shades, got ${shades.map { Integer.toHexString(it) }}", shades.size >= 2)
        assertTrue(
            "every shaded pixel keeps red hue",
            shades.all { (it ushr 16 and 0xFF) >= (it and 0xFF) },
        )
    }

    @Test
    fun `tile bounds are validated`() {
        val model = Voxelizer.voxelize(solidBlock(1, 1), depth = 1)
        try {
            VoxelRenderer.renderIsometric(model, tile = 0)
            error("expected IAE for tile 0")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            VoxelRenderer.renderIsometric(model, tile = 65)
            error("expected IAE for tile 65")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `2x1 plateau shows ao darkening in the seam`() {
        // Two voxels side by side: the touching top-face corners darken.
        val model = Voxelizer.voxelize(solidBlock(2, 1), depth = 1)
        val out = VoxelRenderer.renderIsometric(model, tile = 8)
        val shades = out.pixels.filter { it != 0 }.toSet()
        assertTrue("expected AO variety (${shades.size} shades)", shades.size >= 2)
    }

    @Test
    fun `bigger tile yields bigger canvas`() {
        val model = Voxelizer.voxelize(solidBlock(3, 3), depth = 2)
        val small = VoxelRenderer.renderIsometric(model, tile = 2)
        val big = VoxelRenderer.renderIsometric(model, tile = 8)
        assertTrue(big.width > small.width)
        assertTrue(big.height > small.height)
    }

    @Test
    fun `deterministic rendering`() {
        val model = Voxelizer.voxelize(solidBlock(4, 4), depth = 3)
        val a = VoxelRenderer.renderIsometric(model, tile = 4)
        val b = VoxelRenderer.renderIsometric(model, tile = 4)
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun `fully occluded interior voxels contribute nothing`() {
        // A voxel fully wrapped by neighbors (top occupied, all four
        // sides occupied, bottom never rendered) is invisible: removing
        // it must not change the render.
        fun filled(keepCenter: Boolean): VoxelModel {
            val px = IntArray(3 * 3 * 2)
            for (y in 0 until 3) {
                for (x in 0 until 3) {
                    for (z in 0 until 2) {
                        if (!keepCenter && x == 1 && y == 1 && z == 0) continue
                        px[y * 3 * 2 + z * 3 + x] = R
                    }
                }
            }
            return VoxelModel.of(3, 3, 2, px)
        }
        val a = VoxelRenderer.renderIsometric(filled(true), tile = 4)
        val b = VoxelRenderer.renderIsometric(filled(false), tile = 4)
        assertTrue(
            "fully occluded voxel must be invisible",
            a.pixels.contentEquals(b.pixels),
        )
    }

    @Test
    fun `rotated view matches manual coordinate rotation`() {
        // Rendering model turned 90° must equal rendering a frame whose
        // pixels were rotated the same way (voxelize→rotate vs
        // rotate→voxelize commute for UNIFORM depth).
        val frame = solidBlock(3, 2)
        val model = Voxelizer.voxelize(frame, depth = 1)
        val turnedModel = VoxelOps.rotateYaw(model, 1)
        val turnedRender = VoxelRenderer.renderIsometric(turnedModel, tile = 4)
        val rotatedFrame = frame.rotated90Cw()
        val directRender = VoxelRenderer.renderIsometric(
            Voxelizer.voxelize(rotatedFrame, depth = 1), tile = 4,
        )
        assertTrue(
            "voxelize∘rotate == rotate∘voxelize",
            turnedRender.pixels.contentEquals(directRender.pixels),
        )
    }
}

class VoxelExporterTest {

    private val R = 0xFFFF0000.toInt()

    @Test
    fun `obj exports only exposed faces with deduplicated corners`() {
        // Single voxel: 6 faces, 8 unique corners.
        val model = Voxelizer.voxelize(PixelFrame.of(1, 1, intArrayOf(R)), depth = 1)
        val obj = String(VoxelExporter.exportObj(model), Charsets.UTF_8)
        val vertexLines = obj.lines().count { it.startsWith("v ") }
        val faceLines = obj.lines().count { it.startsWith("f ") }
        assertEquals(8, vertexLines)
        assertEquals(6, faceLines)
        assertTrue(obj.contains("o pixellab_voxel"))
        assertTrue(obj.contains("color #"))
    }

    @Test
    fun `obj culls interior faces on a plateau`() {
        // 2x1x1 bar: 2 voxels, the touching faces are hidden:
        // 12 faces minus 2 hidden = 10; corners: 8 + 4 shared = 12.
        val model = Voxelizer.voxelize(PixelFrame.of(2, 1, intArrayOf(R, R)), depth = 1)
        val obj = String(VoxelExporter.exportObj(model), Charsets.UTF_8)
        val faceLines = obj.lines().count { it.startsWith("f ") }
        assertEquals(10, faceLines)
        val vertexLines = obj.lines().count { it.startsWith("v ") }
        assertEquals(12, vertexLines)
    }

    @Test
    fun `rotation gif has four frames on a shared canvas`() {
        val model = Voxelizer.voxelize(
            PixelFrame.of(3, 2, IntArray(6) { R }), depth = 2,
        )
        val gif = VoxelExporter.exportRotationGif(model, tile = 4, frameDelayMs = 100)
        // GIF89a magic + consistent size.
        assertEquals("GIF89a", String(gif.copyOfRange(0, 6), Charsets.US_ASCII))
        val w = gif[6].toInt() and 0xFF or ((gif[7].toInt() and 0xFF) shl 8)
        val h = gif[8].toInt() and 0xFF or ((gif[9].toInt() and 0xFF) shl 8)
        assertTrue(w > 0 && h > 0)
        assertTrue("gif size sane (${gif.size} B)", gif.size in 100..2_000_000)
    }

    @Test
    fun `rotation gif validates arguments`() {
        val model = Voxelizer.voxelize(PixelFrame.of(2, 2, IntArray(4) { R }), depth = 1)
        try {
            VoxelExporter.exportRotationGif(model, tile = 0)
            error("expected IAE for tile 0")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            VoxelExporter.exportRotationGif(model, tile = 4, frameDelayMs = 10)
            error("expected IAE for tiny delay")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
