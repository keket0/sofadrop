package com.rfmarcos.sofadrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
     * Narrower episode stills are low quality for [Settings.FOCUSED_EPISODE]: TMDB has many at 400 or
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
    /** " / " between a translated and an original title, as in kino.pub's "Терапия / Shrinking". */
    private val bilingualSeparator = Regex("""\s+/\s+""")

    /** What each card is, by language and [FocusedCard.key] (episode name/overview depend on language). */
    private val found = ConcurrentHashMap<String, Match>()
    private fun matchKey(card: FocusedCard) = "${Settings.language}/${card.key}"
    /** Videos of scrolling synopses: one at a time, apart from the drawings so they don't wait for them. */
    private val videoExecutor = Executors.newSingleThreadExecutor()
    /** Wallpaper files whose video is queued or being made. */
    private val encoding = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }
    /** Next to a wallpaper "x.jpg": its video, its scroll box and the files [ScrollingSynopsis.save] names. */
    private const val VIDEO = ".mp4"
    private const val SCROLL = ".scroll.json"
    private const val PATCH = "-patch.jpg"
    private const val STRIP = "-strip.png"
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

    /**
     * The names [title] may go by on TMDB: the whole title and, for a bilingual one like "Терапия / Shrinking"
     * (kino.pub), each side of the " / ".
     */
    fun titleVariants(title: String): List<String> =
        (listOf(title) + title.split(bilingualSeparator)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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
        val taskKey = "$mode/${card.key}"
        cached(context, card, mode, prefix)?.let { wanted = taskKey; return finished(context, it, card, taskKey) }
        if (card.key in unmatched) return fallback(card, mode)
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
            cached(context, card, mode, prefix)?.let { return finished(context, it, card, taskKey) }
        }
        return fallback(card, mode)
    }

    /** [card]'s drawn wallpaper, once it's known what the card is and drawn with the current settings. */
    private fun cached(context: Context, card: FocusedCard, mode: String, prefix: String): File? {
        val match = found[matchKey(card)] ?: return null
        val file = fileOf(context, prefix, match, mode).takeIf { it.exists() } ?: return null
        // The ones in use stay longest
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * The drawn [file], or its video once made (see [encodeLater]): Projectivy asks again every few seconds
     * while the card has the focus, and the video's first frame is the picture it's showing.
     */
    private fun finished(context: Context, file: File, card: FocusedCard, taskKey: String): Wallpaper {
        val video = companion(file, VIDEO).takeIf { it.exists() }
        if (video == null && companion(file, SCROLL).exists()) encodeLater(context.applicationContext, file, card, taskKey)
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.wallpapers")
            .appendPath(DIR).appendPath((video ?: file).name).build()
        return wallpaper(uri.toString(), card.title, video != null)
    }

    /** "…-tv-1399.mp4" next to "…-tv-1399.jpg". */
    private fun companion(file: File, suffix: String) = File(file.parentFile, file.nameWithoutExtension + suffix)

    /**
     * The video of [file], whose synopsis scrolls ([Settings.OVERVIEW_SCROLL]), one at a time behind the
     * drawings; only while its card is still the one wanted.
     */
    private fun encodeLater(context: Context, file: File, card: FocusedCard, taskKey: String) {
        if (!encoding.add(file.name)) return
        videoExecutor.execute {
            val start = System.currentTimeMillis()
            var image: Bitmap? = null
            var synopsis: ScrollingSynopsis? = null
            val tmp = File(file.parentFile, file.nameWithoutExtension + VIDEO + ".tmp")
            try {
                if (wanted != taskKey || companion(file, VIDEO).exists()) return@execute
                val box = json.decodeFromString<ScrollBox>(companion(file, SCROLL).readText())
                synopsis = ScrollingSynopsis.load(file.parentFile!!, box) ?: return@execute
                image = BitmapFactory.decodeFile(file.path) ?: return@execute
                ScrollVideo.encode(synopsis, image, tmp)
                tmp.renameTo(companion(file, VIDEO))
                val seconds = (System.currentTimeMillis() - start) / 100 / 10.0
                Log.i(TAG, "${card.title}: video in $seconds s")
                FocusedLog.add(context, card, "scrolling synopsis · video in $seconds s")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not encode the video of ${card.title}", e)
                FocusedLog.add(context, card, "video failed · ${GenerationLog.describe(e)}")
                tmp.delete()
            } finally {
                image?.recycle()
                synopsis?.recycle()
                encoding.remove(file.name)
            }
        }
    }

    /** The episode's still alone while its wallpaper is drawn, once it's known to be sharp ([Settings.FOCUSED_EPISODE] only). */
    private fun fallback(card: FocusedCard, mode: String): Wallpaper? {
        val still = card.still ?: return null
        val shown = mode == Settings.FOCUSED_EPISODE && (stillWidths[still] ?: 0) >= MIN_STILL_WIDTH
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

    private fun wallpaper(uri: String, title: String, video: Boolean = false) = Wallpaper(
        uri = uri, type = if (video) WallpaperType.VIDEO else WallpaperType.IMAGE, displayMode = WallpaperDisplayMode.CROP, title = title,
    )

    private fun dir(context: Context) = File(File(context.filesDir, WallpaperStore.DIR), DIR)

    /** Drawn with other settings, it's another file. */
    private fun filePrefix(): String {
        val profile = Profile.WALLPAPER
        // With a release date or a watched badge, a card's wallpaper is drawn again each day: the date may have
        // passed, and the episodes watched changed
        val daily = profile.showReleaseDate ||
            (Settings.watchHistoryOn && profile.watchedMode == Settings.WATCHED_MARK && profile.showsInHeader(Settings.HEADER_WATCHED))
        return Integer.toHexString((profile.signature + if (daily) java.time.LocalDate.now() else "").hashCode())
    }

    /**
     * "1a2b3c-tv-1399.jpg" for a series or a movie, "1a2b3c-tv-1399-s1e3-sharp.jpg" for an episode (its image,
     * or the series' when it's low quality), "…-s1e3-series.jpg" for an episode on the series' image.
     */
    private fun fileOf(context: Context, prefix: String, match: Match, mode: String): File {
        val episode = match.episode?.takeIf { Settings.showsEpisodes(mode) }
            ?.let { "-s${it.season}e${it.number}" + if (mode == Settings.FOCUSED_EPISODE_SERIES_IMAGE) "-series" else "-sharp" }.orEmpty()
        return File(dir(context), "$prefix-${match.ref.type.path}-${match.ref.tmdbId}$episode.jpg")
    }

    private fun draw(context: Context, card: FocusedCard, mode: String, prefix: String) {
        val start = System.currentTimeMillis()
        Settings.init(context)
        val profile = Profile.WALLPAPER
        val factory = WallpaperFactory(context, minOf(profile.outputWidth, MAX_WIDTH), profile)
        val key = matchKey(card)
        val match = found[key] ?: find(context, factory.client, card)?.also { found[key] = it }
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
        // A low-quality still gives way to the series' backdrop, under the episode's details; or the series' always
        val still = card.still?.takeIf { episode != null && mode == Settings.FOCUSED_EPISODE && sharp(factory.client, it) }
        val lowQuality = when {
            episode == null || card.still == null || still != null -> ""
            mode == Settings.FOCUSED_EPISODE_SERIES_IMAGE -> " · series image"
            else -> " · low-quality still (${stillWidths[card.still] ?: "?"} px): series image"
        }
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
        var synopsis: ScrollingSynopsis? = null
        val bitmap = factory.render(drawn) { synopsis = it }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            // A synopsis that scrolls: what its video needs, made once the picture is out (see finished)
            synopsis?.save(file.parentFile!!, file.nameWithoutExtension, bitmap.width)?.let { companion(file, SCROLL).writeText(json.encodeToString(it)) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            bitmap.recycle()
            synopsis?.recycle()
        }
        prune(context)
        val seconds = (System.currentTimeMillis() - start) / 100 / 10.0
        Log.i(TAG, "${card.title}: ${file.name} drawn in $seconds s")
        FocusedLog.add(context, card, "${match.note} · ${if (episode != null) "episode" else match.ref.type.path} in $seconds s$lowQuality")
    }

    /** Older than [MAX_AGE_MILLIS] or beyond the [MAX_FILES] last used: they go, with their scrolling synopsis' files and video. */
    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        val files = dir(context).listFiles().orEmpty()
        files.filter { it.extension == "jpg" && !it.name.endsWith(PATCH) && !it.name.startsWith(FALLBACK_PREFIX) }
            .sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                if (index < MAX_FILES && now - file.lastModified() <= MAX_AGE_MILLIS) return@forEachIndexed
                file.delete()
                listOf(VIDEO, SCROLL, PATCH, STRIP).forEach { companion(file, it).delete() }
            }
    }

    // --- Fallback wallpaper for non-media cards (issue #38) ---

    private const val FALLBACK_PREFIX = "fallback-"
    private const val FALLBACK_WIDTH = 640
    private const val FALLBACK_HEIGHT = 360
    private const val BLUR_WIDTH = 96
    private const val BLUR_HEIGHT = 54
    private const val BLUR_RADIUS = 4
    private const val MAX_FALLBACK_FILES = 16
    private val fallbackLock = Any()

    /**
     * Neutral wallpaper shown on non-media cards (app tiles, YouTube clips, unmatched cards) when
     * [Settings.focusedFallback] isn't [Settings.FOCUSED_FALLBACK_SET]. Served from [DIR], so
     * [WallpaperContentProvider.lastShown] keeps pointing at the real set's wallpaper.
     */
    fun otherCardWallpaper(context: Context, fallback: String, cardColor: Int, lastShownPath: String?): Wallpaper? {
        val file = synchronized(fallbackLock) {
            when (fallback) {
                Settings.FOCUSED_FALLBACK_DARK -> darkFallbackFile(context, 0)
                Settings.FOCUSED_FALLBACK_CARD_COLOR -> darkFallbackFile(context, cardColor)
                Settings.FOCUSED_FALLBACK_DIMMED -> dimmedFallbackFile(context, lastShownPath) ?: darkFallbackFile(context, 0)
                else -> return null
            }
        } ?: return null
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.wallpapers")
            .appendPath(DIR).appendPath(file.name).build()
        return wallpaper(uri.toString(), "")
    }

    /** Dark charcoal gradient, optionally tinted softly with [cardColor] (0 = neutral). */
    private fun darkFallbackFile(context: Context, cardColor: Int): File? {
        val rgb = cardColor and 0x00FFFFFF
        val name = if (rgb == 0) "${FALLBACK_PREFIX}dark.jpg" else "${FALLBACK_PREFIX}color-%06x.jpg".format(rgb)
        val file = File(dir(context), name)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val bitmap = Bitmap.createBitmap(FALLBACK_WIDTH, FALLBACK_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val baseTop = Color.rgb(24, 28, 38)
            val baseBottom = Color.rgb(10, 12, 18)
            val top = if (rgb == 0) baseTop else blend(baseTop, cardColor, 0.28f)
            val bottom = if (rgb == 0) baseBottom else blend(baseBottom, cardColor, 0.10f)
            val linear = Paint(Paint.DITHER_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, FALLBACK_HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, FALLBACK_WIDTH.toFloat(), FALLBACK_HEIGHT.toFloat(), linear)
            if (rgb != 0) {
                val glow = Paint(Paint.DITHER_FLAG).apply {
                    shader = RadialGradient(
                        FALLBACK_WIDTH * 0.5f, FALLBACK_HEIGHT * 0.2f, FALLBACK_WIDTH * 0.75f,
                        Color.argb(64, Color.red(cardColor), Color.green(cardColor), Color.blue(cardColor)),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, FALLBACK_WIDTH.toFloat(), FALLBACK_HEIGHT.toFloat(), glow)
            }
            saveFallback(file, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Current (or first) set wallpaper heavily blurred and dimmed into a calm ambient backdrop. */
    private fun dimmedFallbackFile(context: Context, lastShownPath: String?): File? {
        val source = resolveSetImage(context, lastShownPath) ?: return null
        val key = Integer.toHexString("${source.path}:${source.lastModified()}".hashCode())
        val file = File(dir(context), "${FALLBACK_PREFIX}dimmed-$key.jpg")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val decoded = BitmapFactory.decodeFile(source.path, opts) ?: return null
        var tiny: Bitmap? = null
        var output: Bitmap? = null
        return try {
            val scaled = Bitmap.createScaledBitmap(decoded, BLUR_WIDTH, BLUR_HEIGHT, true)
            tiny = if (scaled === decoded || !scaled.isMutable) {
                scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (scaled !== decoded) scaled.recycle() } ?: return null
            } else scaled
            boxBlur(tiny, BLUR_RADIUS)
            boxBlur(tiny, BLUR_RADIUS)
            output = Bitmap.createBitmap(FALLBACK_WIDTH, FALLBACK_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawBitmap(tiny, null, Rect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(Color.argb(150, 8, 10, 14))
            saveFallback(file, output)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not render dimmed fallback from ${source.name}", e)
            null
        } finally {
            if (tiny !== decoded) tiny?.recycle()
            decoded.recycle()
            output?.recycle()
        }
    }

    /** The JPEG of [lastShownPath] (swapping a scrolling video for its companion JPEG), else the first wallpaper in the set. */
    private fun resolveSetImage(context: Context, lastShownPath: String?): File? {
        val root = File(context.filesDir, WallpaperStore.DIR)
        lastShownPath?.let { path ->
            val jpgPath = if (path.endsWith(".mp4")) path.removeSuffix(".mp4") + ".jpg" else path
            WallpaperContentProvider.resolve(root, jpgPath)?.takeIf { it.extension == "jpg" }?.let { return it }
        }
        val store = WallpaperStore(context, Profile.WALLPAPER)
        val gen = store.currentDir() ?: return null
        val first = store.current(gen).firstOrNull() ?: return null
        return File(gen, first.file).takeIf { it.isFile }
    }

    private fun saveFallback(file: File, bitmap: Bitmap): File? {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        return try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return null
            }
            pruneFallbacks(file.parentFile!!)
            file
        } catch (e: Exception) {
            Log.w(TAG, "Could not save ${file.name}", e)
            tmp.delete()
            null
        }
    }

    private fun pruneFallbacks(folder: File) {
        folder.listFiles().orEmpty()
            .filter { it.name.startsWith(FALLBACK_PREFIX) && it.name != "${FALLBACK_PREFIX}dark.jpg" && it.extension == "jpg" }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_FALLBACK_FILES)
            .forEach { it.delete() }
    }

    private fun blend(base: Int, tint: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(base) * inv + Color.red(tint) * ratio).toInt().coerceIn(0, 255),
            (Color.green(base) * inv + Color.green(tint) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(base) * inv + Color.blue(tint) * ratio).toInt().coerceIn(0, 255),
        )
    }

    /** Separable box blur in place on a tiny bitmap (fast, no RenderScript needed). */
    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        val tmp = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dx in -radius..radius) {
                    val c = src[row + (x + dx).coerceIn(0, w - 1)]
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                tmp[row + x] = Color.rgb(r / div, g / div, b / div)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0
                for (dy in -radius..radius) {
                    val c = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                src[y * w + x] = Color.rgb(r / div, g / div, b / div)
            }
        }
        bitmap.setPixels(src, 0, w, 0, 0, w, h)
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
     * How good a search result is for a card: each of the card's names it has ([namesMatched]) counts three,
     * and having a backdrop to draw counts one. A result of the other kind than the card's loses one: a backdrop
     * alone doesn't make it win, one more name does (kino.pub's movies, marked as series), even without a
     * backdrop. 0: not named like the card at all.
     */
    fun candidateScore(namesMatched: Int, hasBackdrop: Boolean, cardsKind: Boolean = true): Int =
        if (namesMatched <= 0) 0 else namesMatched * 3 + (if (hasBackdrop) 1 else 0) - (if (cardsKind) 0 else 1)

    /** The score that needs no further searching: every name of the card matched, with a backdrop. */
    fun perfectScore(bilingual: Boolean, cardsKind: Boolean = true): Int = candidateScore(if (bilingual) 2 else 1, true, cardsKind)

    /**
     * How many of the card's names ([titleVariants]) a result's names (translated, original) are: "Dune" named
     * so twice is one, "Терапия" originally "Shrinking" is two.
     */
    fun namesMatched(resultNames: List<String?>, variants: List<String>): Int {
        val targets = variants.map { normalized(it) }.filter { it.isNotEmpty() }.toSet()
        return resultNames.map { normalized(it.orEmpty()) }.filter { it in targets }.distinct().size
    }

    private class Candidate(val media: MediaType, val id: Int, val result: JsonObject, val query: String, val language: String, val score: Int)

    /**
     * The movie or series named exactly like [card] (see [findNamed]). The card's kind is tried first, then the
     * other one: some apps (kino.pub) mark movies as series. Without a match, the first results instead
     * ("Lucky Hank (2023), …"), for the log.
     */
    private fun findTitle(client: TmdbClient, card: FocusedCard): Pair<Match?, String> {
        val medias = listOf(card.media) + MediaType.entries.filter { it != card.media }
        val (match, closest) = findNamed(client, card, card.title, medias, "by name")
        if (match != null) return match to closest
        // Paramount+ names its series' cards after the series and the episode: the series alone. Not for movies,
        // whose subtitles follow a " - " too ("Mission: Impossible - Dead Reckoning" isn't the 1966 series)
        if (card.media != MediaType.TV) return null to closest
        val series = seriesNameOf(card.title) ?: return null to closest
        return findNamed(client, card, series, listOf(MediaType.TV), "by series name").first to closest
    }

    /** "Marshals" for "Marshals - Mehr Schein als Sein"; null for a title without " - ". */
    fun seriesNameOf(title: String): String? =
        seriesSeparator.find(title)?.let { title.substring(0, it.range.first).trim() }?.takeIf { it.isNotEmpty() }

    /**
     * The movie or series among [medias] named exactly like [title] (or originally named so), in the TV's
     * language and then the wallpapers'; between equals, the first TMDB gives (the most relevant). Its note says
     * how sure it was. A bilingual title ("Терапия / Shrinking", as kino.pub names them) is also searched by each
     * side; a result named like both sides, and one with a backdrop, goes first ([candidateScore]).
     */
    private fun findNamed(client: TmdbClient, card: FocusedCard, title: String, medias: List<MediaType>, how: String): Pair<Match?, String> {
        val variants = titleVariants(title)
        val bilingual = variants.size > 1
        val languages = listOf(Locale.getDefault().toLanguageTag(), Settings.language).distinct()
        var closest = ""
        var best: Candidate? = null
        search@ for (media in medias) {
            val name = if (media == MediaType.MOVIE) "title" else "name"
            val original = "original_$name"
            for (query in variants) {
                for (language in languages) {
                    val results = client.get("search/${media.path}?query=${URLEncoder.encode(query, "UTF-8")}&language=$language")
                        ?.objects("results").orEmpty()
                    if (closest.isEmpty()) closest = results.take(CLOSEST_LOGGED).joinToString(", ") { "${it.str(name).orEmpty()} (${yearOf(it)})" }
                    for (result in results) {
                        val id = result.int("id") ?: continue
                        val matched = namesMatched(listOf(result.str(name), result.str(original)), variants)
                        val score = candidateScore(matched, !result.str("backdrop_path").isNullOrEmpty(), media == card.media)
                        // Strictly better only: between equals, TMDB's order (relevance) and the card's own kind win
                        if (score > (best?.score ?: 0)) best = Candidate(media, id, result, query, language, score)
                    }
                    val top = best ?: continue
                    if (top.score >= perfectScore(bilingual, top.media == card.media)) break@search
                }
            }
        }
        val found = best ?: return null to closest
        val name = if (found.media == MediaType.MOVIE) "title" else "name"
        val by = if (found.query == title) how else "$how \"${found.query}\""
        val kind = if (found.media != card.media) " (card says ${card.media.path})" else ""
        val note = "${found.media.path}/${found.id} ${found.result.str(name).orEmpty()} (${yearOf(found.result)}) $by ${found.language}$kind · score ${found.score}"
        return Match(TitleRef(found.media, found.id), null, note) to closest
    }

    private fun yearOf(result: JsonObject) = (result.str("release_date") ?: result.str("first_air_date")).orEmpty().take(4).ifEmpty { "?" }
}
