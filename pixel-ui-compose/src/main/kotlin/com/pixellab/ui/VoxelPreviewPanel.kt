package com.pixellab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.voxel.DepthMode
import com.pixellab.core.voxel.VoxelOps
import com.pixellab.core.voxel.VoxelRenderer
import com.pixellab.core.voxel.Voxelizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Horizontal pixels of drag that turn the model by one 90° step. */
private const val DragPixelsPerTurn = 96f

/** Lowest/highest on-screen voxel edge. */
private const val MinTile = 2
private const val MaxTile = 16

/** Lowest/highest extrusion depth offered by the panel. */
private const val MinDepth = 1
private const val MaxDepth = 12

/**
 * Pure gesture/geometry logic of [VoxelPreviewPanel] — unit-testable on
 * the JVM with zero Compose types.
 */
object VoxelPanelLogic {

    /** Quarter-turn index after dragging [dragPixels] horizontally. */
    fun turnsFromDrag(baseTurns: Int, dragPixels: Float): Int {
        val steps = (dragPixels / DragPixelsPerTurn).toInt()
        return wrapTurns(baseTurns + steps)
    }

    /** Wraps a quarter-turn count into `0..3`. */
    fun wrapTurns(turns: Int): Int = ((turns % 4) + 4) % 4

    /** Accumulated drag after a turn was consumed (residual kept for the
     * next threshold crossing). */
    fun residualDrag(dragPixels: Float): Float =
        dragPixels - (dragPixels / DragPixelsPerTurn).toInt() * DragPixelsPerTurn

    /** Clamps a tile zoom. */
    fun clampTile(tile: Int): Int = tile.coerceIn(MinTile, MaxTile)
}

/**
 * Interactive pixel-art-native 3D preview: voxelizes [frame] and renders
 * it through the isometric engine on a background dispatcher.
 *
 * Gestures: horizontal drag rotates the model in 90° steps (the render
 * stays on the integer 2:1 lattice — pixel-perfect at every angle, no
 * smeared mid-frames). The control row offers explicit turn buttons,
 * tile zoom, extrusion depth and the three depth modes.
 *
 * While a render is in flight the panel shows a transparent placeholder
 * of the previous render's size (the engine renders typical sprite sizes
 * in single-digit milliseconds, so the placeholder rarely shows).
 */
@Composable
fun VoxelPreviewPanel(
    frame: PixelFrame,
    modifier: Modifier = Modifier,
    initialDepth: Int = 4,
    initialMode: DepthMode = DepthMode.UNIFORM,
) {
    var quarterTurns by remember { mutableIntStateOf(0) }
    var tile by remember { mutableIntStateOf(4) }
    var depth by remember { mutableIntStateOf(initialDepth.coerceIn(MinDepth, MaxDepth)) }
    var mode by remember { mutableStateOf(initialMode) }
    var dragAccumulator by remember { mutableStateOf(0f) }

    val render by produceState(
        initialValue = null as PixelFrame?,
        frame,
        quarterTurns,
        tile,
        depth,
        mode,
    ) {
        value = withContext(Dispatchers.Default) {
            val model = Voxelizer.voxelize(frame, depth, mode)
            val turned = VoxelOps.rotateYaw(model, quarterTurns)
            VoxelRenderer.renderIsometric(turned, tile)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "3D preview — isometric voxel render",
                style = MaterialTheme.typography.titleSmall,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .pointerInput(frame) {
                        detectDragGestures(
                            onDragEnd = { dragAccumulator = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount.x
                            val next = VoxelPanelLogic.turnsFromDrag(quarterTurns, dragAccumulator)
                            if (next != quarterTurns) {
                                quarterTurns = next
                                dragAccumulator = VoxelPanelLogic.residualDrag(dragAccumulator)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val current = render
                if (current != null) {
                    val bitmap = remember(current) {
                        android.graphics.Bitmap.createBitmap(
                            current.pixels,
                            current.width,
                            current.height,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        ).asImageBitmap()
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Isometric voxel render of the current frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(
                        text = "Rendering…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(onClick = { quarterTurns = VoxelPanelLogic.wrapTurns(quarterTurns - 1) }) {
                    Icon(Icons.Filled.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(onClick = { quarterTurns = VoxelPanelLogic.wrapTurns(quarterTurns + 1) }) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate right")
                }
                Text(
                    text = "${quarterTurns * 90}°",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                IconButton(onClick = { tile = VoxelPanelLogic.clampTile(tile - 1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
                Text(
                    text = "zoom $tile",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                IconButton(onClick = { tile = VoxelPanelLogic.clampTile(tile + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DepthMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { mode = candidate },
                        label = {
                            Text(
                                when (candidate) {
                                    DepthMode.UNIFORM -> "solid"
                                    DepthMode.LUMA_RELIEF -> "relief"
                                    DepthMode.OUTLINE -> "outline"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
            Column {
                Text(
                    text = "extrusion depth: $depth",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = depth.toFloat(),
                    onValueChange = { depth = it.toInt().coerceIn(MinDepth, MaxDepth) },
                    valueRange = MinDepth.toFloat()..MaxDepth.toFloat(),
                    steps = MaxDepth - MinDepth - 1,
                )
            }
        }
    }
}
