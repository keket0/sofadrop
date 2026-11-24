package com.rfmarcos.sofadrop

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** What the user has watched, according to Trakt, Simkl, Plex and/or Jellyfin. */
sealed class WatchStatus {
    data object Watched : WatchStatus()
    data class InProgress(val completed: Int, val aired: Int) : WatchStatus()

    companion object {
        /** A series by its episodes: watched when all of them are, in progress when some are. */
        fun episodes(completed: Int, aired: Int): WatchStatus? = when {
            aired > 0 && completed >= aired -> Watched
            completed > 0 -> InProgress(completed, aired)
            else -> null
        }
    }
}

/**
 * Watched titles of the services chosen as watch history ([Settings.watchSources]), loaded once per
 * generation. With several, a title watched on any of them is watched; otherwise the most advanced one counts.
 */
class WatchedContent private constructor(
    private val trakt: TraktClient?,
    private val traktMovies: Set<Int>,
    private val traktShows: Map<Int, Int>,
    /** Simkl, Plex and Jellyfin: their whole history in one go. */
    private val others: List<Map<TitleRef, WatchStatus>>,
) {
    private val traktProgress = ConcurrentHashMap<Int, Any>()

    fun status(ref: TitleRef): WatchStatus? {
        val known = others.mapNotNull { it[ref] }
        // Trakt asks for each series' progress: not when another source already settles it
        known.firstOrNull { it is WatchStatus.Watched }?.let { return it }
        val all = known + listOfNotNull(traktStatus(ref))
        return all.firstOrNull { it is WatchStatus.Watched } ?: all.filterIsInstance<WatchStatus.InProgress>().maxByOrNull { it.completed }
    }

    private fun traktStatus(ref: TitleRef): WatchStatus? = when (ref.type) {
        MediaType.MOVIE -> if (ref.tmdbId in traktMovies) WatchStatus.Watched else null
        MediaType.TV -> {
            val traktId = traktShows[ref.tmdbId]
            val client = trakt
            if (traktId == null || client == null) null
            else {
                val cached = traktProgress[ref.tmdbId]
                if (cached != null) cached as? WatchStatus
                else {
                    // Don't cache network failures so another title or retry can still fetch progress
                    val (completed, aired) = runCatching { client.showProgress(traktId) }.getOrNull() ?: return null
                    val status = WatchStatus.episodes(completed, aired)
                    traktProgress[ref.tmdbId] = status ?: NONE
                    status
                }
            }
        }
    }

    companion object {
        private val NONE = Any()
        /**
         * Focused cards are drawn one by one, each with its own factory: they share the history for an hour.
         * A drawn card keeps its badge until the next day anyway (see FocusedWallpapers.filePrefix).
         */
        private const val REUSE_MILLIS = 60 * 60_000L
        @Volatile private var recent: Triple<Long, List<String>, WatchedContent>? = null

        /** Drop the cached history when accounts connect or disconnect. */
        fun clearCache() {
            recent = null
        }

        /** Null when no watch history is chosen (or none of its services could be read). */
        fun load(
            trakt: TraktClient?, simkl: SimklClient?, plex: () -> PlexClient?, jellyfin: JellyfinClient?, fresh: Boolean = false,
        ): WatchedContent? {
            val sources = Settings.watchSources
            if (sources.isEmpty()) return null
            if (!fresh) {
                recent?.let { (at, from, content) -> if (from == sources && System.currentTimeMillis() - at < REUSE_MILLIS) return content }
            }
            return read(sources, trakt, simkl, plex, jellyfin)?.also { recent = Triple(System.currentTimeMillis(), sources, it) }
        }

        private fun read(sources: List<String>, trakt: TraktClient?, simkl: SimklClient?, plex: () -> PlexClient?, jellyfin: JellyfinClient?): WatchedContent? {
            fun <T> read(name: String, chosen: String, block: () -> T?): T? = if (chosen !in sources) null
                else runCatching(block).onFailure { Log.w("WatchedContent", "Could not load $name history", it) }.getOrNull()
            val traktData = read("Trakt", Settings.WATCH_TRAKT) { trakt?.let { it.watchedMovies() to it.watchedShows() } }
            val others = listOfNotNull(
                read("Simkl", Settings.WATCH_SIMKL) { simkl?.watched() },
                read("Plex", Settings.WATCH_PLEX) { plex()?.watched() },
                read("Jellyfin", Settings.WATCH_JELLYFIN) { jellyfin?.watched() },
            )
            if (traktData == null && others.isEmpty()) return null
            return WatchedContent(trakt.takeIf { traktData != null }, traktData?.first.orEmpty(), traktData?.second.orEmpty(), others)
        }
    }
}
