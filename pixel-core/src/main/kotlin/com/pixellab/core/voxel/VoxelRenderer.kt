package com.pixellab.core.voxel

import com.pixellab.core.model.PixelFrame

/**
 * Software isometric voxel renderer — pixel-art-native 3D.
 *
 * Renders a [VoxelModel] through the classic 2:1 dimetric projection
 * every isometric pixel-art game uses:
 *
 * ```
 * screenX = originX + (x − y) · tile
 * screenY = originY + (x + y) · halfTile − z · tile
 * ```
 *
 * The camera is fixed (viewing direction (−1, −1, +1)); view angles are
 * produced by [VoxelOps.rotateYaw] on the *data* — a pure index
 * permutation that keeps the grid axis-aligned, so the projection stays
 * on the integer 2:1 lattice and the output keeps crisp pixel-art edges.
 * A rendered 3D sprite that still reads as pixel art, not a smoothed
 * OpenGL screenshot.
 *
 * Shading (fixed, deterministic, hand-tuned for readability):
 *  * top faces — full color;
 *  * the two visible side faces — 0.84 / 0.62 luminance (light from
 *    upper-left, like every hand-shaded iso sprite);
 *  * **voxel ambient occlusion** (the classic 0fps corner rule): each
 *    face darkens by the occupancy of its three occluder cells — four
 *    discrete levels, flat per face, preserving the hard voxel look
 *    while giving corners real depth.
 *
 * Painter's algorithm: solid voxels draw back-to-front by `(x + y)`
 * then `z`; hidden faces are culled by neighbor occupancy, so the fill
 * cost is proportional to the *visible* surface, not the volume.
 */
object VoxelRenderer {

    /** Luminance factor of the top faces. */
    private const val TOP_SHADE = 1.0

    /** Luminance factor of the +y (screen-left) side faces. */
    private const val LEFT_SHADE = 0.84

    /** Luminance factor of the +x (screen-right) side faces. */
    private const val RIGHT_SHADE = 0.62

    /** AO brightness per occlusion level (0 = fully open … 3 = cornered). */
    private val AO_FACTORS = doubleArrayOf(1.0, 0.87, 0.74, 0.61)

    /** Screen pixels per voxel edge, 1..64. */
    const val MAX_TILE = 64

    /**
     * Renders [model] from the fixed dimetric camera with a voxel edge of
     * [tile] screen pixels.
     *
     * @param tile screen pixels per voxel edge, `1..64`. The vertical
     * half-step is `(tile + 1) / 2`, keeping the lattice integral.
     * @return a transparent-background [PixelFrame] tightly bounding the
     * rendered model.
     * @throws IllegalArgumentException if [tile] is out of range or the
     * output raster would exceed the 268M-pixel budget.
     */
    fun renderIsometric(model: VoxelModel, tile: Int = 4): PixelFrame {
        require(tile in 1..MAX_TILE) { "tile must be in [1, 64] (was $tile)" }
        val w = model.width
        val h = model.height
        val d = model.depth
        val halfTile = (tile + 1) / 2

        // Output bounds (with one tile of slack for face overhang).
        val outW = (w + h) * tile + tile
        val outH = ((w + h) / 2 + 1 + d) * tile + tile
        val outPixels = outW.toLong() * outH
        require(outPixels <= 268_435_456L) {
            "isometric output ${outW}x${outH} exceeds the 268M pixel budget; reduce the tile size"
        }
        val out = IntArray(outPixels.toInt())
        // Anchor the grid center horizontally; the tallest voxel top sits
        // one tile below the top edge.
        val originX = h * tile + tile / 2
        val originY = tile / 2 + d * tile

        // Painter order: (x + y) ascending (back → front), z ascending
        // within a diagonal. Iterating y outer / x inner / z innermost
        // already satisfies it: (x+y) grows monotonically across the
        // double loop, and same-diagonal columns never occlude each other
        // vertically (z order is only an intra-column detail — top faces
        // of the same column do not overlap).
        val px = model.pixels
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (z in 0 until d) {
                    val color = px[y * w * d + z * w + x]
                    if (color == 0) continue
                    val alpha = color ushr 24
                    // Top-face center on screen.
                    val sx = originX + (x - y) * tile
                    val sy = originY + (x + y) * halfTile - z * tile

                    // Top face: visible when the voxel above is empty.
                    if (z == d - 1 || px[y * w * d + (z + 1) * w + x] == 0) {
                        val ao = topAo(model, x, y, z + 1)
                        fillDiamond(out, outW, outH, sx, sy, tile, halfTile, shade(color, TOP_SHADE * ao, alpha))
                    }
                    // Screen-left side face (+y direction): visible when
                    // the y+1 neighbor is empty.
                    if (y == h - 1 || px[(y + 1) * w * d + z * w + x] == 0) {
                        val ao = leftAo(model, x, y + 1, z)
                        fillLeftFace(out, outW, outH, sx, sy, tile, halfTile, shade(color, LEFT_SHADE * ao, alpha))
                    }
                    // Screen-right side face (+x direction): visible when
                    // the x+1 neighbor is empty.
                    if (x == w - 1 || px[y * w * d + z * w + x + 1] == 0) {
                        val ao = rightAo(model, x + 1, y, z)
                        fillRightFace(out, outW, outH, sx, sy, tile, halfTile, shade(color, RIGHT_SHADE * ao, alpha))
                    }
                }
            }
        }
        return PixelFrame.adopt(outW, outH, out)
    }

    // ---- ambient occlusion (0fps corner rule) ---------------------------

    /**
     * AO of a top face whose outside plane is at height [z]: the four
     * diamond corners (NW/NE/SE/SW grid corners) each darken by their two
     * orthogonal neighbors plus the diagonal at that height. Returns the
     * mean of the four corner levels as a brightness factor.
     */
    private fun topAo(model: VoxelModel, x: Int, y: Int, z: Int): Double {
        var sum = 0
        sum += cornerLevel(model, x, y, z, -1, 0, 0, -1) // NW corner
        sum += cornerLevel(model, x, y, z, 1, 0, 0, -1) // NE corner
        sum += cornerLevel(model, x, y, z, 1, 0, 0, 1) // SE corner
        sum += cornerLevel(model, x, y, z, -1, 0, 0, 1) // SW corner
        return AO_FACTORS[(sum + 2) / 4]
    }

    /**
     * AO of a screen-left side face whose outside plane is at y = [y]:
     * occluders are the two lateral neighbors (x−1, y) and (x+1, y) at
     * the face height — the vertical analogue of the corner rule.
     */
    private fun leftAo(model: VoxelModel, x: Int, y: Int, z: Int): Double {
        val a = model[x - 1, y, z] != 0
        val b = model[x + 1, y, z] != 0
        val level = when {
            a && b -> 3
            a || b -> 2
            else -> 0
        }
        return AO_FACTORS[level]
    }

    /** AO of a screen-right side face whose outside plane is at x = [x]. */
    private fun rightAo(model: VoxelModel, x: Int, y: Int, z: Int): Double {
        val a = model[x, y - 1, z] != 0
        val b = model[x, y + 1, z] != 0
        val level = when {
            a && b -> 3
            a || b -> 2
            else -> 0
        }
        return AO_FACTORS[level]
    }

    /**
     * One corner of a horizontal face: occluders at the two orthogonal
     * neighbors (dxA,dyA) and (dxB,dyB) plus their diagonal, evaluated at
     * height [z]. Level 3 when both sides are occupied, else the count.
     */
    private fun cornerLevel(model: VoxelModel, x: Int, y: Int, z: Int, dxA: Int, dyA: Int, dxB: Int, dyB: Int): Int {
        val sideA = model[x + dxA, y + dyA, z] != 0
        val sideB = model[x + dxB, y + dyB, z] != 0
        if (sideA && sideB) return 3
        val corner = model[x + dxA + dxB, y + dyA + dyB, z] != 0
        return (if (sideA) 1 else 0) + (if (sideB) 1 else 0) + (if (corner) 1 else 0)
    }

    // ---- rasterization ---------------------------------------------------

    /**
     * Fills the top-face diamond centered at (cx, cy): half-width [tile],
     * half-height [halfTile] — the 2:1 iso top of a voxel. Adjacent voxel
     * diamonds share their 45° edge pixels exactly (same floor division),
     * so the surface tiles with no gaps or double-width seams.
     */
    private fun fillDiamond(
        out: IntArray, outW: Int, outH: Int,
        cx: Int, cy: Int, tile: Int, halfTile: Int, color: Int,
    ) {
        for (dy in -halfTile..halfTile) {
            val row = cy + dy
            if (row < 0 || row >= outH) continue
            // |dx|/tile + |dy|/halfTile ≤ 1 → span = tile·(1 − |dy|/halfTile)
            val span = (halfTile - kotlin.math.abs(dy)) * tile / halfTile
            for (dx in -span..span) {
                val col = cx + dx
                if (col in 0 until outW) out[row * outW + col] = color
            }
        }
    }

    /**
     * Fills the screen-left face: a vertical parallelogram hanging below
     * the left half of the top diamond. Corners (relative to the top-face
     * center): W (−tile, 0), S (0, +halfTile), plus both shifted down by
     * [tile]. The 45° edges use the same floor division as the diamond,
     * so side and top faces meet pixel-exactly.
     */
    private fun fillLeftFace(
        out: IntArray, outW: Int, outH: Int,
        cx: Int, cy: Int, tile: Int, halfTile: Int, color: Int,
    ) {
        val xMax = cx
        for (dy in 0..tile + halfTile) {
            val row = cy + dy
            if (row < 0 || row >= outH) continue
            // Inset from the left edge: grows over the top slant (dy in
            // 0..halfTile), stays full over the body, grows again over the
            // bottom slant (dy in tile..tile+halfTile).
            val mTop = dy.coerceAtMost(halfTile)
            val mBottom = (dy - tile).coerceAtLeast(0).coerceAtMost(halfTile)
            val inset = tile * maxOf(mTop, mBottom) / halfTile
            val xMin = cx - tile + inset
            for (col in xMin..xMax) {
                if (col in 0 until outW) out[row * outW + col] = color
            }
        }
    }

    /** Screen-right face — mirror of [fillLeftFace]. */
    private fun fillRightFace(
        out: IntArray, outW: Int, outH: Int,
        cx: Int, cy: Int, tile: Int, halfTile: Int, color: Int,
    ) {
        val xMin = cx
        for (dy in 0..tile + halfTile) {
            val row = cy + dy
            if (row < 0 || row >= outH) continue
            val mTop = dy.coerceAtMost(halfTile)
            val mBottom = (dy - tile).coerceAtLeast(0).coerceAtMost(halfTile)
            val inset = tile * maxOf(mTop, mBottom) / halfTile
            val xMax = cx + tile - inset
            for (col in xMin..xMax) {
                if (col in 0 until outW) out[row * outW + col] = color
            }
        }
    }

    /** Proportional RGB shading by [factor] with the original alpha. */
    private fun shade(color: Int, factor: Double, alpha: Int): Int {
        if (factor >= 1.0) return color
        fun ch(shift: Int): Int {
            val v = ((color ushr shift) and 0xFF) * factor
            return v.toInt().coerceIn(0, 255)
        }
        return (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
