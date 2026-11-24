package com.rfmarcos.sofadrop

/**
 * Decides when the main settings screen shows the temporary "Support the project" row at the top
 * (Ko-fi in GitHub builds, Supporter purchase in Google Play builds). Never shown on first use,
 * never alongside an app update, dismissible at any time, and auto-hidden after [MAX_SHOWS] visits
 * so it stays unobtrusive (About always keeps the permanent entry).
 */
object SupportPrompt {
    const val MAX_SHOWS = 5
    const val MIN_OPENS_EARLY = 3
    const val MIN_OPENS_RETURN = 2
    const val MIN_GENERATIONS_RETURN = 2
    const val MIN_DAYS_RETURN = 2L
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun shouldShow(
        dismissed: Boolean,
        shownCount: Int,
        updateAvailable: Boolean,
        hasSupportOption: Boolean,
        hasGenerated: Boolean,
        settingsOpens: Int,
        generationCount: Int,
        firstSeenAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (dismissed || shownCount >= MAX_SHOWS || updateAvailable || !hasSupportOption || !hasGenerated) return false
        val days = if (firstSeenAt > 0L) (now - firstSeenAt).coerceAtLeast(0L) / DAY_MILLIS else 0L
        return settingsOpens >= MIN_OPENS_EARLY ||
            (settingsOpens >= MIN_OPENS_RETURN && (generationCount >= MIN_GENERATIONS_RETURN || days >= MIN_DAYS_RETURN))
    }

    /** Whether to show the top support row right now for this build flavor and install state. */
    fun shouldShow(updateAvailable: Boolean, supporterBlocked: Boolean = false): Boolean {
        val hasSupportOption = if (BuildConfig.PLAY) !supporterBlocked && !Settings.supporterBought
        else BuildConfig.SUPPORT_URL.isNotBlank()
        val hasGenerated = Settings.generationCount > 0 || Profile.WALLPAPER.lastGeneration > 0L || Profile.SCREENSAVER.lastGeneration > 0L
        return shouldShow(
            dismissed = Settings.supportDismissed,
            shownCount = Settings.supportShownCount,
            updateAvailable = updateAvailable,
            hasSupportOption = hasSupportOption,
            hasGenerated = hasGenerated,
            settingsOpens = Settings.settingsOpens,
            generationCount = Settings.generationCount,
            firstSeenAt = Settings.firstSeenAt,
        )
    }
}
