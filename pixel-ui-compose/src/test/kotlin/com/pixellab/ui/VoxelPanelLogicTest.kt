package com.pixellab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxelPanelLogicTest {

    @Test
    fun `wrapTurns keeps the index in range`() {
        assertEquals(0, VoxelPanelLogic.wrapTurns(0))
        assertEquals(1, VoxelPanelLogic.wrapTurns(5))
        assertEquals(3, VoxelPanelLogic.wrapTurns(-1))
        assertEquals(0, VoxelPanelLogic.wrapTurns(-4))
        assertEquals(2, VoxelPanelLogic.wrapTurns(10))
    }

    @Test
    fun `drag below the threshold keeps the same turn`() {
        assertEquals(0, VoxelPanelLogic.turnsFromDrag(0, 10f))
        assertEquals(0, VoxelPanelLogic.turnsFromDrag(0, -10f))
        assertEquals(2, VoxelPanelLogic.turnsFromDrag(2, 95f))
    }

    @Test
    fun `drag past the threshold turns one step`() {
        assertEquals(1, VoxelPanelLogic.turnsFromDrag(0, 96f))
        assertEquals(2, VoxelPanelLogic.turnsFromDrag(0, 192f))
        assertEquals(3, VoxelPanelLogic.turnsFromDrag(0, -96f))
        assertEquals(0, VoxelPanelLogic.turnsFromDrag(1, -96f))
    }

    @Test
    fun `residual drag keeps the sub-threshold remainder`() {
        assertEquals(0f, VoxelPanelLogic.residualDrag(0f), 0.01f)
        assertEquals(40f, VoxelPanelLogic.residualDrag(136f), 0.01f)
        assertEquals(-20f, VoxelPanelLogic.residualDrag(-116f), 0.01f)
    }

    @Test
    fun `tile zoom is clamped to bounds`() {
        assertEquals(2, VoxelPanelLogic.clampTile(0))
        assertEquals(2, VoxelPanelLogic.clampTile(1))
        assertEquals(8, VoxelPanelLogic.clampTile(8))
        assertEquals(16, VoxelPanelLogic.clampTile(99))
    }
}
