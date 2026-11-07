package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame

/**
 * The "de-AI" pass: turns procedurally clean output into something that
 * reads as hand-made.
 *
 * Generative pixel art has a signature look — perfect flat fills, uniform
 * clusters, ruler-straight outlines — that instantly reads as
 * machine-made. Human artists leave traces: paint clusters break large
 * fills into slightly uneven patches, brush pressure varies across a
 * stroke, and outlines wander a pixel wide. This pass layers exactly
 * those traces, deterministically:
 *
 *  1. **Cluster tinting** — the canvas splits into organic Voronoi cells
 *     (jittered-lattice Worley, mean diameter [clusterScale] pixels);
 *     each cell's opaque pixels shift luminance by a per-cell factor in
 *     `±clusterTint`. Large flat fills read as dabbed paint patches.
 *  2. **Brush texture** — two-octave value noise modulates luminance by
 *     `±brushStrength` at ~2.5px granularity: the dry-brush mottling real
 *     paint shows under magnification.
 *  3. **Stroke wobble** — outline pixels (opaque pixels with a
 *     transparent 4-neighbor) thicken into one adjacent transparent cell
 *     with probability [strokeWobble]: hand-drawn lines are never
 *     uniformly one pixel wide.
 *
 * All three effects scale an RGB-proportional (hue-preserving)
 * luminance factor; the alpha channel is untouched by tinting and
 * texture, so silhouettes stay crisp. Every parameter has a validated
 * range and the whole pass is a pure function of `(frame, seed,
 * parameters)` — identical inputs produce identical bytes.
 */
object HandDrawnPass {

    /** Lower/upper bounds for [apply] parameters, enforced fail-fast. */
    private const val CLUSTER_SCALE_MIN = 4.0
    private const val CLUSTER_SCALE_MAX = 64.0
    private const val CLUSTER_TINT_MAX = 0.25
    private const val BRUSH_STRENGTH_MAX = 0.25
    private const val STROKE_WOBBLE_MAX = 0.5

    /**
     * Applies the hand-crafted look to [frame].
     *
     * @param seed deterministic RNG seed; different seeds give different
     * cluster tilts, brush mottling and wobble spots.
     * @param clusterScale mean Voronoi cell diameter in pixels, `4..64`.
     * @param clusterTint per-cell luminance tilt amplitude, `0..0.25`
     * (0.10 ≈ one 8-bit step on mid-tones — visible but subtle).
     * @param brushStrength brush mottling amplitude, `0..0.25`.
     * @param strokeWobble probability an outline pixel thickens into a
     * neighboring transparent cell, `0..0.5`.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun apply(
        frame: PixelFrame,
        seed: Long = 0L,
        clusterScale: Double = 8.0,
        clusterTint: Double = 0.10,
        brushStrength: Double = 0.06,
        strokeWobble: Double = 0.0,
    ): PixelFrame {
        require(clusterScale in CLUSTER_SCALE_MIN..CLUSTER_SCALE_MAX) {
            "clusterScale must be in [4, 64] (was $clusterScale)"
        }
        require(clusterTint in 0.0..CLUSTER_TINT_MAX) {
            "clusterTint must be in [0, 0.25] (was $clusterTint)"
        }
        require(brushStrength in 0.0..BRUSH_STRENGTH_MAX) {
            "brushStrength must be in [0, 0.25] (was $brushStrength)"
        }
        require(strokeWobble in 0.0..STROKE_WOBBLE_MAX) {
            "strokeWobble must be in [0, 0.5] (was $strokeWobble)"
        }
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = src.copyOf()

        val rng = SeededRng(seed)
        val clusterNoise = WorleyNoise(seed xor 0x5DEECE66DL)
        val brushNoise = ValueNoise(seed xor 0x1F123BB5L)
        // Independent sub-stream per effect: reordering the effect calls
        // never changes the others' randomness.
        val wobbleRng = rng.split()
        val clusterRng = rng.split()
        val tintCache = HashMap<Long, Double>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = src[i]
                if (c == 0) continue // transparency is sacred
                val a = c ushr 24

                // 1. Cluster tilt: Worley CELL feature quantizes space into
                // organic patches; each patch gets one deterministic tilt.
                var factor = 1.0
                if (clusterTint > 0.0) {
                    val cell = cellKey(clusterNoise, x.toDouble(), y.toDouble(), clusterScale)
                    val tilt = tintCache.getOrPut(cell) {
                        (clusterRng.nextDouble() * 2.0 - 1.0) * clusterTint
                    }
                    factor += tilt
                }
                // 2. Brush mottling: high-frequency value noise.
                if (brushStrength > 0.0) {
                    val n = brushNoise.fbm(x / 2.5f, y / 2.5f, 2, 0.5f, 2.0f).toDouble()
                    factor += (n - 0.5) * 2.0 * brushStrength
                }
                if (factor != 1.0) {
                    out[i] = (a shl 24) or scaleRgb(c, factor)
                }
            }
        }

        // 3. Stroke wobble: thicken outlines into one adjacent transparent
        // cell with probability strokeWobble (never thins — connectivity
        // of strokes must survive).
        if (strokeWobble > 0.0) {
            val outline = ArrayList<Int>(w * 2 + h * 2)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = out[y * w + x]
                    if (c == 0) continue
                    if (hasTransparentNeighbor(src, w, h, x, y)) outline.add(y * w + x)
                }
            }
            for (i in outline) {
                if (!wobbleRng.chance(strokeWobble.toFloat())) continue
                val x = i % w
                val y = i / w
                val color = out[i]
                thickenInto(out, w, h, x, y, color, wobbleRng)
            }
        }
        return PixelFrame.adopt(w, h, out)
    }

    /**
     * Worley cell key for the cluster grid: the quantized
     * jittered-feature-point coordinates, stable for every pixel of one
     * cell.
     */
    private fun cellKey(noise: WorleyNoise, x: Double, y: Double, scale: Double): Long {
        val gx = (x / scale)
        val gy = (y / scale)
        val ix = kotlin.math.floor(gx)
        val iy = kotlin.math.floor(gy)
        // Nearest jittered feature point among the 3x3 surrounding cells —
        // the classic Voronoi assignment.
        var bestD = Double.MAX_VALUE
        var bestX = ix
        var bestY = iy
        for (dy in -1..1) {
            for (dx in -1..1) {
                val cx = ix + dx
                val cy = iy + dy
                val fx = cx + noise.jitterX(cx, cy)
                val fy = cy + noise.jitterY(cx, cy)
                val d = (fx - gx) * (fx - gx) + (fy - gy) * (fy - gy)
                if (d < bestD) {
                    bestD = d
                    bestX = cx
                    bestY = cy
                }
            }
        }
        return (bestX.toLong() shl 32) or (bestY.toLong() and 0xFFFFFFFFL)
    }

    /** True when any 4-neighbor of (x, y) is transparent or off-raster. */
    private fun hasTransparentNeighbor(src: IntArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        return (x == 0 || src[y * w + x - 1] == 0) ||
            (x == w - 1 || src[y * w + x + 1] == 0) ||
            (y == 0 || src[(y - 1) * w + x] == 0) ||
            (y == h - 1 || src[(y + 1) * w + x] == 0)
    }

    /**
     * Paints [color] into one transparent 4-neighbor of (x, y), chosen
     * deterministically by [rng]; does nothing when no transparent
     * neighbor exists (interior pixel).
     */
    private fun thickenInto(out: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int, rng: SeededRng) {
        val candidates = IntArray(4)
        var count = 0
        if (x > 0 && out[y * w + x - 1] == 0) candidates[count++] = y * w + x - 1
        if (x < w - 1 && out[y * w + x + 1] == 0) candidates[count++] = y * w + x + 1
        if (y > 0 && out[(y - 1) * w + x] == 0) candidates[count++] = (y - 1) * w + x
        if (y < h - 1 && out[(y + 1) * w + x] == 0) candidates[count++] = (y + 1) * w + x
        if (count == 0) return
        out[candidates[rng.nextInt(count)]] = color
    }

    /**
     * Scales the RGB channels of packed [c] by [factor] (round-half-up,
     * clamped); the alpha byte is left in place by the caller.
     * Proportional scaling preserves hue — the paint "thinner/lighter"
     * look — while shifting perceived luminance.
     */
    private fun scaleRgb(c: Int, factor: Double): Int {
        fun ch(shift: Int): Int {
            val v = ((c ushr shift) and 0xFF) * factor
            return v.toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
