package com.rfmarcos.sofadrop

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredWallpaper(
    val file: String,
    val title: String,
    val type: String,
    val tmdbId: Int,
    /** Key of the list it came from (empty in sets made before it was stored). */
    val source: String = "",
    /** YouTube trailer opened from Projectivy (null: none, or a set made before it was stored). */
    val trailerKey: String? = null,
    /** "tt0133093", for the apps that open a title by its IMDb id (see [TitleApp]). */
    val imdbId: String? = null,
    /** TMDB ids of its streaming services in the streaming country (see [Platform.pick]). */
    val providerIds: List<Int> = emptyList(),
    /** Its pages on the streaming platforms, by [Platform.key] (only when the user has platforms). */
    val platformLinks: Map<String, String> = emptyMap(),
    /** Another page on some of those platforms, tried when the app doesn't take the first (see [Platform.others]). */
    val platformOthers: Map<String, String> = emptyMap(),
    /** Where its text is, when each wallpaper has its own place (see [Settings.POSITION_ROTATE]). */
    val position: String? = null,
    /** Its item on the user's Jellyfin server, when it is there and Jellyfin opens it (see [TitleApp.JELLYFIN]). */
    val jellyfinId: String? = null,
    /** Its page on watch.plex.tv ("the-matrix-1999"), when Plex opens it (see [TitleApp.PLEX]). */
    val plexSlug: String? = null,
    /** Its synopsis didn't fit and scrolls (see [Settings.OVERVIEW_SCROLL]): Sofascreen scrolls it live. */
    val scroll: ScrollBox? = null,
    /** The MP4 of [file] with its synopsis scrolling, which Projectivy gets instead of the JPEG. */
    val video: String? = null,
)

/**
 * Generated wallpapers live in files/wallpapers/<generation>/ with an index.json (the screensaver's own
 * set in files/screensaver/). A new generation is written to its own folder and swapped in atomically.
 */
class WallpaperStore(private val context: Context, profile: Profile = Profile.WALLPAPER) {

    /** Whose set it is: the screensaver shows the wallpapers' unless it has its own. */
    private val set = profile.source
    private val root = File(context.filesDir, if (set.isScreensaver) SCREENSAVER_DIR else DIR)
    private val currentFile = File(root, "current.txt")
    private val servedFile = File(root, "served.txt")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun newGeneration(): File =
        File(root, "gen-${System.currentTimeMillis()}").apply { mkdirs() }

    /**
     * Make [generation] the current set. The set Projectivy was last given is kept: it caches those
     * URIs (for up to [R.integer.items_cache_duration_millis], or until it restarts) and falls back to
     * its own wallpaper when they fail to load. Before anything was served, the previous set is kept.
     * The screensaver reads its files directly, so its previous set goes.
     */
    fun publish(generation: File, wallpapers: List<StoredWallpaper>, partial: Boolean = false) = synchronized(lock) {
        // Atomically: a first set is published again and again while Sofascreen reads it
        val index = File(generation, "$INDEX.tmp")
        index.writeText(json.encodeToString(wallpapers))
        index.renameTo(File(generation, INDEX))
        val keep = if (set.isScreensaver) null else servedFile.takeIf { it.exists() }?.readText()?.trim() ?: currentDir()?.name
        val tmp = File(root, "current.tmp")
        tmp.writeText(generation.name)
        tmp.renameTo(currentFile)
        if (partial) return@synchronized
        // Only older sets: a newer one may be another generation still being written (two can overlap).
        // The focused cards' wallpapers stay: they're named after the settings they were drawn with
        root.listFiles()?.filter { it.isDirectory && it.name != keep && it.name != FocusedWallpapers.DIR && startedAt(it) < startedAt(generation) }?.forEach { it.deleteRecursively() }
    }

    /** When the generation in [dir] ("gen-1761560000000") started; 0 for anything else, which goes. */
    private fun startedAt(dir: File): Long = dir.name.removePrefix("gen-").toLongOrNull() ?: 0L

    /** True once Projectivy has been given a set (since 1.0.1). */
    val everServed: Boolean get() = servedFile.exists()

    /** Remember that Projectivy now has the current set's URIs (see [publish]). */
    fun markServed(dir: File? = currentDir()) {
        // Asked every few seconds: written only when it changes, and whole (publish reads it to keep that set)
        val name = dir?.name ?: return
        synchronized(lock) {
            if (servedFile.takeIf { it.exists() }?.readText()?.trim() == name) return
            val tmp = File(root, "served.tmp")
            tmp.writeText(name)
            tmp.renameTo(servedFile)
        }
    }

    fun discard(generation: File) {
        generation.deleteRecursively()
    }

    /** The current set's folder: resolved once for [current] and its URIs, a new set may be published meanwhile. */
    fun currentDir(): File? =
        currentFile.takeIf { it.exists() }?.readText()?.trim()?.let { File(root, it) }?.takeIf { it.isDirectory }

    fun current(dir: File? = currentDir()): List<StoredWallpaper> {
        dir ?: return emptyList()
        val index = File(dir, INDEX).takeIf { it.exists() } ?: return emptyList()
        return try {
            json.decodeFromString<List<StoredWallpaper>>(index.readText()).filter { File(dir, it.file).exists() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fileOf(wallpaper: StoredWallpaper): File? = currentDir()?.let { File(it, wallpaper.file) }

    /** content:// URI of a current wallpaper ([name] is one of its files), served by [WallpaperContentProvider]. */
    fun uriOf(wallpaper: StoredWallpaper, name: String = wallpaper.file, dir: File? = currentDir()): Uri? {
        dir ?: return null
        return Uri.Builder().scheme("content").authority("${context.packageName}.wallpapers")
            .appendPath(dir.name).appendPath(name).build()
    }

    /** The URI of [wallpaper]'s video, when it has one. */
    fun videoUriOf(wallpaper: StoredWallpaper, dir: File? = currentDir()): Uri? {
        val video = wallpaper.video ?: return null
        return dir?.takeIf { File(it, video).isFile }?.let { uriOf(wallpaper, video, it) }
    }

    companion object {
        private val lock = Any()
        private const val INDEX = "index.json"
        const val DIR = "wallpapers"
        private const val SCREENSAVER_DIR = "screensaver"

        /** Remove the screensaver's own set, once it shows the wallpapers' again. */
        fun deleteScreensaverSet(context: Context) {
            File(context.filesDir, SCREENSAVER_DIR).deleteRecursively()
        }
    }
}
