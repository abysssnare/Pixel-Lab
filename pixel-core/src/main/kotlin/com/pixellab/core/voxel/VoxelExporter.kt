package com.pixellab.core.voxel

import com.pixellab.core.export.KotlinGifEncoder
import com.pixellab.core.model.PixelFrame

/**
 * 3D artifact exporters for voxelized sprites.
 *
 *  * **OBJ** — the voxel surface as watertight quad mesh: only faces
 *    between a solid cell and an empty neighbor are emitted, vertices are
 *    deduplicated through a corner cache, coordinates flip to the OBJ
 *    y-up right-handed convention (`objX = x, objY = z, objZ = −y`), and
 *    a per-voxel color comment block rides along for importers that read
 *    them (Blender's OBJ importer ignores them harmlessly).
 *  * **Rotation GIF** — the four 90° yaw views rendered through
 *    [VoxelRenderer] onto one shared canvas (centered per frame, so the
 *    sprite turns in place instead of jittering), encoded as a looping
 *    GIF89a through the existing [KotlinGifEncoder]. A turntable
 *    showcase of the 3D model that stays 100% pixel-art.
 */
object VoxelExporter {

    /**
     * Encodes the exposed surface of [model] as Wavefront OBJ text.
     *
     * Output structure: `#` header comments (including the voxel color
     * table), one `v` line per unique corner, empty-line groups per
     * voxel (its color in a comment), and one `f v1 v2 v3 v4` quad per
     * exposed face. Face winding is counter-clockwise viewed from the
     * outside (front faces under OpenGL default winding).
     *
     * @return the OBJ document as UTF-8 bytes.
     */
    fun exportObj(model: VoxelModel): ByteArray {
        val sb = StringBuilder(estimateObjSize(model))
        sb.append("# Pixel Lab voxel export\n")
        sb.append("# grid ${model.width}x${model.height}x${model.depth}, ${model.solidCount} solid voxels\n")
        sb.append("# axes: objX = gridX, objY = gridZ (up), objZ = -gridY\n")
        sb.append("o pixellab_voxel\n")

        // Corner cache: grid corner (x, y, z) with x in 0..w, y in 0..h,
        // z in 0..d → OBJ vertex index (1-based).
        val cornerIndex = HashMap<Long, Int>(model.solidCount * 4)
        val w = model.width
        val h = model.height
        val d = model.depth

        fun vertex(x: Int, y: Int, z: Int): Int {
            val key = (z.toLong() * (w + 1) + y) * (w + 1) + x
            return cornerIndex.getOrPut(key) {
                // Emit on first use (only surface corners ever appear).
                sb.append("v ").append(x).append(' ').append(z).append(' ').append(-y).append('\n')
                cornerIndex.size + 1
            }
        }

        var faces = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (z in 0 until d) {
                    val color = model[x, y, z]
                    if (color == 0) continue
                    sb.append("\n# voxel ").append(x).append(' ').append(y).append(' ').append(z)
                        .append(" color #").append(Integer.toHexString(color).uppercase())
                        .append('\n')
                    // Six faces; emit the ones exposed to empty space,
                    // wound CCW from outside.
                    if (model[x, y, z + 1] == 0) { // +z (OBJ +y) top
                        quad(sb, vertex(x, y, z + 1), vertex(x + 1, y, z + 1), vertex(x + 1, y + 1, z + 1), vertex(x, y + 1, z + 1))
                        faces++
                    }
                    if (model[x, y, z - 1] == 0) { // -z bottom
                        quad(sb, vertex(x, y, z), vertex(x, y + 1, z), vertex(x + 1, y + 1, z), vertex(x + 1, y, z))
                        faces++
                    }
                    if (model[x + 1, y, z] == 0) { // +x (OBJ +x)
                        quad(sb, vertex(x + 1, y, z), vertex(x + 1, y + 1, z), vertex(x + 1, y + 1, z + 1), vertex(x + 1, y, z + 1))
                        faces++
                    }
                    if (model[x - 1, y, z] == 0) { // -x
                        quad(sb, vertex(x, y, z), vertex(x, y, z + 1), vertex(x, y + 1, z + 1), vertex(x, y + 1, z))
                        faces++
                    }
                    if (model[x, y + 1, z] == 0) { // +y (OBJ -z) screen-left
                        quad(sb, vertex(x, y + 1, z), vertex(x, y + 1, z + 1), vertex(x + 1, y + 1, z + 1), vertex(x + 1, y + 1, z))
                        faces++
                    }
                    if (model[x, y - 1, z] == 0) { // -y (OBJ +z)
                        quad(sb, vertex(x, y, z), vertex(x + 1, y, z), vertex(x + 1, y, z + 1), vertex(x, y, z + 1))
                        faces++
                    }
                }
            }
        }
        sb.insert(sb.indexOf("\no "), "\n# faces: $faces\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Appends one quad face line. */
    private fun quad(sb: StringBuilder, a: Int, b: Int, c: Int, dd: Int) {
        sb.append("f ").append(a).append(' ').append(b).append(' ').append(c).append(' ').append(dd).append('\n')
    }

    /**
     * Renders the four yaw views of [model] (0°/90°/180°/270°) into a
     * centered, shared-canvas looping GIF.
     *
     * @param tile screen pixels per voxel edge, `1..64`
     * ([VoxelRenderer.MAX_TILE]).
     * @param frameDelayMs per-frame delay; must be ≥ 20 ms (the GIF
     * format stores centiseconds).
     * @return GIF89a bytes.
     * @throws IllegalArgumentException on invalid [tile] or delay, or if
     * the shared canvas would exceed the 268M-pixel budget.
     */
    fun exportRotationGif(
        model: VoxelModel,
        tile: Int = 4,
        frameDelayMs: Int = 250,
    ): ByteArray {
        require(tile in 1..VoxelRenderer.MAX_TILE) { "tile must be in [1, 64] (was $tile)" }
        require(frameDelayMs >= 20) { "frameDelayMs must be >= 20 (was $frameDelayMs)" }
        // Render all four views first (transparent, tightly bounded).
        val views = (0 until 4).map { quarter ->
            VoxelRenderer.renderIsometric(VoxelOps.rotateYaw(model, quarter), tile)
        }
        // Shared canvas = per-axis max, frames centered.
        val canvasW = views.maxOf { it.width }
        val canvasH = views.maxOf { it.height }
        val canvasPixels = canvasW.toLong() * canvasH
        require(canvasPixels <= 268_435_456L) {
            "rotation canvas ${canvasW}x${canvasH} exceeds the 268M pixel budget; reduce the tile size"
        }
        val frames = views.map { view -> centerOnCanvas(view, canvasW, canvasH) }
        return KotlinGifEncoder.encode(
            canvasW,
            canvasH,
            frames,
            List(frames.size) { frameDelayMs },
            0, // loop forever
        )
    }

    /** Places [view] centered on a transparent canvas. */
    private fun centerOnCanvas(view: PixelFrame, canvasW: Int, canvasH: Int): PixelFrame {
        val out = IntArray(canvasW * canvasH)
        val offX = (canvasW - view.width) / 2
        val offY = (canvasH - view.height) / 2
        for (y in 0 until view.height) {
            System.arraycopy(
                view.pixels, y * view.width,
                out, (offY + y) * canvasW + offX,
                view.width,
            )
        }
        return PixelFrame.adopt(canvasW, canvasH, out)
    }

    /** Rough OBJ size estimate for the StringBuilder's initial capacity. */
    private fun estimateObjSize(model: VoxelModel): Int =
        (model.solidCount * 7 * 24 + 256).coerceAtMost(1 shl 24)
}
