package com.rfmarcos.sofadrop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of what Projectivy does: generate, bind the plugin service through its
 * public intent action, request wallpapers and decode every returned URI.
 * Needs network access and a TMDB key (tmdb.token in local.properties).
 */
@RunWith(AndroidJUnit4::class)
class PluginServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatesAndServesSpanishWallpapers() {
        Settings.init(context)
        Settings.language = "es-ES"
        Settings.regionOverride = ""
        Profile.WALLPAPER.lists = Settings.DEFAULT_LISTS
        Profile.WALLPAPER.titlesPerList = 3
        Profile.WALLPAPER.outputWidth = 1920

        val before = Profile.WALLPAPER.lastGeneration
        GenerateWallpapersWorker.runNow(context, replace = true)
        val deadline = System.currentTimeMillis() + 180_000
        while (Profile.WALLPAPER.lastGeneration == before && Profile.WALLPAPER.lastError.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
        }
        assertEquals("", Profile.WALLPAPER.lastError)
        assertTrue("generation did not finish", Profile.WALLPAPER.lastGeneration > before)

        val service = bind()
        val wallpapers = service.getWallpapers(Event.TimeElapsed)
        assertEquals(6, wallpapers.size)
        for (wallpaper in wallpapers) {
            Log.i(TAG, "${wallpaper.title} -> ${wallpaper.uri} (${wallpaper.source}) action=${wallpaper.actionUri}")
            val bitmap = context.contentResolver.openInputStream(Uri.parse(wallpaper.uri)).use { BitmapFactory.decodeStream(it) }
            assertNotNull("cannot decode ${wallpaper.uri}", bitmap)
            assertEquals(1920, bitmap.width)
            assertEquals(1080, bitmap.height)
        }

        // Trailers: "Watch" in Projectivy opens YouTube; a title without one has no action
        assertEquals(Settings.CLICK_TRAILER, Profile.GENERAL.clickFallback)
        val trailers = wallpapers.mapNotNull { it.actionUri }
        assertTrue("no wallpaper has a trailer", trailers.isNotEmpty())
        for (actionUri in trailers) {
            val intent = Intent.parseUri(actionUri, Intent.URI_INTENT_SCHEME)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(actionUri, intent.dataString.orEmpty().startsWith("https://www.youtube.com/watch?v="))
        }
        Profile.GENERAL.clickFallback = Settings.CLICK_NONE
        try {
            assertTrue(service.getWallpapers(Event.TimeElapsed).all { it.actionUri == null })
        } finally {
            Profile.GENERAL.clickFallback = Settings.CLICK_TRAILER
        }

        // Projectivy backup round-trip
        val exported = service.preferences
        assertTrue(exported.contains("\"language\":\"es-ES\""))
        service.setPreferences(exported.replace("\"titles_per_list\":3", "\"titles_per_list\":5"))
        assertEquals(5, Profile.WALLPAPER.titlesPerList)
        assertEquals(Settings.DEFAULT_LISTS, Profile.WALLPAPER.lists)
        Profile.WALLPAPER.titlesPerList = 3
    }

    /**
     * The trailer stored with a wallpaper reaches Projectivy as actionUri, from an index.json written by hand
     * (no TMDB key or network): one entry with a trailer, one in the format of sets made before it.
     */
    @Test
    fun servesTrailersAsActionUri() {
        Settings.init(context)
        val store = WallpaperStore(context)
        val generation = store.newGeneration()
        for (name in listOf("movie-1.jpg", "movie-2.jpg")) {
            File(generation, name).outputStream().use { Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        store.publish(generation, emptyList())
        File(generation, "index.json").writeText(
            """[{"file":"movie-1.jpg","title":"With trailer","type":"movie","tmdbId":1,"source":"tmdb:trending_movies","trailerKey":"dQw4w9WgXcQ"},""" +
                """{"file":"movie-2.jpg","title":"Older set","type":"movie","tmdbId":2}]"""
        )
        val lastGeneration = Profile.WALLPAPER.lastGeneration
        Profile.WALLPAPER.lastGeneration = System.currentTimeMillis() // not stale: no generation from getWallpapers
        try {
            val service = bind()
            val wallpapers = service.getWallpapers(Event.TimeElapsed).associateBy { it.title }
            Log.i(TAG, "actionUri: ${wallpapers.mapValues { it.value.actionUri }}")
            assertEquals(null, wallpapers.getValue("Older set").actionUri)
            val intent = Intent.parseUri(wallpapers.getValue("With trailer").actionUri, Intent.URI_INTENT_SCHEME)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", intent.dataString)

            Profile.GENERAL.clickFallback = Settings.CLICK_NONE
            assertTrue(service.getWallpapers(Event.TimeElapsed).all { it.actionUri == null })
        } finally {
            Profile.GENERAL.clickFallback = Settings.CLICK_TRAILER
            Profile.WALLPAPER.lastGeneration = lastGeneration
        }
    }

    /** Any installed app can call getPreferences / setPreferences: keys and connections never go through them. */
    @Test
    fun backupLeavesOutKeysAndConnections() {
        Settings.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val secrets = listOf("tmdb_token", "trakt_client_id", "trakt_client_secret", "mdblist_api_key",
            "_trakt_access_token", "_simkl_access_token", "_mdblist_access_token", "_tmdb_session_id")
            .associateWith { "test-secret-$it" }
        val saved = secrets.keys.associateWith { prefs.getString(it, null) }
        prefs.edit(commit = true) { secrets.forEach { (key, value) -> putString(key, value) } }
        Profile.WALLPAPER.titlesPerList = Profile.WALLPAPER.titlesPerList
        try {
            val service = bind()
            val exported = service.preferences
            Log.i(TAG, "Backup: $exported")
            secrets.keys.forEach { assertFalse("$it exported", exported.contains("\"$it\"")) }
            assertFalse(exported.contains("test-secret"))

            service.setPreferences(exported.removeSuffix("}") + secrets.keys.joinToString("") { ",\"$it\":\"injected\"" } + "}")
            secrets.forEach { (key, value) -> assertEquals(key, value, prefs.getString(key, null)) }
        } finally {
            prefs.edit(commit = true) { saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }
        }
    }

    /**
     * Paramount+'s "Continue watching" cards are named "Series - Episode", with its own image (2026-11): the
     * series is found by the name before " - ". A movie card isn't searched so: its subtitles follow a " - " too.
     */
    @Test
    fun paramountCardsFindTheirSeries() {
        Settings.init(context)
        val mode = Settings.focusedCard
        Settings.focusedCard = Settings.FOCUSED_EPISODE
        FocusedLog.clear(context)
        try {
            val service = bindAsProjectivy()
            val skyMed = Event.ProgramCardFocused(title = "SkyMed - Frau im Cockpit", type = 3, iconUri = "https://thumbnails.cbsig.net/example.jpg")
            val wallpapers = answer(service, skyMed)
            Log.i(TAG, "SkyMed: ${wallpapers.map { it.uri }} · ${FocusedLog.read(context)}")
            assertEquals(1, wallpapers.size)
            val bitmap = context.contentResolver.openInputStream(Uri.parse(wallpapers[0].uri)).use { BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            assertTrue(FocusedLog.read(context).any { "tv/133908 SkyMed" in it && "by series name" in it })

            val movie = Event.ProgramCardFocused(title = "Mission: Impossible - Kein solcher Film", type = 0, iconUri = "https://thumbnails.cbsig.net/example.jpg")
            answer(service, movie)
            Log.i(TAG, "Movie: ${FocusedLog.read(context)}")
            assertTrue(FocusedLog.read(context).any { "Kein solcher Film" in it && "nothing named so" in it })
            assertFalse(FocusedLog.read(context).any { "Kein solcher Film" in it && "by series name" in it })
        } finally {
            Settings.focusedCard = mode
        }
    }

    /**
     * kino.pub's cards (PR #31, 2026-11) name titles in Russian and originally ("Терапия / Shrinking") and say
     * every one is a series: the series is found by either name, and a movie as a movie.
     */
    @Test
    fun kinoPubCardsFindBilingualTitlesAndMovies() {
        Settings.init(context)
        val mode = Settings.focusedCard
        Settings.focusedCard = Settings.FOCUSED_EPISODE
        FocusedLog.clear(context)
        try {
            val service = bindAsProjectivy()
            val series = Event.ProgramCardFocused(title = "Терапия / Shrinking", type = 1, iconUri = "https://m.staticpop.net/example.jpg")
            assertEquals(1, answer(service, series).size)
            val movie = Event.ProgramCardFocused(title = "Хороший адвокат / Un bon avocat", type = 1, iconUri = "https://m.staticpop.net/example.jpg")
            assertEquals(1, answer(service, movie).size)
            val log = FocusedLog.read(context)
            Log.i(TAG, "kino.pub: $log")
            assertTrue(log.any { "Shrinking" in it && "→ tv/" in it && "card says" !in it })
            assertTrue(log.any { "Un bon avocat" in it && "movie/1616419" in it && "(card says tv)" in it })
        } finally {
            Settings.focusedCard = mode
        }
    }

    /**
     * "Episode": Doctor Who's first episode (1963) has a 480 px still on TMDB, which gives way to the series'
     * backdrop; Breaking Bad's first has a 1920 px one, which stays (2026-11). "Episode, series image" takes
     * the series' backdrop for Breaking Bad too.
     */
    @Test
    fun lowQualityStillsGiveWayToTheSeries() {
        Settings.init(context)
        val mode = Settings.focusedCard
        Settings.focusedCard = Settings.FOCUSED_EPISODE
        FocusedLog.clear(context)
        // Drawn by an earlier run they'd only be "cached" in the log
        java.io.File(context.filesDir, "${WallpaperStore.DIR}/${FocusedWallpapers.DIR}").listFiles()
            ?.filter { "-tv-121-" in it.name || "-tv-1396-" in it.name }?.forEach { it.delete() }
        try {
            val service = bindAsProjectivy()
            val doctorWho = Event.ProgramCardFocused(title = "Doctor Who", type = 3, iconUri = "https://image.tmdb.org/t/p/w500/geJKM80SjYZIG5XNwonRHIkKBrF.jpg")
            assertEquals(1, answer(service, doctorWho).size)
            val breakingBad = Event.ProgramCardFocused(title = "Breaking Bad", type = 3, iconUri = "https://image.tmdb.org/t/p/w500/ydlY3iPfeOAvu8gVqrxPoMvzNCn.jpg")
            assertEquals(1, answer(service, breakingBad).size)
            val log = FocusedLog.read(context)
            Log.i(TAG, "Sharp: $log")
            assertTrue(log.any { "tv/121 " in it && "S1E1 by still" in it && "low-quality still (480 px)" in it })
            assertTrue(log.any { "tv/1396 " in it && "S1E1 by still" in it && "episode in" in it && "image" !in it.substringAfter("episode in") })

            Settings.focusedCard = Settings.FOCUSED_EPISODE_SERIES_IMAGE
            FocusedLog.clear(context)
            assertEquals(1, answer(service, breakingBad).size)
            Log.i(TAG, "Series image: ${FocusedLog.read(context)}")
            assertTrue(FocusedLog.read(context).any { "tv/1396 " in it && "episode in" in it && "· series image" in it })
        } finally {
            Settings.focusedCard = mode
        }
    }

    /** What the service answers [event] with once the card is drawn, or found to be nothing (asked again as Projectivy does). */
    private fun answer(service: IWallpaperProviderService, event: Event.ProgramCardFocused): List<tv.projectivy.plugin.wallpaperprovider.api.Wallpaper> {
        val card = FocusedWallpapers.card(event.title, event.type, event.iconUri)!!
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val wallpapers = service.getWallpapers(event)
            if (wallpapers.isNotEmpty() || FocusedWallpapers.matchesNothing(card)) return wallpapers
            Thread.sleep(1000)
        }
        return emptyList()
    }

    /** Bound and already given the set, as Projectivy is after its first request: card events then wait for their card. */
    private fun bindAsProjectivy(): IWallpaperProviderService = bind().also { it.getWallpapers(Event.TimeElapsed) }

    private fun bind(): IWallpaperProviderService {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent("tv.projectivy.plugin.WALLPAPER_PROVIDER").setPackage(context.packageName)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service not connected", latch.await(10, TimeUnit.SECONDS))
        return IWallpaperProviderService.Stub.asInterface(binder)
    }

    companion object {
        private const val TAG = "PluginServiceTest"
    }
}
