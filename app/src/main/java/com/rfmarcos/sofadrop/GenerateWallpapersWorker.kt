package com.rfmarcos.sofadrop

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * Downloads TMDB data, renders the wallpapers of a profile (see [KEY_PROFILE]) and publishes them: to
 * Projectivy, or to the screensaver when it has a set of its own.
 */
class GenerateWallpapersWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val profile = Profile.of(params.inputData.getString(KEY_PROFILE))

    override fun doWork(): Result {
        Settings.init(applicationContext)
        if (!profile.isScreensaver) Updater.check()
        // Without Projectivy or the folder nothing shows the wallpapers' set while the screensaver has its own
        if (!profile.isScreensaver && Profile.SCREENSAVER.separate && !WallpaperFolder.shown(applicationContext)) {
            profile.generating = false
            runForScreensaver(applicationContext)
            return Result.success()
        }
        val signature = profile.signature
        val result = generateSet()
        // Settings changed while it ran (the guided setup, or leaving the settings, found it busy): again with those
        if (profile.signature != signature && !isStopped && Sources.selected(profile).isNotEmpty()) {
            Log.i(TAG, "Settings changed during the generation: generating again")
            runNow(applicationContext, profile, after = true)
            return Result.success()
        }
        if (!profile.isScreensaver) runForScreensaver(applicationContext)
        return result
    }

    private fun generateSet(): Result {
        // Nothing to build from: keep the current wallpapers and say why (runNow already set "generating")
        val missing = when {
            Settings.token.isBlank() -> R.string.error_no_token
            Sources.selected(profile).isEmpty() -> R.string.error_no_lists
            else -> null
        }
        val log = GenerationLog(applicationContext, profile)
        if (missing != null) {
            profile.lastError = applicationContext.getString(missing)
            profile.generating = false
            log.finish("Not started: ${profile.lastError}")
            return Result.failure()
        }

        val signature = profile.signature
        val store = WallpaperStore(applicationContext, profile)
        val generation = store.newGeneration()
        // With no set yet nothing can show meanwhile: each wallpaper goes out as it's made, so Sofascreen can start
        val first = store.current().isEmpty()
        val made = mutableListOf<StoredWallpaper>()
        val onMade = { wallpaper: StoredWallpaper ->
            if (first) synchronized(made) {
                made += wallpaper
                store.publish(generation, made, partial = true)
            }
        }
        profile.generating = true
        return try {
            val start = System.currentTimeMillis()
            val requests = TmdbClient.requestCounts()
            val drawn = generate(generation, log, onMade)
            // Replaced by a newer run, or its set dropped: what it got to make isn't the set, and the state is the other's
            if (cancelled) {
                if (synchronized(made) { made.isEmpty() }) store.discard(generation)
                log.finish("Cancelled")
                return Result.success()
            }
            val stored = withPlexSlugs(withJellyfinItems(withPlatformLinks(drawn, log), log), log)
            // Every list failing (e.g. no network) or no title fitting in memory is that error, not "no titles"
            if (stored.isEmpty()) throw when {
                listErrors.isNotEmpty() -> listErrors.first()
                outOfMemory -> OutOfMemoryError("No title fitted in memory")
                else -> TmdbException(applicationContext.getString(R.string.error_no_titles))
            }
            store.publish(generation, stored)
            profile.lastGeneration = System.currentTimeMillis()
            profile.lastSignature = signature
            profile.lastError = ""
            // A deleted or private Trakt list doesn't fail the generation, but the status says which one it was
            val unavailable = listErrors.filterIsInstance<ListUnavailableException>().map { it.name }
            profile.lastNotice = listOfNotNull(
                applicationContext.getString(R.string.notice_memory_1080).takeIf { memoryErrors.get() > 0 },
                applicationContext.getString(R.string.notice_list_unavailable, unavailable.joinToString(", ")).takeIf { unavailable.isNotEmpty() },
                applicationContext.getString(R.string.error_simkl_premium).takeIf { listErrors.any { it is SimklPremiumException } },
            ).joinToString(" · ")
            if (!profile.isScreensaver) {
                notifyProjectivy(applicationContext)
                WallpaperFolder.update(applicationContext)
                // The set is out with its JPEGs; the videos replace them as they're made
                withVideos(generation, stored, log)?.let { store.publish(generation, it) }
            }
            Log.i(TAG, "Generated ${stored.size} wallpapers${" for the screensaver".takeIf { profile.isScreensaver }.orEmpty()} (${Settings.language}) in ${System.currentTimeMillis() - start} ms")
            // What a generation costs TMDB (a preview open at the same time counts too)
            val (api, images, network) = TmdbClient.requestCounts().zip(requests) { now, before -> now - before }
            log.add("TMDB: $api API requests, $images images, $network not cached")
            log.finish("Generated ${stored.size} wallpapers")
            Result.success()
        } catch (e: Throwable) {
            // Errors too (out of memory): the status must always say why, not keep the last one
            Log.e(TAG, "Generation failed", e)
            // What a first set got to show stays, better than nothing; the next run replaces it
            if (synchronized(made) { made.isEmpty() }) store.discard(generation)
            // Stopped on purpose, its folder may be gone: nothing to tell
            if (!isStopped) profile.lastError = errorMessage(applicationContext, e)
            log.finish("Failed: ${GenerationLog.describe(e)}")
            if (e is Exception && e !is TmdbException && e !is ListUnavailableException && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            if (!cancelled) {
                profile.generating = false
                profile.generationProgress = null
            }
        }
    }

    /** Stopped by the app and not by Android's time limit, whose partial set is worth keeping. */
    @get:SuppressLint("NewApi") // WorkManager sets it on every version
    private val cancelled get() = isStopped && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    /** Lists that couldn't be read in this run. */
    private val listErrors = mutableListOf<Exception>()

    /** Width the wallpapers are drawn at: the chosen one, or 1920 once 4K ran out of memory twice. */
    @Volatile
    private var width = 0
    private val memoryErrors = AtomicInteger()
    /** Wallpapers started, for the position of the next one (see [Profile.positionAt]). */
    private val started = AtomicInteger()

    /** Some title didn't fit in memory even at 1920. */
    @Volatile
    private var outOfMemory = false

    private fun generate(generation: File, log: GenerationLog, onMade: (StoredWallpaper) -> Unit): List<StoredWallpaper> {
        val factory = WallpaperFactory(applicationContext, profile.outputWidth, profile)
        width = factory.width
        val perList = profile.titlesPerList
        // Finished 4K wallpapers are ~33 MB each until compressed: one title at a time there
        val parallelTitles = if (factory.width > FALLBACK_WIDTH) 1 else perList
        // Titles that became a wallpaper; later lists skip them (unless duplicates are allowed)
        val taken = mutableSetOf<TitleRef>()
        val stored = mutableListOf<StoredWallpaper>()
        val sources = Sources.selected(profile)

        /** Draw each title with its list's plan, in parallel; how many became a wallpaper. */
        fun draw(titles: List<Pair<ListPlan, TitleRef>>, list: Int): Int {
            var made = 0
            for ((ref, wallpaper) in WallpaperFactory.parallel(titles) { (plan, ref) -> ref to store(factory, plan, ref, generation, log)?.also(onMade) }) {
                if (wallpaper == null) continue
                // Only a title that really became a wallpaper is taken: a failed one stays free for other lists
                taken += ref
                stored += wallpaper
                made++
                profile.generationProgress = Triple(stored.size, list, sources.size)
            }
            return made
        }

        for ((index, source) in sources.withIndex()) {
            profile.generationProgress = Triple(stored.size, index + 1, sources.size)
            // Within Settings.MAX_WALLPAPERS: what's left shared among the lists to go (a short one leaves more)
            val left = Settings.MAX_WALLPAPERS - stored.size
            val quota = minOf(perList, (left + sources.size - index - 1) / (sources.size - index))
            if (quota <= 0) break
            val plan = try {
                factory.plan(source, perList, taken)
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                // One failing service (e.g. Trakt down) must not stop the others
                Log.w(TAG, "Could not read list ${source.key}", e)
                log.listFailed(source.key, e)
                listErrors += e
                continue
            }
            var added = 0
            var next = 0
            // In parallel, as many titles as still missing: the same ones a title-by-title loop would pick
            while (added < quota && next < plan.usable.size) {
                if (isStopped) {
                    // Android stops workers after 10 minutes: worth knowing when 4K on a slow TV fails
                    log.add("Stopped by Android after ${stored.size} wallpapers")
                    return stored
                }
                val batch = mutableListOf<Pair<ListPlan, TitleRef>>()
                while (added + batch.size < quota && batch.size < parallelTitles && next < plan.usable.size) {
                    val ref = plan.usable[next++]
                    // Without skipping duplicates, a title already drawn for another list still counts for this one
                    if (ref in taken) added++ else batch += plan to ref
                }
                added += draw(batch, index + 1)
            }
            Log.i(TAG, if (added < quota) "${source.key}: $added of $quota wallpapers (${plan.usable.size} usable titles)"
                else "${source.key}: $added wallpapers")
            log.list(source.key, plan.candidates.size, plan.candidates.count { it.exclusion != null }, added)
        }
        // A list that comes up short isn't made up for by the others: "titles per list" is each list's most
        return stored
    }

    /**
     * [stored] with their pages on the streaming platforms that have them, when the wallpaper opens its
     * platform: JustWatch's, then Wikidata's for the platforms it has none of. Without either they open the
     * fallback instead: not worth failing the generation for.
     */
    private fun withPlatformLinks(stored: List<StoredWallpaper>, log: GenerationLog): List<StoredWallpaper> {
        if (!profile.opensPlatforms) return stored
        val refs = stored.filter { wallpaper -> wallpaper.providerIds.any { Platform.ofProvider(it) != null } }
            .associateWith { wallpaper -> TitleRef(MediaType.entries.first { it.path == wallpaper.type }, wallpaper.tmdbId) }
        if (refs.isEmpty()) return stored
        val justWatch = try {
            JustWatch.links(refs.entries.associate { (wallpaper, ref) -> ref to wallpaper.title }, Settings.region)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the JustWatch links", e)
            log.add("Platform pages failed: ${GenerationLog.describe(e)}")
            emptyMap()
        }
        val ids = try {
            Wikidata.ids(refs.values)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the platform ids", e)
            log.add("Platform links failed: ${GenerationLog.describe(e)}")
            emptyMap()
        }
        val linked = stored.map { wallpaper ->
            val ref = refs[wallpaper] ?: return@map wallpaper
            val wikidata = Platform.links(ref.type, ids[ref].orEmpty())
            val pages = justWatch[ref].orEmpty()
            wallpaper.copy(platformLinks = Platform.merged(wikidata, pages), platformOthers = Platform.others(wikidata, pages))
        }
        log.add("Platform links: ${linked.count { it.platformLinks.isNotEmpty() }} of ${refs.size} titles on a known platform" +
            " (sources: ${justWatch.size}, ${ids.count { it.value.isNotEmpty() }})")
        return linked
    }

    /**
     * [stored] with their items on the user's Jellyfin server, when wallpapers open Jellyfin: its page then,
     * instead of its search. Without the server they open the search: not worth failing the generation for.
     */
    private fun withJellyfinItems(stored: List<StoredWallpaper>, log: GenerationLog): List<StoredWallpaper> {
        if (Settings.clickApp(profile.clickFallback) != TitleApp.JELLYFIN) return stored
        val client = JellyfinClient.fromSettings() ?: return stored
        val index = try {
            client.libraryIndex()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the Jellyfin library", e)
            log.add("Jellyfin library failed: ${GenerationLog.describe(e)}")
            return stored
        }
        val linked = stored.map { wallpaper ->
            val type = MediaType.entries.first { it.path == wallpaper.type }
            wallpaper.copy(jellyfinId = index[TitleRef(type, wallpaper.tmdbId)])
        }
        log.add("Jellyfin: ${linked.count { it.jellyfinId != null }} of ${stored.size} titles in the library (${index.size} there)")
        return linked
    }

    /**
     * [stored] with their pages on watch.plex.tv, when wallpapers open Plex: the server's titles bring
     * theirs, the rest are asked to Plex once and remembered. Without one, the fallback after the app opens.
     */
    private fun withPlexSlugs(stored: List<StoredWallpaper>, log: GenerationLog): List<StoredWallpaper> {
        if (Settings.clickApp(profile.clickFallback) != TitleApp.PLEX || !Settings.plexSignedIn) return stored
        val slugs = PlexSlugs(applicationContext)
        try {
            PlexClient.connect()?.librarySlugs()?.forEach { (ref, slug) -> slugs[ref] = slug }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the Plex library", e)
            log.add("Plex library failed: ${GenerationLog.describe(e)}")
        }
        val refs = stored.associateWith { wallpaper -> TitleRef(MediaType.entries.first { it.path == wallpaper.type }, wallpaper.tmdbId) }
        val failed = slugs.fetch(refs.values.filter { slugs[it] == null }) { ref -> PlexClient.slug(Settings.plexToken, ref) }
        slugs.save()
        val linked = stored.map { wallpaper -> wallpaper.copy(plexSlug = slugs[refs.getValue(wallpaper)]) }
        log.add("Plex: ${linked.count { it.plexSlug != null }} of ${stored.size} titles with a page" + if (failed > 0) " ($failed failed)" else "")
        return linked
    }

    /**
     * [stored] with an MP4 for each wallpaper whose synopsis scrolls (see [ScrollingSynopsis]), for
     * Projectivy; null when none has one. One at a time: TV encoders take few sessions. Stopped by
     * Android, the ones left keep their JPEG.
     */
    private fun withVideos(generation: File, stored: List<StoredWallpaper>, log: GenerationLog): List<StoredWallpaper>? {
        if (stored.none { it.scroll != null } || !Projectivy.isInstalled(applicationContext)) return null
        val start = System.currentTimeMillis()
        var made = 0
        var bytes = 0L
        var codec = ""
        val result = stored.map { wallpaper ->
            val box = wallpaper.scroll
            if (box == null || isStopped) return@map wallpaper
            val video = wallpaper.file.removeSuffix(".jpg") + ".mp4"
            val file = File(generation, video)
            val image = runCatching { BitmapFactory.decodeFile(File(generation, wallpaper.file).path) }.getOrNull()
            val synopsis = ScrollingSynopsis.load(generation, box)
            try {
                if (image == null || synopsis == null) return@map wallpaper
                codec = ScrollVideo.encode(synopsis, image, file)
                made++
                bytes += file.length()
                wallpaper.copy(video = video)
            } catch (e: Throwable) {
                // Out of memory too: the wallpaper stays a still one
                Log.w(TAG, "Could not encode the video of ${wallpaper.file}", e)
                log.add("Video of ${wallpaper.file} failed: ${GenerationLog.describe(e)}")
                file.delete()
                wallpaper
            } finally {
                image?.recycle()
                synopsis?.recycle()
            }
        }
        val millis = System.currentTimeMillis() - start
        log.add("Scrolling synopses: $made videos in ${millis / 1000} s (${if (made > 0) millis / made else 0} ms each), ${bytes / 1024} KB, $codec" +
            if (isStopped) " (stopped by Android)" else "")
        return result.takeIf { made > 0 }
    }

    /** Render one title into [generation]; null when it can't be drawn. */
    private fun store(factory: WallpaperFactory, plan: ListPlan, ref: TitleRef, generation: File, log: GenerationLog): StoredWallpaper? =
        try {
            factory.build(ref, plan)?.let { info ->
                val position = profile.positionAt(started.getAndIncrement())
                var synopsis: ScrollingSynopsis? = null
                val bitmap = render(factory, info, ref, log, position) { synopsis = it } ?: return null
                val base = "${info.type.path}-${info.id}"
                val name = "$base.jpg"
                val scroll = try {
                    FileOutputStream(File(generation, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    synopsis?.save(generation, base, bitmap.width)
                } finally {
                    bitmap.recycle()
                    synopsis?.recycle()
                }
                StoredWallpaper(name, info.title, info.type.path, info.id, plan.source.key, info.trailerKey, info.imdbId, info.providerIds,
                    position = position.takeIf { profile.position == Settings.POSITION_ROTATE }, scroll = scroll)
            }
        } catch (e: TmdbException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not render ${ref.type.path}/${ref.tmdbId}", e)
            log.titleFailed(ref, e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory saving ${ref.type.path}/${ref.tmdbId}", e)
            log.titleFailed(ref, e)
            null
        }

    /**
     * [info] drawn at [width]. A 4K wallpaper that doesn't fit in memory is drawn again at 1920, and after
     * the second one the rest of the generation goes straight to 1920. Null when not even that fits.
     */
    private fun render(
        factory: WallpaperFactory, info: WallpaperInfo, ref: TitleRef, log: GenerationLog, position: String, onScroll: (ScrollingSynopsis) -> Unit,
    ): Bitmap? {
        val width = this.width
        try {
            return factory.render(info, width, position, onScroll)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory drawing ${ref.type.path}/${ref.tmdbId} at $width px", e)
            log.titleFailed(ref, e, width)
            if (width <= FALLBACK_WIDTH) {
                outOfMemory = true
                return null
            }
            if (memoryErrors.incrementAndGet() >= 2 && this.width > FALLBACK_WIDTH) {
                this.width = FALLBACK_WIDTH
                log.add("Out of memory twice at $width px: the rest at $FALLBACK_WIDTH px")
            }
        }
        return try {
            factory.render(info, FALLBACK_WIDTH, position, onScroll)
        } catch (e: OutOfMemoryError) {
            log.titleFailed(ref, e, FALLBACK_WIDTH)
            outOfMemory = true
            null
        }
    }

    companion object {
        private const val TAG = "GenerateWallpapers"
        private const val JPEG_QUALITY = 90
        /** What 4K falls back to when the TV runs out of memory. */
        private const val FALLBACK_WIDTH = 1920
        private const val UNIQUE_NOW = "generate-now"
        /** Id of the [Profile] to generate; the wallpapers' when missing (as in work scheduled by earlier versions). */
        private const val KEY_PROFILE = "profile"
        private const val UNIQUE_PERIODIC = "generate-periodic"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Generate [profile]'s set as soon as possible (the wallpapers' when the screensaver shows those).
         * [replace] restarts a generation already running; [after] queues this one behind it (from the
         * running worker itself, which KEEP would ignore).
         */
        fun runNow(context: Context, profile: Profile = Profile.WALLPAPER, replace: Boolean = false, after: Boolean = false) {
            Settings.init(context)
            val set = profile.source
            set.generating = true
            val request = OneTimeWorkRequestBuilder<GenerateWallpapersWorker>()
                .setConstraints(networkConstraint)
                .setInputData(workDataOf(KEY_PROFILE to set.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (set.isScreensaver) "$UNIQUE_NOW-${set.id}" else UNIQUE_NOW,
                when {
                    replace -> ExistingWorkPolicy.REPLACE
                    after -> ExistingWorkPolicy.APPEND_OR_REPLACE
                    else -> ExistingWorkPolicy.KEEP
                },
                request,
            )
        }

        /** Update everything now: the wallpapers' set and, with two, the screensaver's. */
        fun runAll(context: Context) {
            runNow(context, Profile.WALLPAPER, replace = true)
            if (Profile.twoSets) runNow(context, Profile.SCREENSAVER, replace = true)
        }

        /** Stop the screensaver's own generation, running or waiting (the wallpapers' isn't stopped from the app). */
        fun cancel(context: Context, profile: Profile) {
            if (!profile.isScreensaver) return
            WorkManager.getInstance(context).cancelUniqueWork("$UNIQUE_NOW-${profile.id}")
            profile.generating = false
            profile.generationProgress = null
        }

        fun isOnline(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        /** Once both places use one set again the screensaver's goes (disk); it's made from scratch if it comes back. */
        fun dropScreensaverSet(context: Context) {
            if (Profile.twoSets) return
            val screensaver = Profile.SCREENSAVER
            // A generation of its own still running would write into the folder deleted here
            cancel(context, screensaver)
            WallpaperStore.deleteScreensaverSet(context)
            screensaver.lastGeneration = 0L
            screensaver.lastSignature = ""
            screensaver.lastError = ""
        }

        /**
         * The screensaver's own set, after the wallpapers' (the periodic refresh only runs those): when it
         * has none yet, its settings changed or it's as old as the refresh interval.
         */
        private fun runForScreensaver(context: Context) {
            val screensaver = Profile.SCREENSAVER
            if (!screensaver.separate || screensaver.generating || Sources.selected(screensaver).isEmpty()) return
            val age = System.currentTimeMillis() - screensaver.lastGeneration
            if (screensaver.lastGeneration == 0L || screensaver.hasPendingChanges || age > Settings.refreshIntervalMillis * 9 / 10) {
                runNow(context, screensaver)
            }
        }

        /**
         * Sofascreen is starting: without Projectivy, which asks for its set and so keeps it fresh, nothing
         * else may have scheduled the refresh, or noticed that the set it shows is empty or expired.
         */
        fun refreshForScreensaver(context: Context) {
            Settings.init(context)
            schedule(context)
            val set = Profile.SCREENSAVER.source
            val expired = System.currentTimeMillis() - set.lastGeneration > Settings.refreshIntervalMillis
            // By the files and not the date: a restored backup has the date but not the images
            if (expired || WallpaperStore(context, set).current().isEmpty()) runNow(context, set)
        }

        /** Keep the periodic refresh in sync with the "update every" setting. */
        fun schedule(context: Context, settingsChanged: Boolean = false) {
            Settings.init(context)
            val hours = Settings.refreshHours.toLong()
            val request = PeriodicWorkRequestBuilder<GenerateWallpapersWorker>(hours, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .addTag("every-$hours-h")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                if (settingsChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** What the status line says about a failed generation: the cause in the user's words when it's known. */
        fun errorMessage(context: Context, error: Throwable): String = when {
            error is OutOfMemoryError -> context.getString(R.string.error_memory)
            error is TmdbException && error.keyRejected -> context.getString(R.string.error_tmdb_key)
            error is TmdbException -> error.message.orEmpty()
            error is ListUnavailableException -> context.getString(R.string.error_list_unavailable, error.name)
            error is SimklPremiumException -> context.getString(R.string.error_simkl_premium)
            error is UnknownHostException || error is ConnectException || error is SocketTimeoutException || error is SSLException ->
                context.getString(R.string.error_network)
            else -> GenerationLog.describe(error)
        }

        /** Ask Projectivy to request the wallpaper list again. */
        fun notifyProjectivy(context: Context) {
            val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
                `package` = PROJECTIVY_PACKAGE
                putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, context.getString(R.string.plugin_uuid))
                putExtra(
                    WallpaperProviderContract.EXTRA_UPDATE_REASON,
                    WallpaperProviderContract.UpdateReason.DATA_CHANGED,
                )
            }
            context.sendBroadcast(intent)
        }
    }
}
