package com.pixellab.core.export

import com.pixellab.core.io.PngDecoder
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.pipeline.RecipeParser
import com.pixellab.core.pipeline.RecipeStep
import com.pixellab.core.pipeline.PipelineRunner
import com.pixellab.core.PixelResult
import com.pixellab.core.transform.UpscaleFilter
import com.pixellab.core.transform.XbrzScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleExportWiringTest {

    /** 4x4 frame with a diagonal k/w staircase — the bevel showcase. */
    private fun staircase(): PixelFrame {
        val px = IntArray(16)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                px[y * 4 + x] = if (x + y < 4) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return PixelFrame.of(4, 4, px)
    }

    /** A one-frame project around [frame] for the Exporter facade tests. */
    private fun projectOf(frame: PixelFrame): com.pixellab.core.model.SpriteProject =
        com.pixellab.core.model.SpriteFactory.create(
            "wiring", frame.width, frame.height,
            com.pixellab.core.palette.BuiltInPalettes.PICO8,
        ).withActiveCel(frame)

    @Test
    fun `adaptive-row-filter PNG decodes back to the exact raster`() {
        val upscaled = XbrzScale.xbrz4x(staircase())
        val png = PngCodec.encodeUpscaled(upscaled)
        val decoded = PngDecoder.decode(png).frames.single().frame
        assertEquals(upscaled.width, decoded.width)
        assertEquals(upscaled.height, decoded.height)
        assertTrue(
            "decode(encodeUpscaled(frame)) must be pixel-identical",
            decoded.pixels.contentEquals(upscaled.pixels),
        )
    }

    @Test
    fun `adaptive row filters beat the None-only encode on beveled rasters`() {
        // The whole reason encodeUpscaled exists. A beveled smooth
        // gradient has strong row-to-row structure: Sub/Up/Paeth
        // predictors encode each row as a small delta of the previous
        // one, while the all-None layout forces deflate to rediscover
        // the pattern through LZ matches. Measured on this fixture the
        // adaptive path wins ~4.5x. (Pure flat-color staircases can tie
        // — LZ already handles exact repetition — so the fixture uses a
        // gradient, the structure the heuristic targets.)
        val w = 64
        val px = IntArray(w * w)
        for (y in 0 until w) {
            for (x in 0 until w) {
                val t = (x + y) * 255 / (2 * w)
                px[y * w + x] = (0xFF shl 24) or (t shl 16) or (t shl 8) or t
            }
        }
        val upscaled = XbrzScale.xbrz4x(PixelFrame.of(w, w, px))
        val plain = PngCodec.encode(upscaled, 1)
        val adaptive = PngCodec.encodeUpscaled(upscaled)
        assertTrue(
            "adaptive (${adaptive.size} B) should clearly beat plain (${plain.size} B)",
            adaptive.size * 2 < plain.size,
        )
    }

    @Test
    fun `filter-routed exports decode at the exact target size`() {
        val frame = staircase()
        for (filter in listOf(UpscaleFilter.XBRZ4X, UpscaleFilter.XBRZ6X, UpscaleFilter.HYBRID_AA)) {
            for (scale in intArrayOf(2, 5, 8)) {
                val upscaled = filter.upscale(frame, scale)
                val png = PngCodec.encodeUpscaled(upscaled)
                val decoded = PngDecoder.decode(png).frames.single().frame
                assertEquals("$filter/$scale width", frame.width * scale, decoded.width)
                assertEquals("$filter/$scale height", frame.height * scale, decoded.height)
            }
        }
    }

    @Test
    fun `nearest export keeps the historical byte layout`() {
        // NEAREST at scale 1 must keep using the plain path — byte-stable
        // for every existing consumer.
        val frame = staircase()
        val historical = PngCodec.encode(frame, 1)
        val routed = UpscaleFilter.NEAREST.upscale(frame, 1)
        assertTrue(routed === frame)
        assertTrue(PngCodec.encode(frame, 1).contentEquals(historical))
    }

    @Test
    fun `exporter spritesheet routes frames through the requested filter`() {
        val exporter = Exporter(com.pixellab.core.PixelLabConfig.default())
        val project = projectOf(staircase())
        val nearestPng = runBlocking {
            exporter.exportSpritesheet(project, scale = 2, columns = 1, filter = UpscaleFilter.NEAREST)
        }
        val beveledPng = runBlocking {
            exporter.exportSpritesheet(project, scale = 2, columns = 1, filter = UpscaleFilter.XBRZ4X)
        }
        assertTrue(nearestPng is PixelResult.Ok)
        assertTrue(beveledPng is PixelResult.Ok)
        val nearest = PngDecoder.decode((nearestPng as PixelResult.Ok).value).frames.single().frame
        val beveled = PngDecoder.decode((beveledPng as PixelResult.Ok).value).frames.single().frame
        assertEquals(nearest.width, beveled.width)
        assertEquals(nearest.height, beveled.height)
        // The beveled sheet must contain shades beyond the two input
        // colors (proof the filter actually ran); the nearest sheet must
        // not.
        val pureColors = setOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0)
        assertTrue(
            "beveled sheet must contain mix shades",
            beveled.pixels.any { it !in pureColors },
        )
        assertTrue(
            "nearest sheet must stay pure",
            nearest.pixels.all { it in pureColors },
        )
    }

    @Test
    fun `recipe scale step parses the filter name`() {
        val recipe = com.pixellab.core.pipeline.RecipeParser.parse(
            """
            {"steps":[{"type":"scale","factor":3,"filter":"hybrid"}]}
            """.trimIndent(),
        )
        val step = recipe.steps.first() as com.pixellab.core.pipeline.RecipeStep.ScaleStep
        assertEquals(3, step.factor)
        assertEquals(UpscaleFilter.HYBRID_AA, step.filter)
    }

    @Test
    fun `recipe scale step defaults to nearest without a filter`() {
        val recipe = com.pixellab.core.pipeline.RecipeParser.parse(
            """
            {"steps":[{"type":"scale","factor":2}]}
            """.trimIndent(),
        )
        val step = recipe.steps.first() as com.pixellab.core.pipeline.RecipeStep.ScaleStep
        assertEquals(UpscaleFilter.NEAREST, step.filter)
    }

    @Test
    fun `recipe rejects unknown filter names`() {
        try {
            com.pixellab.core.pipeline.RecipeParser.parse(
                """
                {"steps":[{"type":"scale","factor":2,"filter":"bogus"}]}
                """.trimIndent(),
            )
            error("expected IAE for unknown filter")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `pipeline runner executes the hybrid scale step`() {
        val recipe = com.pixellab.core.pipeline.RecipeParser.parse(
            """
            {"steps":[{"type":"scale","factor":2,"filter":"hybrid"}]}
            """.trimIndent(),
        )
        val result = PipelineRunner.run(recipe, listOf(staircase()))
        assertTrue(result is PixelResult.Ok<*>)
        val frames = (result as PixelResult.Ok<List<PixelFrame>>).value
        assertEquals(8, frames[0].width)
        assertEquals(8, frames[0].height)
    }
}
