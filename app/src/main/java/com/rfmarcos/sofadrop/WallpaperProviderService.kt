package com.rfmarcos.sofadrop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** The card in focus while [Settings.focusedCard] is on (see [FocusedWallpapers]). */
    @Volatile private var focusedCard: FocusedCard? = null

    /** Projectivy shows [focusedCard]'s wallpaper, which must give way to the set when the focus leaves it. */
    @Volatile private var showingCard = false

    /** Focus is on a non-media card (app tile, YouTube clip, unmatched card) while not idle (issue #38). */
    @Volatile private var onOtherCard = false

    /** Projectivy currently shows the non-media fallback wallpaper, and with which card colour (for card_color mode). */
    @Volatile private var showingFallback = false
    @Volatile private var showingFallbackColor = 0
    @Volatile private var lastCardColor = 0
    @Volatile private var launcherIdle = false

    /**
     * Projectivy got the set since it bound this service. Started cold (after a reboot), it shows a black
     * background until it gets a list, and its first request is often a card event: answering that one
     * empty left the screen black until the first TimeElapsed (reported 2026-11-15).
     */
    @Volatile private var servedSet = false

    private val binder = object : IWallpaperProviderService.Stub() {

        /**
         * Projectivy asks every few seconds (TimeElapsed) and, as the manifest's updateMode asks, when a card
         * is focused and when it goes idle; there's no request of its own on start. An empty answer leaves
         * the wallpaper as it is, so the first request of any kind gets the set.
         */
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            Log.d(TAG, "getWallpapers(${event?.javaClass?.simpleName})")
            // Card events come with every move of the remote: the settings file is rewritten once a minute at most
            val now = System.currentTimeMillis()
            if (now - Settings.projectivyAskedAt > ASKED_AT_EVERY_MILLIS) Settings.projectivyAskedAt = now
            val mode = Settings.focusedCard
            val fallback = if (mode == Settings.FOCUSED_OFF) Settings.FOCUSED_FALLBACK_SET else Settings.focusedFallback
            when (event) {
                is Event.ProgramCardFocused -> {
                    launcherIdle = false
                    val card = if (mode == Settings.FOCUSED_OFF) null else FocusedWallpapers.card(event.title, event.type, event.iconUri)
                    if (mode != Settings.FOCUSED_OFF && card == null) FocusedLog.ignored(this@WallpaperProviderService, event.title, event.type, event.iconUri)
                    focusedCard = card
                    if (card != null) {
                        onOtherCard = false
                        cardWallpaper(card, mode)?.let { return it }
                        // Still drawn, or focus moved on: the wallpaper stays. Found nothing: treat as non-media card
                        if (servedSet && (focusedCard !== card || !FocusedWallpapers.matchesNothing(card))) return emptyList()
                    }
                    if (fallback != Settings.FOCUSED_FALLBACK_SET) {
                        onOtherCard = true
                        lastCardColor = 0
                        return otherCardAnswer(fallback, 0)
                    }
                    onOtherCard = false
                }
                is Event.CardFocused -> {
                    launcherIdle = false
                    focusedCard = null
                    val color = event.darkColor.takeIf { it != 0 } ?: event.lightColor
                    lastCardColor = color
                    if (fallback != Settings.FOCUSED_FALLBACK_SET && Settings.focusedFallbackScope == Settings.FOCUSED_SCOPE_ALL) {
                        onOtherCard = true
                        return otherCardAnswer(fallback, color)
                    }
                    onOtherCard = false
                }
                is Event.LauncherIdleModeChanged -> {
                    launcherIdle = event.isIdle
                    focusedCard = null
                    if (event.isIdle) {
                        // Idle mode always rotates the set, even when a fallback is set for non-media cards
                        onOtherCard = false
                        val wasShowing = showingCard || showingFallback
                        showingCard = false
                        showingFallback = false
                        if (wasShowing || !servedSet) return wallpapers()
                        return emptyList()
                    }
                    if (servedSet) return emptyList()
                }
                is Event.TimeElapsed -> {
                    if (mode == Settings.FOCUSED_OFF) {
                        focusedCard = null
                        onOtherCard = false
                    } else {
                        focusedCard?.let { card ->
                            // The same wait as the card's own request: answering the set now would win over it
                            cardWallpaper(card, mode)?.let { return it }
                        }
                        if (focusedCard == null && onOtherCard && !launcherIdle && fallback != Settings.FOCUSED_FALLBACK_SET) {
                            return if (showingFallback) emptyList() else otherCardAnswer(fallback, lastCardColor)
                        }
                    }
                }
                else -> if (servedSet) return emptyList()
            }
            // Away from the card: its wallpaper gives way to the set; otherwise card events change nothing
            if (event !is Event.TimeElapsed && !showingCard && !showingFallback && servedSet) return emptyList()
            val leftCard = showingCard || showingFallback
            showingCard = false
            showingFallback = false
            // Given the set, Projectivy would show any of it: back to the one the card's replaced, and the
            // set again on the next TimeElapsed
            return wallpapers().let { set -> if (leftCard) shownBefore(set, WallpaperContentProvider.lastShown) else set }
        }

        /** Serves the non-media fallback wallpaper, avoiding redundant reloads when it's already on screen. */
        private fun otherCardAnswer(fallback: String, cardColor: Int): List<Wallpaper> {
            val rgb = cardColor and 0x00FFFFFF
            val unchanged = showingFallback && (fallback != Settings.FOCUSED_FALLBACK_CARD_COLOR || showingFallbackColor == rgb)
            if (unchanged && servedSet) return emptyList()
            GenerateWallpapersWorker.schedule(this@WallpaperProviderService)
            val wallpaper = FocusedWallpapers.otherCardWallpaper(
                this@WallpaperProviderService, fallback, cardColor, WallpaperContentProvider.lastShown,
            ) ?: return if (servedSet) emptyList() else wallpapers()
            showingCard = false
            showingFallback = true
            showingFallbackColor = rgb
            servedSet = true
            return listOf(wallpaper)
        }

        /**
         * [card]'s wallpaper, waiting for it to be drawn (Projectivy doesn't wait, but applies it when it
         * comes); null while it's drawn or found nothing, or once the focus moved on (its answer would
         * come after the next card's).
         */
        private fun cardWallpaper(card: FocusedCard, mode: String): List<Wallpaper>? {
            val wallpaper = FocusedWallpapers.wallpaper(this@WallpaperProviderService, card, mode, CARD_WAIT_MILLIS) {
                focusedCard === card
            } ?: return null
            if (focusedCard !== card) return null
            showingCard = true
            showingFallback = false
            return listOf(wallpaper)
        }

        private fun wallpapers(): List<Wallpaper> {
            // Never block Projectivy: return what we have and refresh in the background if needed
            val store = WallpaperStore(this@WallpaperProviderService)
            val dir = store.currentDir()
            val current = store.current(dir)
            // Settings changes wait for the next scheduled update (or "Update now"): only an empty
            // or expired set is regenerated from here, as a safety net for the periodic job
            val stale = System.currentTimeMillis() - Profile.WALLPAPER.lastGeneration > Settings.refreshIntervalMillis
            if (current.isEmpty() || stale) {
                GenerateWallpapersWorker.runNow(this@WallpaperProviderService)
            }
            GenerateWallpapersWorker.schedule(this@WallpaperProviderService)

            Log.i(TAG, "getWallpapers: ${current.size} wallpapers, stale=$stale, pendingChanges=${Profile.WALLPAPER.hasPendingChanges}")
            store.markServed(dir)
            val apps = ClickTargets.Apps(this@WallpaperProviderService)
            return (if (Profile.WALLPAPER.mixed) current.shuffled() else current).mapNotNull { wallpaper ->
                val video = store.videoUriOf(wallpaper, dir)
                val uri = video ?: store.uriOf(wallpaper, dir = dir) ?: return@mapNotNull null
                Wallpaper(
                    uri = uri.toString(),
                    type = if (video != null) WallpaperType.VIDEO else WallpaperType.IMAGE,
                    displayMode = WallpaperDisplayMode.CROP,
                    title = wallpaper.title,
                    source = "https://www.themoviedb.org/${wallpaper.type}/${wallpaper.tmdbId}",
                    author = "TMDB",
                    // "Watch" in Projectivy's "About this wallpaper" and a play button on its status bar
                    actionUri = actionUri(this@WallpaperProviderService, wallpaper, apps),
                )
            }.also { if (it.isNotEmpty()) servedSet = true }
        }

        override fun getPreferences(): String = Settings.export()

        override fun setPreferences(params: String) {
            Settings.import(params)
        }

    }

    companion object {
        private const val TAG = "SofadropService"
        /**
         * Longer than drawing takes on a Chromecast with Google TV (3-4 s, 10 s just after Sofadrop starts).
         * Only the focused card's request waits that long: the others stop waiting once the focus leaves them.
         */
        private const val CARD_WAIT_MILLIS = 15000L
        private const val ASKED_AT_EVERY_MILLIS = 60_000L

        /** The wallpaper of [set] at [path] (see [WallpaperContentProvider.lastShown]) alone; the set when it's not there. */
        internal fun shownBefore(set: List<Wallpaper>, path: String?): List<Wallpaper> =
            path?.let { set.filter { wallpaper -> wallpaper.uri.endsWith(path) } }.orEmpty().ifEmpty { set }

        /**
         * What Projectivy opens from [wallpaper] (see [ClickTargets]); applied when served, without
         * regenerating (except the platforms' pages).
         */
        fun actionUri(context: Context, wallpaper: StoredWallpaper, apps: ClickTargets.Apps = ClickTargets.Apps(context)): String? =
            ClickTargets.candidates(context, wallpaper, Profile.WALLPAPER, apps).firstOrNull()?.toUri(Intent.URI_INTENT_SCHEME)
    }
}
