package com.rfmarcos.sofadrop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_700_000_000_000L

    @Test
    fun hiddenOnFirstUseOrBeforeGenerating() {
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = false, settingsOpens = 5, generationCount = 0, firstSeenAt = t0, now = t0 + 10 * day,
        ))
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 1, generationCount = 1, firstSeenAt = t0, now = t0,
        ))
        // Second open on the same day with just one generation: still setting up
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 2, generationCount = 1, firstSeenAt = t0, now = t0 + 3_600_000L,
        ))
    }

    @Test
    fun shownOnThirdOpenOrAfterReturningLater() {
        // Early active user tweaking settings on day 1-2 (3rd open after generating)
        assertTrue(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 3, generationCount = 1, firstSeenAt = t0, now = t0 + 3_600_000L,
        ))
        // Set-and-forget user returning 2 days later on their 2nd open
        assertTrue(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 2, generationCount = 1, firstSeenAt = t0, now = t0 + 2 * day,
        ))
        // Returning after 2+ generations on their 2nd open
        assertTrue(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 2, generationCount = 2, firstSeenAt = t0, now = t0,
        ))
    }

    @Test
    fun respectsDismissMaxShowsUpdateAndAvailability() {
        assertFalse(SupportPrompt.shouldShow(
            dismissed = true, shownCount = 0, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 10, generationCount = 10, firstSeenAt = t0, now = t0 + 10 * day,
        ))
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = SupportPrompt.MAX_SHOWS, updateAvailable = false, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 10, generationCount = 10, firstSeenAt = t0, now = t0 + 10 * day,
        ))
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = true, hasSupportOption = true,
            hasGenerated = true, settingsOpens = 10, generationCount = 10, firstSeenAt = t0, now = t0 + 10 * day,
        ))
        assertFalse(SupportPrompt.shouldShow(
            dismissed = false, shownCount = 0, updateAvailable = false, hasSupportOption = false,
            hasGenerated = true, settingsOpens = 10, generationCount = 10, firstSeenAt = t0, now = t0 + 10 * day,
        ))
    }
}
