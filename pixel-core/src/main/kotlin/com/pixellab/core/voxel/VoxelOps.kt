package com.pixellab.core.voxel

/**
 * Grid-space voxel operations: yaw rotation in 90° steps.
 *
 * Rotating the *data* (not the camera) keeps the grid axis-aligned at
 * every view angle, which is what makes painter-order sorting and
 * neighbor-face culling provably correct for the isometric renderer —
 * and keeps every projected coordinate on the integer 2:1 lattice, so
 * rotated output stays pixel-perfect. A quarter turn is a pure index
 * permutation of the voxel array (transpose + reverse), never a
 * resample.
 */
object VoxelOps {

    /**
     * Returns [model] rotated by [quarterTurns] × 90° around the vertical
     * (z) axis. Turns wrap modulo 4; `0` returns the model itself.
     *
     * One quarter turn maps ground cell (x, y) → (height−1−y, x) — a
     * square-grid rotation that preserves z and every voxel color. The
     * returned grid dimensions swap accordingly (w×h → h×w).
     */
    fun rotateYaw(model: VoxelModel, quarterTurns: Int): VoxelModel {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return model
        val d = model.depth
        var current = model
        repeat(turns) {
            val w = current.width
            val h = current.height
            val src = current.pixels
            // (x, y) → (h−1−y, x): the rotated grid is h wide, w tall.
            val newW = h
            val dst = IntArray(src.size)
            for (z in 0 until d) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val v = src[y * w * d + z * w + x]
                        if (v == 0) continue
                        dst[x * newW * d + z * newW + (h - 1 - y)] = v
                    }
                }
            }
            current = VoxelModel.adopt(newW, w, d, dst)
        }
        return current
    }
}
