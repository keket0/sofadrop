package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.JsonObject
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A movie, series or episode card focused in Projectivy: all it tells is the card's title, its type
 * (TvContract's PreviewProgramColumns.TYPE_*) and its image.
 */
data class FocusedCard(val title: String, val type: Int, val image: String?) {
    val media: MediaType = if (type == FocusedWallpapers.TYPE_MOVIE) MediaType.MOVIE else MediaType.TV

    /** The TMDB episode still the card shows ("/abc.jpg"), as Nuvio's "Continue watching" does. */
    val still: String? = if (type == FocusedWallpapers.TYPE_TV_EPISODE) FocusedWallpapers.stillOf(image) else null

    /** Names the card in caches and file names: its still, or its kind and title. */
    val key: String = still?.let { "still-${it.trim('/').substringBefore('.')}" }
        ?: "${media.path}-${Integer.toHexString(FocusedWallpapers.normalized(title).hashCode())}"
}

/**
 * Wallpaper of the card focused in Projectivy (see [Settings.focusedCard]), in beta. The title is found
 * on TMDB: an episode by its still when the card shows one (the series searched by the card's title, and
 * the still among its episodes), anything else by its exact name (Apple TV's channel names them in the
 * TV's language). It is then drawn like any wallpaper: with the still and the episode's synopsis, or as
 * its series or movie. What each card gave is kept in [FocusedLog], for users to send.
 *
 * Projectivy doesn't wait for the answer, but applies it when it comes: the request waits for the drawing
 * (a few seconds, in 1080p to be quicker). Drawn wallpapers stay in files/wallpapers/focused/, named after
 * the look-affecting settings, for [MAX_AGE_MILLIS] and up to [MAX_FILES].
 */
object FocusedWallpapers {
    private const val TAG = "FocusedWallpapers"
    const val DIR = "focused"
    /** How often a waiting request checks that its card still has the focus. */
    private const val WAIT_SLICE_MILLIS = 200L
    private const val JPEG_QUALITY = 90
    private const val MAX_WIDTH = 1920
    private const val MAX_FILES = 50
    private const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L
    /** Search results tried for an episode's still: the first is almost always it, the next for series with the same name. */
    private const val SHOWS_TRIED = 3
    /**
     * Narrower episode stills are low quality for [Settings.FOCUSED_EPISODE_SHARP]: TMDB has many at 400 or
     * 640 px, blurry across a TV; 1280 px ones still look fine at 1080p.
     */
    private const val MIN_STILL_WIDTH = 1280
    /** Results named in the log when none is named like the card. */
    private const val CLOSEST_LOGGED = 3

    /** TvContractCompat.PreviewProgramColumns: the types that are a movie or a series. */
    const val TYPE_MOVIE = 0
    const val TYPE_TV_SERIES = 1
    const val TYPE_TV_SEASON = 2
    const val TYPE_TV_EPISODE = 3

    private val stillPattern = Regex("""^https://image\.tmdb\.org/t/p/[a-z0-9]+(/[A-Za-z0-9]+\.jpg)$""")
    private val marks = Regex("""\p{M}+""")
    private val notAlphanumeric = Regex("""[^\p{L}\p{N}]+""")
    /** Between a series and its episode in a card's title: a spaced hyphen or dash. */
    private val seriesSeparator = Regex("""\s[-–—]\s""")

    /** What each card is, by [FocusedCard.key]. */
    private val found = ConcurrentHashMap<String, Match>()
    /** Width of each episode still's original, by its path, once downloaded. */
    private val stillWidths = ConcurrentHashMap<String, Int>()
    /** Cards that matched nothing: not searched again until Sofadrop restarts. */
    private val unmatched = ConcurrentHashMap.newKeySet<String>()
    private val drawing = ConcurrentHashMap<String, Future<*>>()
    private val executor = Executors.newSingleThreadExecutor()
    /** The last card asked for: cards queued before it was focused aren't drawn. */
    @Volatile private var wanted: String? = null

    private data class Episode(val season: Int, val number: Int, val name: String, val overview: String)
    /** What the card is, and how it was found (for [FocusedLog]). */
    private data class Match(val ref: TitleRef, val episode: Episode?, val note: String)

    /** The card for a focused program, when it's a movie, series or episode with a title; null otherwise. */
    fun card(title: String?, type: Int?, iconUri: String?): FocusedCard? {
        if (type == null || type !in TYPE_MOVIE..TYPE_TV_EPISODE || title.isNullOrBlank()) return null
        return FocusedCard(title.trim(), type, iconUri)
    }

    /** True when TMDB has nothing named like [card] (until Sofadrop restarts). */
    fun matchesNothing(card: FocusedCard): Boolean = card.key in unmatched

    /** "/abc.jpg" from a TMDB image URL of any size; null for any other image. */
    fun stillOf(iconUri: String?): String? = iconUri?.let { stillPattern.find(it)?.groupValues?.get(1) }

    /** "matchboxlapelicula" for "Matchbox: La película": names compared without case, accents or punctuation. */
    fun normalized(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace(notAlphanumeric, "")

    /**
     * The wallpaper for [card] in [mode] ([Settings.FOCUSED_EPISODE] or [Settings.FOCUSED_SERIES]), waiting up
     * to [waitMillis] for it to be drawn while [stillWanted]. Until then: the episode's still alone (episode mode), or null.
     */
    fun wallpaper(
        context: Context, card: FocusedCard, mode: String, waitMillis: Long = 0, stillWanted: () -> Boolean = { true },
    ): Wallpaper? {
        val prefix = filePrefix()
        cached(context, card, mode, prefix)?.let { return finished(context, it, card) }
        if (card.key in unmatched) return fallback(card, mode)
        val taskKey = "$mode/${card.key}"
        wanted = taskKey
        val task = drawing.computeIfAbsent(taskKey) {
            val appContext = context.applicationContext
            executor.submit {
                try {
                    if (wanted == taskKey) draw(appContext, card, mode, prefix)
                    else Log.d(TAG, "${card.title}: no longer focused, not drawn")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not draw ${card.title}", e)
                    FocusedLog.add(appContext, card, "failed · ${GenerationLog.describe(e)}")
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Out of memory drawing ${card.title}", e)
                    FocusedLog.add(appContext, card, "failed · out of memory")
                } finally {
                    drawing.remove(taskKey)
                }
            }
        }
        if (waitMillis > 0) {
            // In short slices, so a request for a card the focus has left frees Projectivy's thread at once
            val deadline = System.currentTimeMillis() + waitMillis
            while (stillWanted() && !task.isDone) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    task.get(minOf(left, WAIT_SLICE_MILLIS), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                } catch (_: ExecutionException) {
                }
            }
            cached(context, card, mode, prefix)?.let { return finished(context, it, card) }
        }
        return fallback(card, mode)
    }

    /** [card]'s drawn wallpaper, once it's known what the card is and drawn with the current settings. */
    private fun cached(context: Context, card: FocusedCard, mode: String, prefix: String): File? {
        val match = found[card.key] ?: return null
        val file = fileOf(context, prefix, match, mode).takeIf { it.exists() } ?: return null
        // The ones in use stay longest
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    private fun finished(context: Context, file: File, card: FocusedCard): Wallpaper {
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.wallpapers")
            .appendPath(DIR).appendPath(file.name).build()
        return wallpaper(uri.toString(), card.title)
    }

    /** The episode's still alone while its wallpaper is drawn; with [Settings.FOCUSED_EPISODE_SHARP], once it's known to be sharp. */
    private fun fallback(card: FocusedCard, mode: String): Wallpaper? {
        val still = card.still ?: return null
        val shown = when (mode) {
            Settings.FOCUSED_EPISODE -> true
            Settings.FOCUSED_EPISODE_SHARP -> (stillWidths[still] ?: 0) >= MIN_STILL_WIDTH
            else -> false
        }
        return if (shown) wallpaper(TmdbClient.IMAGE_URL + "original" + still, card.title) else null
    }

    /** [still] is at least [MIN_STILL_WIDTH] wide (its original, which the drawing downloads anyway); not when it can't be read. */
    private fun sharp(client: TmdbClient, still: String): Boolean {
        val width = stillWidths[still] ?: run {
            val bytes = client.image(still, "original") ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bounds.outWidth.also { stillWidths[still] = it }
        }
        return width >= MIN_STILL_WIDTH
    }

    private fun wallpaper(uri: String, title: String) =
        Wallpaper(uri = uri, type = WallpaperType.IMAGE, displayMode = WallpaperDisplayMode.CROP, title = title)

    private fun dir(context: Context) = File(File(context.filesDir, WallpaperStore.DIR), DIR)

    /** Drawn with other settings, it's another file. */
    private fun filePrefix() = Integer.toHexString(Profile.WALLPAPER.signature.hashCode())

    /**
     * "1a2b3c-tv-1399.jpg" for a series or a movie, "1a2b3c-tv-1399-s1e3.jpg" for an episode ("…-s1e3-sharp.jpg"
     * with [Settings.FOCUSED_EPISODE_SHARP], which may have the series' image).
     */
    private fun fileOf(context: Context, prefix: String, match: Match, mode: String): File {
        val episode = match.episode?.takeIf { Settings.showsEpisodes(mode) }
            ?.let { "-s${it.season}e${it.number}" + if (mode == Settings.FOCUSED_EPISODE_SHARP) "-sharp" else "" }.orEmpty()
        return File(dir(context), "$prefix-${match.ref.type.path}-${match.ref.tmdbId}$episode.jpg")
    }

    private fun draw(context: Context, card: FocusedCard, mode: String, prefix: String) {
        val start = System.currentTimeMillis()
        Settings.init(context)
        val profile = Profile.WALLPAPER
        val factory = WallpaperFactory(context, minOf(profile.outputWidth, MAX_WIDTH), profile)
        val match = found[card.key] ?: find(context, factory.client, card)?.also { found[card.key] = it }
        if (match == null) {
            unmatched += card.key
            return
        }
        val file = fileOf(context, prefix, match, mode)
        if (file.exists()) {
            FocusedLog.add(context, card, "${match.note} · cached")
            return
        }
        val info = factory.build(match.ref) ?: run {
            FocusedLog.add(context, card, "${match.note} · not drawn")
            return
        }
        val details = profile.drawsDetails
        val language = Settings.language
        val episode = match.episode?.takeIf { Settings.showsEpisodes(mode) }
        // A low-quality still gives way to the series' backdrop, under the episode's details
        val still = card.still?.takeIf { episode != null && (mode != Settings.FOCUSED_EPISODE_SHARP || sharp(factory.client, it)) }
        val lowQuality = if (episode != null && card.still != null && still == null) " · low-quality still (${stillWidths[card.still] ?: "?"} px): series image" else ""
        // No list behind it: no list name in the footer
        val drawn = info.copy(sourceLabel = null, sourceProvider = null).let { title ->
            if (episode == null || card.still == null) title
            else title.copy(
                backdropPath = still ?: title.backdropPath,
                extraInfo = if (details && profile.showsInHeader(Settings.HEADER_RUNTIME))
                    listOf(Languages.episodeCode(language, episode.season, episode.number), episode.name).filter { it.isNotBlank() }.joinToString(" · ")
                else title.extraInfo,
                // Untranslated: the series' synopsis, which already follows the synopsis setting
                overview = if (details) episode.overview.ifBlank { title.overview } else title.overview,
            )
        }
        val bitmap = factory.render(drawn)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        tmp.renameTo(file)
        prune(context)
        val seconds = (System.currentTimeMillis() - start) / 100 / 10.0
        Log.i(TAG, "${card.title}: ${file.name} drawn in $seconds s")
        FocusedLog.add(context, card, "${match.note} · ${if (episode != null) "episode" else match.ref.type.path} in $seconds s$lowQuality")
    }

    /** Older than [MAX_AGE_MILLIS] or beyond the [MAX_FILES] last used: they go. */
    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
            if (index >= MAX_FILES || now - file.lastModified() > MAX_AGE_MILLIS) file.delete()
        }
    }

    /** What [card] is; logged once drawn, or here when it's nothing TMDB has (with what it found instead). */
    private fun find(context: Context, client: TmdbClient, card: FocusedCard): Match? {
        card.still?.let { findEpisode(client, card)?.let { return it } }
        val stillMissed = if (card.still != null) "still matched no episode · " else ""
        val (match, closest) = findTitle(client, card)
        if (match == null) FocusedLog.add(context, card, "${stillMissed}nothing named so on TMDB · closest: ${closest.ifEmpty { "none" }}")
        return match?.let { it.copy(note = stillMissed + it.note) }
    }

    /** The episode whose still is [card]'s image, among the first series named like it; the latest seasons first. */
    private fun findEpisode(client: TmdbClient, card: FocusedCard): Match? {
        val language = Settings.language
        val shows = client.get("search/tv?query=${URLEncoder.encode(card.title, "UTF-8")}&language=$language")
            ?.objects("results").orEmpty().take(SHOWS_TRIED)
        for (show in shows) {
            val id = show.int("id") ?: continue
            val seasons = client.get("tv/$id?language=$language")?.objects("seasons").orEmpty()
                .mapNotNull { it.int("season_number") }.sortedByDescending { if (it == 0) -1 else it }
            for (season in seasons) {
                val episode = client.get("tv/$id/season/$season?language=$language")?.objects("episodes").orEmpty()
                    .firstOrNull { it.str("still_path") == card.still } ?: continue
                val number = episode.int("episode_number") ?: 0
                val found = Episode(season, number, episode.str("name").orEmpty(), episode.str("overview").orEmpty())
                return Match(TitleRef(MediaType.TV, id), found, "tv/$id ${show.str("name").orEmpty()} S${season}E$number by still")
            }
        }
        return null
    }

    /**
     * The movie or series named exactly like [card] (or originally named so), in the TV's language and then the
     * wallpapers'; with several, the first TMDB gives (the most relevant). Its note says how sure it was.
     * Without one, the first results instead ("Lucky Hank (2023), …"), for the log.
     */
    private fun findTitle(client: TmdbClient, card: FocusedCard): Pair<Match?, String> {
        val (match, closest) = findNamed(client, card.title, card.media, "name")
        if (match != null) return match to closest
        // Paramount+ names its series' cards after the series and the episode: the series alone. Not for movies,
        // whose subtitles follow a " - " too ("Mission: Impossible - Dead Reckoning" isn't the 1966 series)
        if (card.media != MediaType.TV) return null to closest
        val series = seriesNameOf(card.title) ?: return null to closest
        return findNamed(client, series, MediaType.TV, "series name").first to closest
    }

    /** "Marshals" for "Marshals - Mehr Schein als Sein"; null for a title without " - ". */
    fun seriesNameOf(title: String): String? =
        seriesSeparator.find(title)?.let { title.substring(0, it.range.first).trim() }?.takeIf { it.isNotEmpty() }

    private fun findNamed(client: TmdbClient, title: String, media: MediaType, how: String): Pair<Match?, String> {
        val target = normalized(title)
        val path = media.path
        val name = if (media == MediaType.MOVIE) "title" else "name"
        val original = "original_$name"
        var closest = ""
        for (language in listOf(Locale.getDefault().toLanguageTag(), Settings.language).distinct()) {
            val results = client.get("search/$path?query=${URLEncoder.encode(title, "UTF-8")}&language=$language")
                ?.objects("results").orEmpty()
            if (closest.isEmpty()) closest = results.take(CLOSEST_LOGGED).joinToString(", ") { "${it.str(name).orEmpty()} (${yearOf(it)})" }
            val exact = results.filter { result -> listOf(name, original).any { normalized(result.str(it).orEmpty()) == target } }
            val first = exact.firstOrNull() ?: continue
            val id = first.int("id") ?: continue
            val others = if (exact.size > 1) " · ${exact.size} named so" else ""
            return Match(TitleRef(media, id), null, "$path/$id ${first.str(name).orEmpty()} (${yearOf(first)}) by $how $language$others") to closest
        }
        return null to closest
    }

    private fun yearOf(result: JsonObject) = (result.str("release_date") ?: result.str("first_air_date")).orEmpty().take(4).ifEmpty { "?" }
}
