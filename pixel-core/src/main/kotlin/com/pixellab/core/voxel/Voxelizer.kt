package com.pixellab.core.voxel

import com.pixellab.core.model.PixelFrame

/**
 * Turns a flat [PixelFrame] into a [VoxelModel] — the "support 3D" bridge.
 *
 * Every **opaque** frame pixel becomes a column of voxels standing on the
 * ground plane, extruded along +z. Frame "up" (−y) maps to world "up"
 * (+z), so a sprite of a standing character extrudes into a standing
 * statue, not a fallen one.
 *
 * Depth strategies ([DepthMode]):
 *  * **UNIFORM** — solid blocks; the classic sprite-statue look.
 *  * **LUMA_RELIEF** — bright pixels stand short, dark pixels tall: an
 *    instantly readable relief where dark outlines frame bright fills.
 *  * **OUTLINE** — only silhouette pixels extrude; everything else stays
 *    empty (a 3D wireframe of the shape).
 *
 * Deterministic pure function; transparent frame pixels never produce
 * voxels; voxel color = frame pixel color (alpha preserved, so
 * semi-transparent edges extrude as semi-transparent voxels).
 */
object Voxelizer {

    /** Inclusive extrusion depth range. */
    const val MAX_DEPTH = 32

    /**
     * Extrudes [frame] into a voxel model.
     *
     * @param frame source raster; `0x0` (fully transparent) pixels are
     * skipped, semi-transparent ones extrude with their alpha.
     * @param depth extrusion depth in voxels, `1..32`.
     * @param mode how per-pixel luminance participates in the depth; see
     * [DepthMode].
     * @throws IllegalArgumentException if [depth] is out of range or the
     * resulting volume would exceed [VoxelModel.MAX_CELLS].
     */
    fun voxelize(frame: PixelFrame, depth: Int = 4, mode: DepthMode = DepthMode.UNIFORM): VoxelModel {
        require(depth in 1..MAX_DEPTH) { "depth must be in [1, 32] (was $depth)" }
        val w = frame.width
        val h = frame.height
        require(w.toLong() * h * depth <= VoxelModel.MAX_CELLS) {
            "voxel volume ${w}x${h}x${depth} exceeds the 4M-cell budget; " +
                "reduce the depth or the canvas"
        }
        val src = frame.pixels
        val out = IntArray(w * h * depth)

        val outlineMask = if (mode == DepthMode.OUTLINE) outlineMask(src, w, h) else null
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = src[y * w + x]
                if (color == 0) continue
                var columnHeight = depth
                when (mode) {
                    DepthMode.UNIFORM -> {}
                    DepthMode.LUMA_RELIEF -> {
                        // Rec.709 luma 0..255 → 1..depth (dark = tall).
                        val luma = (299 * (color ushr 16 and 0xFF) +
                            587 * (color ushr 8 and 0xFF) +
                            114 * (color and 0xFF)) / 1000
                        columnHeight = 1 + (depth - 1) * (255 - luma) / 255
                        if (columnHeight < 1) columnHeight = 1
                    }
                    DepthMode.OUTLINE -> {
                        if (!outlineMask!![y * w + x]) continue
                    }
                }
                // Ground the column: z = 0 is the floor.
                for (z in 0 until columnHeight) {
                    out[y * w * depth + z * w + x] = color
                }
            }
        }
        return VoxelModel.adopt(w, h, depth, out)
    }

    /** Mask of pixels that touch (4-neighborhood) transparency or the
     * raster edge — the silhouette outline. */
    private fun outlineMask(src: IntArray, w: Int, h: Int): BooleanArray {
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src[y * w + x] == 0) continue
                val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                val transparentNeighbor = edge ||
                    src[y * w + x - 1] == 0 ||
                    src[y * w + x + 1] == 0 ||
                    src[(y - 1) * w + x] == 0 ||
                    src[(y + 1) * w + x] == 0
                mask[y * w + x] = transparentNeighbor
            }
        }
        return mask
    }
}
