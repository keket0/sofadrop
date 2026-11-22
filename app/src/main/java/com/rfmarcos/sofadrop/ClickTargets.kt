package com.rfmarcos.sofadrop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/**
 * What a wallpaper opens: the user's platforms that have the title, in their order (see [Platform]), then
 * the fallback ([Profile.clickFallback]): an app (the trailer when it isn't installed), the trailer or
 * nothing.
 */
object ClickTargets {
    private const val YOUTUBE_TV = "com.google.android.youtube.tv"

    /**
     * The apps installed on the TV, looked up once for a whole set ([candidates] of a hundred wallpapers
     * would otherwise ask the package manager for every platform again and again).
     */
    class Apps(private val context: Context) {
        private val packages = mutableMapOf<Any, String?>()
        private val opens = mutableMapOf<String, Boolean>()

        fun of(platform: Platform): String? = packages.cached(platform) { platform.installedPackage(context) }
        fun of(app: TitleApp): String? = packages.cached(app) { app.installedPackage(context) }
        val youTube: Boolean by lazy { runCatching { context.packageManager.getPackageInfo(YOUTUBE_TV, 0) }.isSuccess }

        /** An installed app takes [intent]; links of one app and kind are taken alike. */
        fun opens(intent: Intent): Boolean {
            val data = intent.data
            // The kind of link is its first path segment, past a country one ("tv.apple.com/es/movie/…")
            val kind = data?.pathSegments?.firstOrNull { it.length != 2 }
            val key = "${intent.`package`}|${intent.action}|${data?.scheme}|${data?.host}|$kind"
            return opens.getOrPut(key) { intent.resolveActivity(context.packageManager) != null }
        }

        private fun <K> MutableMap<K, String?>.cached(key: K, find: () -> String?): String? =
            if (key in this) this[key] else find().also { this[key] = it }
    }

    /** Intents that open [wallpaper] in [profile]'s place, best first; only those an installed app takes. */
    fun candidates(context: Context, wallpaper: StoredWallpaper, profile: Profile, apps: Apps = Apps(context)): List<Intent> {
        val platforms = Platform.pick(profile.clickPlatforms, wallpaper.providerIds, wallpaper.platformLinks, wallpaper.platformOthers) { apps.of(it) != null }
            .map { (platform, link) -> Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(apps.of(platform)) }
        val fallback = profile.clickFallback
        val app = Settings.clickApp(fallback)?.let { app -> apps.of(app)?.let { app.intent(context, it, wallpaper) } }
        // Also after an app, harmless: only the first one is served
        val trailer = wallpaper.trailerKey?.takeIf { fallback != Settings.CLICK_NONE }?.let { key ->
            val link = Intent(Intent.ACTION_VIEW, (WATCH_URL + key).toUri())
            // The client picked while it's installed; else, since several apps open YouTube links on a TV
            // (browsers, Downloader…), YouTube's own, without asking
            Settings.trailerApp?.let { Intent(link).setPackage(it) }?.takeIf { apps.opens(it) }
                ?: link.apply { if (apps.youTube) setPackage(YOUTUBE_TV) }
        }
        // An app that doesn't take that kind of link would fail silently. Not the trailer: whatever opens
        // YouTube links (its phone app, a browser) is hidden from Sofadrop, and something always does
        val usable = (platforms + listOfNotNull(app)).filter { apps.opens(it) }
        return (usable + listOfNotNull(trailer)).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /**
     * Other YouTube clients (SmartTube, TizenTube…), as package to name: the installed apps that open YouTube
     * links but not any web link (browsers, Downloader), other than YouTube. Taking the link doesn't mean
     * playing it (TizenTube 2.0.3 stays on its home), so the user picks one, YouTube being the default.
     */
    fun youTubeClients(context: Context): Map<String, String> {
        val packageManager = context.packageManager
        fun takers(url: String) = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            // With verified links (YouTube's) only YouTube would come; MATCH_ALL alone keeps the others
            // (with MATCH_DEFAULT_ONLY too it doesn't, tested on Google TV 14)
            PackageManager.MATCH_ALL,
        ).associate { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        val web = takers("https://example.com/watch?v=$PROBE").keys
        return clients(takers(WATCH_URL + PROBE), web, context.packageName)
    }

    /** [youTube]'s takers that aren't [web]'s, YouTube or Sofadrop, by name. */
    internal fun clients(youTube: Map<String, String>, web: Set<String>, own: String): Map<String, String> =
        youTube.filterKeys { it !in web && it != YOUTUBE_TV && it != own }.toList().sortedBy { it.second.lowercase() }.toMap()

    private const val WATCH_URL = "https://www.youtube.com/watch?v="
    private const val PROBE = "dQw4w9WgXcQ"
}
