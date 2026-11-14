package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollVideoTest {

    @Test
    fun framesHoldMoveAndHold() {
        // Two lines over the window, 66 px each: 6 s of scrolling
        val scroll = 2 * ScrollingSynopsis.LINE_MILLIS
        val overflow = 132f
        val offsetAt = { millis: Long ->
            if (millis <= ScrollingSynopsis.START_HOLD_MILLIS) 0f
            else minOf(overflow, (millis - ScrollingSynopsis.START_HOLD_MILLIS) * 66f / ScrollingSynopsis.LINE_MILLIS)
        }
        val frames = ScrollVideo.frames(scroll, offsetAt)
        val times = frames.map { it.second }
        assertEquals(0f to 0L, frames.first())
        val duration = ScrollingSynopsis.START_HOLD_MILLIS + scroll + ScrollingSynopsis.END_HOLD_MILLIS
        assertEquals(overflow to duration, frames.last())
        assertTrue("times go up", times.zipWithNext().all { (a, b) -> b > a })
        // No long gap anywhere (Android's MP4 writer loses a long first one), and the movement at 30 fps
        assertTrue("short gaps", times.zipWithNext().all { (a, b) -> b - a <= 250 })
        assertEquals(180, frames.count { it.second >= ScrollingSynopsis.START_HOLD_MILLIS && it.second < ScrollingSynopsis.START_HOLD_MILLIS + scroll })
        assertTrue("offsets never go back", frames.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
    }
}
