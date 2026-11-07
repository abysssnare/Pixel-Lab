package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame

/**
 * Hand-crafted anti-aliasing pass for pixel art at native resolution.
 *
 * Digital art rendered with hard pixel edges shows "staircase" jaggies on
 * any non-orthogonal silhouette. Human pixel artists fix this by hand:
 * they find the corners where two colors meet at a diagonal step and lay
 * a single intermediate shade between them — an art discipline usually
 * called *manual AA* (see the classic "so you want to be a pixel artist"
 * tutorials). This pass automates exactly that discipline and nothing
 * else:
 *
 *  * **Convex corners** — a transparent pixel sitting on the diagonal
 *    outside of an opaque region's 45° step (its diagonal neighbor is
 *    opaque, its two adjacent orthogonal neighbors are transparent)
 *    receives a semi-transparent copy of that region's color. Sprites on
 *    transparent backgrounds gain a proper silhouette falloff instead of
 *    hard jaggies, and nothing is added anywhere else.
 *  * **Concave corners** — an opaque pixel whose two orthogonal
 *    neighbors *and* diagonal neighbor all share one different opaque
 *    color (the region wraps around the corner) blends 25% toward that
 *    surrounding color, softening inner notches the way an artist would
 *    pick a mid-tone.
 *
 * Straight edges, flat interiors and isolated pixels are never touched:
 * the pass only reacts to genuine diagonal corner patterns, so it cannot
 * blur a correctly drawn image the way a naive convolution would.
 *
 * Blend fractions derive from [XbrzScale.mix] (per-mille linear blend of
 * the two involved colors only) — intermediate shades stay strictly
 * between the two meeting colors, preserving palette discipline.
 * Deterministic pure function; the output alpha of injected silhouette
 * pixels scales linearly with [strength].
 */
object EdgeSmoothing {

    /** Concave blend fraction: quarter-tone, the conservative artist pick. */
    private const val CONCAVE_PER_MILLE = 250

    /**
     * Applies manual-AA to [frame].
     *
     * @param strength blend strength `0.0..1.0`; 0 returns the frame
     * unchanged, 1 is the maximal bevel. Default 0.5 matches the mix a
     * pixel artist most often picks (an even split between the two
     * meeting colors).
     * @throws IllegalArgumentException if [strength] is outside `0..1`.
     */
    fun smooth(frame: PixelFrame, strength: Double = 0.5): PixelFrame {
        require(strength in 0.0..1.0) { "strength must be in 0.0..1.0 (was $strength)" }
        if (strength <= 0.0) return frame
        val perMille = (strength * 1000).toInt().coerceIn(1, 1000)
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = src.copyOf()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val e = src[y * w + x]
                if (e == 0) {
                    smoothConvexCorner(out, w, h, x, y, src, perMille)
                } else {
                    smoothConcaveCorner(out, w, h, x, y, src, perMille)
                }
            }
        }
        return PixelFrame.adopt(w, h, out)
    }

    /**
     * Transparent-pixel corner pass. A transparent pixel participates when
     * one of its four quadrant patterns holds:
     *
     *  * **outer convex corner** — the diagonal neighbor is opaque while
     *    both orthogonal neighbors are transparent: the pixel sits on the
     *    diagonal outside of the region's 45° step.
     *  * **inner notch** — both orthogonal neighbors of the quadrant share
     *    one opaque color while the diagonal is transparent: the pixel is
     *    the gap between two diagonal steps of the same region.
     *
     * Both receive a semi-transparent copy of the region color, producing
     * the alpha falloff a hand-beveled silhouette shows.
     */
    private fun smoothConvexCorner(
        out: IntArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        src: IntArray,
        perMille: Int,
    ) {
        for (quadrant in 0 until 4) {
            val dx = if (quadrant == 0 || quadrant == 3) -1 else 1 // diagonal step x
            val dy = if (quadrant == 0 || quadrant == 1) -1 else 1 // diagonal step y
            val diag = neighborOrNull(src, w, h, x + dx, y + dy) ?: continue
            val ortho1 = neighborOrNull(src, w, h, x + dx, y) ?: continue
            val ortho2 = neighborOrNull(src, w, h, x, y + dy) ?: continue
            val injected = when {
                diag != 0 && ortho1 == 0 && ortho2 == 0 -> diag // outer convex corner
                diag == 0 && ortho1 != 0 && ortho1 == ortho2 -> ortho1 // inner notch
                else -> 0
            }
            if (injected != 0) {
                // Silhouette falloff caps at half opacity: an AA step should
                // read as a lighter edge, not as a new solid pixel.
                out[y * w + x] = mixTransparentToward(injected, (perMille + 1) / 2)
            }
        }
    }

    /**
     * Concave corner: [x, y] is opaque, and around one of its corners the
     * two orthogonal neighbors plus the diagonal neighbor all share one
     * different opaque color → blend the pixel 25% toward that color.
     */
    private fun smoothConcaveCorner(
        out: IntArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        src: IntArray,
        perMille: Int,
    ) {
        val e = src[y * w + x]
        for (quadrant in 0 until 4) {
            val dx = if (quadrant == 0 || quadrant == 3) -1 else 1
            val dy = if (quadrant == 0 || quadrant == 1) -1 else 1
            val vNeighbor = neighborOrNull(src, w, h, x, y + dy) ?: continue
            val hNeighbor = neighborOrNull(src, w, h, x + dx, y) ?: continue
            val diag = neighborOrNull(src, w, h, x + dx, y + dy) ?: continue
            // Region wraps around the corner: both orthogonal neighbors and
            // the diagonal share one color distinct from E.
            if (vNeighbor == 0 || hNeighbor == 0 || diag == 0) continue
            if (vNeighbor != hNeighbor || vNeighbor != diag || vNeighbor == e) continue
            val blend = XbrzScale.mix(e, vNeighbor, CONCAVE_PER_MILLE * perMille / 1000)
            out[y * w + x] = blend
            return // one corner per pixel is enough — keep it subtle
        }
    }

    /**
     * Blends a fully transparent pixel toward opaque [color] at [perMille]:
     * the result carries `color`'s channels with alpha scaled to the blend
     * fraction — the canonical sprite-silhouette falloff.
     */
    private fun mixTransparentToward(color: Int, perMille: Int): Int {
        val alpha = ((color ushr 24) * perMille + 500) / 1000
        return (alpha shl 24) or (color and 0x00_FF_FF_FF)
    }

    /** Reads a neighbor pixel, or null when the coordinate is off-raster. */
    private fun neighborOrNull(src: IntArray, w: Int, h: Int, x: Int, y: Int): Int? =
        if (x < 0 || y < 0 || x >= w || y >= h) null else src[y * w + x]
}
