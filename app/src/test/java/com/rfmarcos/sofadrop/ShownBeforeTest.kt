package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper

class ShownBeforeTest {
    private val set = listOf("tv-4601.mp4", "tv-335981.jpg", "movie-1315772.jpg")
        .map { Wallpaper("content://com.rfmarcos.sofadrop.wallpapers/gen-1/$it") }

    @Test
    fun leavingACardGoesBackToTheWallpaperItReplaced() {
        assertEquals(listOf(set[1]), WallpaperProviderService.shownBefore(set, "/gen-1/tv-335981.jpg"))
    }

    @Test
    fun unknownOrGoneWallpaperGivesTheSet() {
        assertEquals(set, WallpaperProviderService.shownBefore(set, null))
        // From a set that was replaced since
        assertEquals(set, WallpaperProviderService.shownBefore(set, "/gen-0/tv-335981.jpg"))
    }
}
