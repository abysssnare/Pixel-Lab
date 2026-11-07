package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame

/**
 * The upscale filter catalogue for exports, transforms and pipelines.
 *
 * Historically the transform algorithms ([Scale2x], [EpxScale], [XbrScale],
 * [XbrzScale]) lived behind individual MCP tools while every export path
 * hard-coded plain nearest-neighbor duplication. This enum is the single
 * routing point that connects the whole catalogue to any integer target
 * scale `1..16`:
 *
 *  * **NEAREST** — bit-exact duplication (the classic crisp look; and the
 *    only filter that never invents a color).
 *  * **SCALE2X / SCALE3X** — AdvMAME-style edge-interpolation, exact at
 *    their nominal factor and iterated for multiples of it.
 *  * **EPX2X / EPX3X** — the EPX corner-detection family.
 *  * **XBR2X** — Hyllian's level-1 rules, never-blend.
 *  * **XBRZ4X / XBRZ6X** — edge-aware interpolation that mixes the two
 *    colors meeting at a corner (see [XbrzScale]): the "beveled" look of
 *    premium hand-touched pixel art.
 *  * **HYBRID_AA** — the premium composition for final exports: upscale
 *    with [XbrzScale] (iterated to ≥ 4x), then area-average back down to
 *    the exact target size with [Resample.boxResample]. The result keeps
 *    xBRZ's directional edge detection but gains true sub-pixel coverage
 *    on the final ratio — the closest a deterministic integer pipeline
 *    gets to an artist-retouched master.
 *
 * Non-matching target scales are handled by the cascade rule:
 * iterate the nominal pass while the result is smaller than the target,
 * then area-average down to the exact factor (never upsample crudely).
 * Intermediate rasters obey the same 268M-pixel budget as [Resample].
 */
enum class UpscaleFilter {

    /** Bit-exact nearest-neighbor duplication. */
    NEAREST,

    /** AdvMAME Scale2x (exact at 2x, iterated for 4x/8x/16x). */
    SCALE2X,

    /** AdvMAME Scale3x (exact at 3x, iterated for 6x/12x). */
    SCALE3X,

    /** EPX 2x corner-detection scaler. */
    EPX2X,

    /** EPX 3x corner-detection scaler. */
    EPX3X,

    /** xBR level-1, never-blend variant. */
    XBR2X,

    /** xBRZ-style edge-aware 4x with corner bevels. */
    XBRZ4X,

    /** xBRZ-style edge-aware 6x with wider bevel falloff. */
    XBRZ6X,

    /** xBRZ supersampling + box-filter reduction to the exact target. */
    HYBRID_AA;

    /**
     * Upscales [frame] to exactly `frame.width * [scale]` x
     * `frame.height * scale`.
     *
     * @param scale integer factor `1..16`. A scale of 1 returns [frame]
     * itself for every filter.
     * @throws IllegalArgumentException if [scale] is outside `1..16` or any
     * intermediate/output raster would exceed the 268M pixel budget.
     */
    fun upscale(frame: PixelFrame, scale: Int): PixelFrame {
        require(scale in 1..16) { "scale must be in 1..16 (was $scale)" }
        if (scale == 1) return frame
        when (this) {
            NEAREST -> return Resample.nearest(frame, frame.width * scale, frame.height * scale)
            else -> {}
        }
        val base = nominalPass(frame)
        val targetW = frame.width.toLong() * scale
        val targetH = frame.height.toLong() * scale
        budgetGuard(base.width, base.height, "intermediate")
        var current = base
        while (current.width.toLong() < targetW) {
            // The next nominal pass multiplies both axes by the nominal
            // factor; check its output budget before running it.
            val nextW = current.width.toLong() * nominalFactor
            val nextH = current.height.toLong() * nominalFactor
            require(nextW * nextH <= 268_435_456L) {
                "xBRZ cascade intermediate raster ${nextW}x${nextH} exceeds the 268M pixel " +
                    "budget; use UpscaleFilter.NEAREST for large frames"
            }
            current = nominalPass(current)
        }
        if (current.width.toLong() == targetW && current.height.toLong() == targetH) {
            return current
        }
        budgetGuard(targetW.toInt(), targetH.toInt(), "output")
        return Resample.boxResample(current, targetW.toInt(), targetH.toInt())
    }

    /** Nominal integer factor of one [nominalPass] (≥ 2). */
    private val nominalFactor: Int
        get() = when (this) {
            NEAREST, SCALE2X, EPX2X, XBR2X -> 2 // NEAREST unreachable here
            SCALE3X, EPX3X -> 3
            XBRZ4X, HYBRID_AA -> 4
            XBRZ6X -> 6
        }

    /** One nominal-factor pass of this filter's base algorithm. */
    private fun nominalPass(frame: PixelFrame): PixelFrame = when (this) {
        NEAREST -> Resample.nearest(frame, frame.width * 2, frame.height * 2) // unreachable
        SCALE2X -> Scale2x.scale2x(frame)
        SCALE3X -> Scale2x.scale3x(frame)
        EPX2X -> EpxScale.epx2x(frame)
        EPX3X -> EpxScale.epx3x(frame)
        XBR2X -> XbrScale.xbr2x(frame)
        XBRZ4X -> XbrzScale.xbrz4x(frame)
        XBRZ6X -> XbrzScale.xbrz6x(frame)
        HYBRID_AA -> XbrzScale.xbrz4x(frame)
    }

    /** Rejects rasters whose area would blow the shared pixel budget. */
    private fun budgetGuard(width: Int, height: Int, stage: String) {
        require(width.toLong() * height.toLong() <= 268_435_456L) {
            "xBRZ cascade $stage raster ${width}x${height} exceeds the 268M pixel budget; " +
                "use UpscaleFilter.NEAREST for large frames"
        }
    }
}
