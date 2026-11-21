package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPositionTest {

    @Test
    fun customPositionsRoundTrip() {
        assertEquals(30 to 70, Settings.customPoint(Settings.customPosition(30, 70)))
        // Kept inside the screen; anything else isn't a custom position
        assertEquals(100 to 0, Settings.customPoint(Settings.customPosition(140, -5)))
        assertNull(Settings.customPoint("top_left"))
        assertNull(Settings.customPoint("custom:50"))
        assertNull(Settings.customPoint("custom:50x101"))
        // No comma: the rotation is a comma-separated list of them
        assertFalse(',' in Settings.customPosition(30, 70))
        // Moving starts where a fixed position has the block
        assertEquals(100 to 50, Settings.pointOf("center_right"))
        assertEquals(0 to 100, Settings.pointOf("bottom_left"))
        assertTrue(Settings.onRight("custom:60x10") && Settings.onTop("custom:60x10"))
        assertFalse(Settings.onRight("custom:50x90") || !Settings.onBottom("custom:50x90"))
        // Shaded around the block away from the sides, by the side shading near them
        assertTrue(Settings.centred("custom:50x90"))
        assertFalse(Settings.centred("custom:20x50") || Settings.centred("custom:80x50") || Settings.centred("center_left"))
    }

    @Test
    fun theBlockStaysOnTheWallpaper() {
        // A 1000 × 550 block drawn from (100, 50) on 3840 × 2160: from edge to edge, no margins
        assertEquals(-100f to -50f, WallpaperRenderer.customOffset(100, 50, 1100, 600, 3840, 2160, 0, 0))
        assertEquals(2740f to 1560f, WallpaperRenderer.customOffset(100, 50, 1100, 600, 3840, 2160, 100, 100))
        assertEquals(1320f to 755f, WallpaperRenderer.customOffset(100, 50, 1100, 600, 3840, 2160, 50, 50))
        // A block taller than the wallpaper stays at the top
        assertEquals(0f, WallpaperRenderer.customOffset(100, 0, 1100, 2200, 3840, 2160, 0, 100).second)
    }
}
