package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame

/**
 * xBRZ-style edge-aware interpolation scaler (4x and 6x).
 *
 * xBR (Hyllian) and xBRZ (Zenju) form the state of the art in
 * "pixel-art aware" upscaling: instead of blurring the whole image like a
 * bilinear/lanczos filter, they analyze each pixel's 3x3 neighborhood,
 * find where the *edges* run, and only smooth along those edges — flat
 * interiors stay bit-exact, straight lines stay perfectly straight, and
 * staircase diagonals get a one-pixel anti-aliased bevel. Unlike the
 * never-blend [XbrScale] level-1 port, this implementation follows the
 * xBRZ school and **mixes the two colors meeting at an edge**, producing
 * intermediate shades strictly between the two input colors (never a
 * third color) — the same discipline a human pixel artist applies when
 * hand-beveling edges.
 *
 * Rule set per output quadrant (shown for the top-left quadrant,
 * neighborhood `A B C / D E F / G H I` around center E):
 *
 *  * `B == E || D == E` → flat / straight edge: the quadrant copies E.
 *    Straight lines survive bit-exact because a straight edge always has
 *    the inline neighbor equal to E on one side of the corner test.
 *  * `B == D (≠ E)` → a diagonal edge crosses the quadrant corner: the
 *    sub-pixels fade from `mix(E, B, 75%)` at the very corner to pure E
 *    toward the quadrant center (the classic xBRZ bevel).
 *  * `A == B` → the edge arrives vertically: blend the row adjacent to
 *    the vertical neighbor (50%, one extra 15% echo row at 6x).
 *  * `A == D` → the edge arrives horizontally: same, mirrored onto the
 *    column adjacent to the horizontal neighbor.
 *  * otherwise → isolated corner clash: only the single corner sub-pixel
 *    blends toward the luma-closer neighbor at 25%.
 *
 * Alpha follows the same linear blend, so opaque-to-transparent edges
 * gain a proper alpha falloff (the standard treatment for sprite
 * silhouettes) instead of hard jaggies.
 *
 * Deterministic: pure function of the input raster, integer-only
 * arithmetic (per-mille weights), no allocation beyond the output raster.
 */
object XbrzScale {

    /** Per-mille blend weight applied at the exact corner sub-pixel. */
    private const val CORNER_WEIGHT = 750

    /** Per-mille blend weight for sub-pixels one step from the corner. */
    private const val NEAR_WEIGHT = 400

    /** Per-mille blend weight for sub-pixels two steps from the corner. */
    private const val FAR_WEIGHT = 100

    /** Per-mille weight on the row/column adjacent to a straight-ish edge. */
    private const val EDGE_ROW_WEIGHT = 500

    /** Secondary echo weight at 6x (row/column next to the adjacent one). */
    private const val EDGE_ECHO_WEIGHT = 150

    /** Per-mille weight of the isolated-corner clash fallback. */
    private const val CLASH_WEIGHT = 250

    /** Hard cap on output pixels, mirroring [Resample]'s budget. */
    private const val PIXEL_BUDGET = 268_435_456L

    /**
     * Upscales [frame] by 4 with edge-aware interpolation.
     *
     * @throws IllegalArgumentException if the output raster would exceed the
     * 268M pixel budget.
     */
    fun xbrz4x(frame: PixelFrame): PixelFrame = upscale(frame, 4)

    /**
     * Upscales [frame] by 6 with edge-aware interpolation. The 6x variant
     * renders the bevel with three weight steps instead of two, giving
     * visibly rounder corners on large-format exports.
     *
     * @throws IllegalArgumentException if the output raster would exceed the
     * 268M pixel budget.
     */
    fun xbrz6x(frame: PixelFrame): PixelFrame = upscale(frame, 6)

    /** Shared driver for both factors. */
    private fun upscale(frame: PixelFrame, factor: Int): PixelFrame {
        val w = frame.width
        val h = frame.height
        val outPixels = w.toLong() * factor * (h.toLong() * factor)
        require(outPixels <= PIXEL_BUDGET) {
            "xBRZ ${factor}x output ${w * factor}x${h * factor} exceeds the 268M pixel budget"
        }
        val src = frame.pixels
        val outW = w * factor
        val out = IntArray(outPixels.toInt())
        for (y in 0 until h) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val left = if (x > 0) x - 1 else x
                val right = if (x < w - 1) x + 1 else x
                val a = src[up * w + left]
                val b = src[up * w + x]
                val c = src[up * w + right]
                val d = src[y * w + left]
                val e = src[y * w + x]
                val f = src[y * w + right]
                val g = src[down * w + left]
                val hh = src[down * w + x]
                val ii = src[down * w + right]
                blendQuadrant(out, outW, x, y, factor, quadrant = 0, e, b, d, a)
                blendQuadrant(out, outW, x, y, factor, quadrant = 1, e, b, f, c)
                blendQuadrant(out, outW, x, y, factor, quadrant = 2, e, hh, d, g)
                blendQuadrant(out, outW, x, y, factor, quadrant = 3, e, hh, f, ii)
            }
        }
        return PixelFrame.of(outW, h * factor, out)
    }

    /**
     * Blends one quadrant of the output block.
     *
     * @param quadrant 0 = top-left, 1 = top-right, 2 = bottom-left,
     * 3 = bottom-right.
     * @param e center color.
     * @param vert vertical neighbor color for this quadrant (B for the top
     * quadrants, H for the bottom ones).
     * @param horiz horizontal neighbor color (D for the left quadrants,
     * F for the right ones).
     * @param diag diagonal neighbor at the quadrant corner (A / C / G / I).
     */
    private fun blendQuadrant(
        out: IntArray,
        outW: Int,
        x: Int,
        y: Int,
        factor: Int,
        quadrant: Int,
        e: Int,
        vert: Int,
        horiz: Int,
        diag: Int,
    ) {
        val n = if (factor == 4) 2 else 3
        // Sub-block origin inside the factor×factor output block, and the
        // corner of the block this quadrant hugs.
        val blockX = x * factor
        val blockY = y * factor
        val qx = if (quadrant == 0 || quadrant == 2) blockX else blockX + n
        val qy = if (quadrant == 0 || quadrant == 1) blockY else blockY + n
        val cornerCol = if (quadrant == 0 || quadrant == 2) 0 else n - 1
        val cornerRow = if (quadrant == 0 || quadrant == 1) 0 else n - 1
        // The side of the sub-block adjacent to the vertical/horizontal
        // neighbor: top quadrants hug vert from row 0, bottom ones from the
        // last row; left quadrants hug horiz on column 0, right ones on the
        // last column.
        val vertRow = if (quadrant == 0 || quadrant == 1) 0 else n - 1
        val horizCol = if (quadrant == 0 || quadrant == 2) 0 else n - 1

        if (vert == e || horiz == e) {
            // Flat or straight edge: the whole quadrant copies E. (The
            // output raster starts zeroed, so every quadrant MUST write —
            // an early return would leave transparent holes.)
            for (j in 0 until n) {
                val row = (qy + j) * outW + qx
                for (i in 0 until n) out[row + i] = e
            }
            return
        }

        val blendColor: Int
        val weights = IntArray(n * n)
        when {
            vert == horiz -> { // diagonal edge crosses the corner
                blendColor = vert
                for (j in 0 until n) {
                    for (i in 0 until n) {
                        val dist = kotlin.math.abs(i - cornerCol) + kotlin.math.abs(j - cornerRow)
                        weights[j * n + i] = when (dist) {
                            0 -> CORNER_WEIGHT
                            1 -> if (n == 2) NEAR_WEIGHT - 150 else NEAR_WEIGHT
                            2 -> if (n == 2) 0 else FAR_WEIGHT
                            else -> 0
                        }
                    }
                }
            }
            diag == vert -> { // edge arrives vertically: bevel the vert row
                blendColor = vert
                for (i in 0 until n) weights[vertRow * n + i] = EDGE_ROW_WEIGHT
                if (n == 3) {
                    // The echo row is the middle row (adjacent to either a
                    // top-hugging or bottom-hugging bevel row).
                    for (i in 0 until n) weights[n + i] = EDGE_ECHO_WEIGHT
                }
            }
            diag == horiz -> { // edge arrives horizontally: bevel the horiz column
                blendColor = horiz
                for (j in 0 until n) weights[j * n + horizCol] = EDGE_ROW_WEIGHT
                if (n == 3) {
                    // The echo column is the middle column.
                    for (j in 0 until n) weights[j * n + 1] = EDGE_ECHO_WEIGHT
                }
            }
            else -> { // isolated corner clash: single soft corner
                blendColor = if (yuvDistance(e, vert) <= yuvDistance(e, horiz)) vert else horiz
                weights[cornerRow * n + cornerCol] = CLASH_WEIGHT
            }
        }

        for (j in 0 until n) {
            val row = (qy + j) * outW + qx
            for (i in 0 until n) {
                val t = weights[j * n + i]
                out[row + i] = if (t == 0) e else mix(e, blendColor, t)
            }
        }
    }

    /**
     * Linear per-mille blend of two packed pixels (straight alpha, all four
     * channels, round-half-up). A weight of 0 returns [c1] bit-exact; a
     * blend of a color with itself is that color, so equal-color edges never
     * drift.
     */
    fun mix(c1: Int, c2: Int, perMille: Int): Int {
        if (perMille <= 0) return c1
        if (perMille >= 1000) return c2
        val inv = 1000 - perMille
        fun ch(shift: Int): Int {
            val a = (c1 ushr shift) and 0xFF
            val b = (c2 ushr shift) and 0xFF
            return (a * inv + b * perMille + 500) / 1000
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * YUV-weighted color distance (luma dominates, chroma breaks ties) —
     * the xBRZ-family perceptual metric in integer form.
     */
    fun yuvDistance(c1: Int, c2: Int): Int {
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 ushr 16) and 0xFF
        val g2 = (c2 ushr 8) and 0xFF
        val b2 = c2 and 0xFF
        val y1 = 299 * r1 + 587 * g1 + 114 * b1
        val y2 = 299 * r2 + 587 * g2 + 114 * b2
        val u1 = (b1 shl 1) - (r1 + g1)
        val u2 = (b2 shl 1) - (r2 + g2)
        val v1 = (r1 shl 1) - (g1 + b1)
        val v2 = (r2 shl 1) - (g2 + b2)
        val dy = kotlin.math.abs(y1 - y2)
        val du = kotlin.math.abs(u1 - u2)
        val dv = kotlin.math.abs(v1 - v2)
        return 48 * dy + 7 * du + 6 * dv
    }
}
