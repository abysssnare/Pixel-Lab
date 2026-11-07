package com.pixellab.core.voxel

/**
 * A dense voxel volume built from a [com.pixellab.core.model.PixelFrame]:
 * `width x height x depth` cells, row-major by `(y, height-slice, x)` —
 * `voxelIndex = y * width * depth + z * width + x` — each holding a packed
 * `0xAARRGGBB` color or `0` for empty space.
 *
 * The voxel grid axes: **x** = frame x (screen right in the default yaw),
 * **y** = frame y (screen depth — the frame's vertical axis becomes the
 * ground plane), **z** = extrusion axis (screen up). Converting a sprite
 * "up" (−y in frame space) to world "up" (+z) happens in
 * [Voxelizer.voxelize] so renderers never deal with flipped frames.
 *
 * Content-level [equals]/[hashCode] like every core model.
 */
class VoxelModel private constructor(
    val width: Int,
    val height: Int,
    val depth: Int,
    val pixels: IntArray,
) {

    /** Voxel at (x, y, z) — `0` when empty, or out of bounds. */
    operator fun get(x: Int, y: Int, z: Int): Int {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) return 0
        return pixels[y * width * depth + z * width + x]
    }

    /** Total number of non-empty voxels. */
    val solidCount: Int by lazy { pixels.count { it != 0 } }

    /** True when at least one voxel is solid. */
    fun isNotEmpty(): Boolean = solidCount > 0

    override fun equals(other: Any?): Boolean =
        other is VoxelModel &&
            other.width == width &&
            other.height == height &&
            other.depth == depth &&
            other.pixels.contentEquals(pixels)

    override fun hashCode(): Int =
        31 * (31 * (31 * width + height) + depth) + pixels.contentHashCode()

    companion object {
        /** Hard cap on voxel cells (w*h*d) — 4M cells ≈ 16 MB IntArray. */
        const val MAX_CELLS = 4_194_304L

        /**
         * Builds a model from a densely packed voxel array (ownership
         * transferred — callers must not mutate afterwards).
         *
         * @throws IllegalArgumentException on non-positive dimensions or a
         * cell count above [MAX_CELLS].
         */
        fun adopt(width: Int, height: Int, depth: Int, pixels: IntArray): VoxelModel {
            require(width > 0 && height > 0 && depth > 0) {
                "voxel dimensions must be positive (was ${width}x${height}x${depth})"
            }
            require(pixels.size.toLong() == width.toLong() * height * depth) {
                "voxel array size ${pixels.size} does not match ${width}x${height}x${depth}"
            }
            require(width.toLong() * height * depth <= MAX_CELLS) {
                "voxel volume ${width}x${height}x${depth} exceeds the 4M-cell budget"
            }
            return VoxelModel(width, height, depth, pixels)
        }

        /** Defensive-copy factory for callers that keep the source array. */
        fun of(width: Int, height: Int, depth: Int, pixels: IntArray): VoxelModel =
            adopt(width, height, depth, pixels.copyOf())
    }
}

/**
 * How a frame's pixels map to voxel depth during extrusion.
 */
enum class DepthMode {
    /** Every opaque pixel extrudes to the full requested depth. */
    UNIFORM,

    /**
     * Luminance-driven relief: bright pixels extrude thin, dark pixels
     * thick (a dark outline towers over a bright core — the classic
     * "shaded bas-relief" pixel-art 3D look). Depth interpolates between
     * 1 and the requested depth by Rec.709 luma.
     */
    LUMA_RELIEF,

    /** Only outline pixels (having a transparent 4-neighbor) extrude —
     * a wireframe skeleton of the silhouette. */
    OUTLINE,
}
