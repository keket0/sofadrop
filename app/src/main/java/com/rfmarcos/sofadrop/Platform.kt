package com.rfmarcos.sofadrop

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Streaming services a wallpaper can open its title on (the ones the user picks, in their order: see
 * [Profile.clickPlatforms]). TMDB says which ones have it in the streaming country (by provider id),
 * [JustWatch] gives the title's page on each one in that country (Wikidata when it hasn't: someone may
 * have added the title's id there) and the service's app opens that page.
 */
enum class Platform(
    val key: String,
    val label: String,
    /** TMDB watch provider ids, with their ad tiers and older ids (not channels inside another service). */
    private val providerIds: Set<Int>,
    /** Android TV app first. */
    internal val packages: List<String>,
    /** Wikidata properties of its ids, best first; none: its pages only come from [JustWatch]. */
    val ids: List<WikidataId>,
    /** Countries where TMDB lists it, for a service of a few; null: most of the world. */
    private val countries: Set<String>? = null,
) {
    NETFLIX("netflix", "Netflix", setOf(8, 175, 1796), listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        listOf(WikidataId("P1874") { "https://www.netflix.com/title/$it" })),
    PRIME("prime", "Prime Video", setOf(9, 119, 2100), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        listOf(
            WikidataId("P14462") { "https://app.primevideo.com/detail?gti=$it" },
            WikidataId("P14440") { "https://www.primevideo.com/detail/$it" },
            WikidataId("P8055") { "https://www.primevideo.com/detail/$it" },
        )),
    DISNEY("disney", "Disney+", setOf(337), listOf("com.disney.disneyplus"),
        listOf(
            WikidataId("P13902") { "https://www.disneyplus.com/browse/$it" },
            WikidataId("P7595", series = false) { "https://www.disneyplus.com/movies/wd/$it" },
            WikidataId("P7596", movies = false) { "https://www.disneyplus.com/series/wp/$it" },
        )),
    MAX("max", "HBO Max", setOf(384, 1899), listOf("com.wbd.stream"),
        listOf(WikidataId("P8298") { "https://play.hbomax.com/$it" })),
    APPLE("apple", "Apple TV", setOf(350), listOf("com.apple.atve.androidtv.appletv"),
        listOf(
            WikidataId("P9586", series = false) { "https://tv.apple.com/movie/$it" },
            WikidataId("P9751", movies = false) { "https://tv.apple.com/show/$it" },
        )),
    // com.cbs.ott: US TV app; com.cbs.ca: the international one
    PARAMOUNT("paramount", "Paramount+", setOf(531, 2303, 2304, 2616), listOf("com.cbs.ott", "com.cbs.ca", "com.cbs.app"), emptyList(),
        setOf("AR", "AT", "AU", "BO", "BR", "CA", "CH", "CL", "CO", "CR", "DE", "DO", "EC", "FR", "GB", "GT", "HN", "IE", "IT",
            "MX", "NI", "PA", "PE", "PY", "SV", "US", "UY", "VE")),
    SKYSHOWTIME("skyshowtime", "SkyShowtime", setOf(1773), listOf("com.skyshowtime.skyshowtime.google"), emptyList(),
        setOf("AD", "AL", "BA", "BG", "CZ", "DK", "ES", "FI", "HR", "HU", "MK", "NL", "NO", "PL", "PT", "RO", "RS", "SE", "SI", "SK")),
    MUBI("mubi", "MUBI", setOf(11), listOf("com.mubi"),
        listOf(WikidataId("P7299") { "https://mubi.com/films/$it" })),
    CRUNCHYROLL("crunchyroll", "Crunchyroll", setOf(283), listOf("com.crunchyroll.crunchyroid"),
        listOf(WikidataId("P11330") { "https://www.crunchyroll.com/series/$it" })),
    HULU("hulu", "Hulu", setOf(15), listOf("com.hulu.livingroomplus", "com.hulu.plus"),
        listOf(
            WikidataId("P6466", series = false) { "https://www.hulu.com/movie/$it" },
            WikidataId("P6467", movies = false) { "https://www.hulu.com/series/$it" },
        ), setOf("US")),
    TUBI("tubi", "Tubi", setOf(73), listOf("com.tubitv"),
        listOf(
            WikidataId("P7760", series = false) { "https://tubitv.com/movies/$it" },
            WikidataId("P7761", movies = false) { "https://tubitv.com/series/$it" },
        ), setOf("AU", "CA", "GB", "IN", "MX", "US")),
    KANOPY("kanopy", "Kanopy", setOf(191), listOf("com.kanopy"),
        listOf(WikidataId("P7985") { "https://www.kanopy.com/product/$it" }), setOf("US")),
    HOOPLA("hoopla", "Hoopla", setOf(212), listOf("com.hoopladigital.android"),
        listOf(WikidataId("P5680") { "https://www.hoopladigital.com/title/$it" }), setOf("AU", "CA", "NZ", "US")),
    UNEXT("unext", "U-NEXT", setOf(84), listOf("jp.unext.mediaplayer", "jp.unext.tv.player"),
        listOf(WikidataId("P14567") { "https://video.unext.jp/title/$it" }), setOf("JP")),
    NRK("nrk", "NRK TV", setOf(442), listOf("no.nrk.tv"),
        listOf(WikidataId("P9979") { "https://tv.nrk.no/$it" }), setOf("NO")),
    SVT("svt", "SVT Play", setOf(493), listOf("se.svt.android.svtplay"),
        listOf(WikidataId("P6817") { "https://www.svtplay.se/$it" }), setOf("SE")),
    YLE("yle", "Yle Areena", setOf(323), listOf("com.yle.webtv"),
        listOf(WikidataId("P3414") { "https://areena.yle.fi/$it" }), setOf("FI")),
    VIDEOLAND("videoland", "Videoland", setOf(72), listOf("nl.rtl.videoland.v2"), emptyList(), setOf("NL")),
    VIAPLAY("viaplay", "Viaplay", setOf(76), listOf("com.viaplay.android"), emptyList(), setOf("DK", "FI", "IS", "NL", "NO", "SE")),
    RTL_PLUS("rtlplus", "RTL+", setOf(2750), listOf("de.rtli.tvnow"), emptyList(), setOf("AT", "DE")),
    IPLAYER("iplayer", "BBC iPlayer", setOf(38), listOf("bbc.iplayer.android"), emptyList(), setOf("GB")),
    PEACOCK("peacock", "Peacock", setOf(386, 387), listOf("com.peacocktv.peacockandroid"), emptyList(), setOf("US")),
    STARZ("starz", "Starz", setOf(43), listOf("com.bydeluxe.d3.android.program.starz"), emptyList(), setOf("US")),
    NOW("now", "NOW", setOf(39, 591), listOf("com.bskyb.nowtv.beta"), emptyList(), setOf("GB", "IE")),
    CRAVE("crave", "Crave", setOf(230), listOf("ca.bellmedia.cravetv"), emptyList(), setOf("CA")),
    STAN("stan", "Stan", setOf(21), listOf("au.com.stan.and"), emptyList(), setOf("AU")),
    OSN("osn", "OSN+", setOf(629), listOf("com.osn.go"), emptyList(), setOf("AE", "BH", "EG", "IQ", "JO", "KW", "LB", "OM", "QA", "SA")),
    VIKI("viki", "Rakuten Viki", setOf(344), listOf("com.viki.android"), emptyList());

    /** A Wikidata property holding the title's id on a platform, and the page of an id. */
    class WikidataId(val property: String, val movies: Boolean = true, val series: Boolean = true, val url: (String) -> String)

    /** Worth offering in [region]: TMDB lists it there, or its app is installed anyway. */
    fun offeredIn(region: String, context: Context) = countries == null || region in countries || installedPackage(context) != null

    fun installedPackage(context: Context): String? = packages.firstOrNull { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Page of the title on this platform, from its Wikidata [ids] (property to value). */
    fun link(type: MediaType, ids: Map<String, String>): String? = this.ids
        .filter { if (type == MediaType.TV) it.series else it.movies }
        .firstNotNullOfOrNull { id -> ids[id.property]?.let(id.url) }

    companion object {
        fun of(key: String): Platform? = entries.firstOrNull { it.key == key }

        fun ofProvider(providerId: Int): Platform? = entries.firstOrNull { providerId in it.providerIds }

        /**
         * A title's pages from both sources: [justWatch]'s (of the streaming country), except a playback or
         * episode link (series on Apple TV, HBO Max, Crunchyroll) where [wikidata] has the title's own page.
         */
        fun merged(wikidata: Map<String, String>, justWatch: Map<String, String>): Map<String, String> =
            wikidata + justWatch.filterNot { (key, url) -> key in wikidata && PLAYBACK.containsMatchIn(url) }

        /**
         * The other page of the platforms both sources have, when it's another kind of link: an app may take
         * one and not the other ("app.primevideo.com/detail" and "watch.amazon.de/detail").
         */
        fun others(wikidata: Map<String, String>, justWatch: Map<String, String>): Map<String, String> {
            val first = merged(wikidata, justWatch)
            return wikidata.keys.intersect(justWatch.keys).mapNotNull { key ->
                (if (first[key] == wikidata[key]) justWatch[key] else wikidata[key])?.takeIf { it != first[key] }?.let { key to it }
            }.toMap()
        }

        private val PLAYBACK = Regex("/(episode|watch)/")

        /** Pages of a title on every platform Wikidata knows it on, by [key]. */
        fun links(type: MediaType, ids: Map<String, String>): Map<String, String> =
            entries.mapNotNull { platform -> platform.link(type, ids)?.let { platform.key to it } }.toMap()

        /**
         * The platforms of [order] (the user's, best first) that have the title in the streaming country
         * ([providerIds]), a page for it in [links] and are [installed], with that page (and then its
         * page in [others], for an app that doesn't take the first).
         */
        fun pick(
            order: List<Platform>, providerIds: List<Int>, links: Map<String, String>, others: Map<String, String> = emptyMap(),
            installed: (Platform) -> Boolean,
        ): List<Pair<Platform, String>> {
            val available = providerIds.mapNotNull(::ofProvider).toSet()
            return order.filter { it in available && installed(it) }
                .flatMap { platform -> listOfNotNull(links[platform.key], others[platform.key]).map { platform to it } }
        }
    }
}

/** Ids of titles on the streaming platforms, from Wikidata's query service (one request per media type). */
object Wikidata {
    private const val URL = "https://query.wikidata.org/sparql"
    // Wikidata asks for a user agent that says who is calling
    private val USER_AGENT = "Sofadrop/${BuildConfig.VERSION_NAME} (https://github.com/rfmarcos/sofadrop)"
    private val PROPERTIES = Platform.entries.flatMap { platform -> platform.ids.map { it.property } }.distinct()
    /** Wikidata properties of TMDB ids. */
    private val TMDB_PROPERTY = mapOf(MediaType.MOVIE to "P4947", MediaType.TV to "P4983")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Platform ids of [refs] (Wikidata property to value), only for the titles Wikidata knows. */
    fun ids(refs: Collection<TitleRef>): Map<TitleRef, Map<String, String>> =
        refs.groupBy { it.type }.flatMap { (type, ofType) ->
            ofType.chunked(BATCH).flatMap { batch ->
                val body = FormBody.Builder().add("query", query(TMDB_PROPERTY.getValue(type), batch.map { it.tmdbId })).build()
                val request = Request.Builder().url(URL).post(body)
                    .header("User-Agent", USER_AGENT).header("Accept", "application/sparql-results+json").build()
                val text = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("Wikidata: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                parse(text).map { (tmdbId, ids) -> TitleRef(type, tmdbId) to ids }
            }
        }.toMap()

    /** One row per title: its TMDB id ([tmdbProperty]) and a sample value of each platform property. */
    internal fun query(tmdbProperty: String, tmdbIds: List<Int>): String {
        val values = tmdbIds.joinToString(" ") { "\"$it\"" }
        val samples = PROPERTIES.joinToString(" ") { "(SAMPLE(?$it) AS ?${it}_)" }
        val optionals = PROPERTIES.joinToString(" ") { "OPTIONAL { ?item wdt:$it ?$it }" }
        return "SELECT ?tmdb $samples WHERE { VALUES ?tmdb { $values } ?item wdt:$tmdbProperty ?tmdb . $optionals } GROUP BY ?tmdb"
    }

    /** TMDB id to its platform ids, from a SPARQL JSON result of [query]. */
    internal fun parse(text: String): Map<Int, Map<String, String>> {
        val rows = (Json.parseToJsonElement(text) as? JsonObject)?.obj("results")?.get("bindings") as? JsonArray ?: return emptyMap()
        return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
            val tmdbId = row.obj("tmdb")?.str("value")?.toIntOrNull() ?: return@mapNotNull null
            tmdbId to row.keys.filter { it.endsWith("_") }.mapNotNull { key -> row.obj(key)?.str("value")?.let { key.removeSuffix("_") to it } }.toMap()
        }.toMap()
    }

    private const val BATCH = 100
}

/**
 * Pages of titles on the streaming platforms of a country, from JustWatch (where TMDB's "where to watch"
 * comes from, so its package ids are TMDB's provider ids). It's the API of its website, not a public one:
 * Wikidata stays behind it. It can't look a title up by its TMDB id, so each one is searched by name and
 * picked by its TMDB id; several searches go in one request.
 */
object JustWatch {
    private const val URL = "https://apis.justwatch.com/graphql"
    private val USER_AGENT = "Sofadrop/${BuildConfig.VERSION_NAME} (https://github.com/rfmarcos/sofadrop)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** No new batch after this long: a slow or unreachable site mustn't hold the generation for minutes. */
    private const val BUDGET_MILLIS = 90_000L

    /**
     * Pages of [titles] (by their names) in [country], by [Platform.key]; only the titles it has on a platform.
     * A batch that fails (a timeout, a 429) doesn't lose the others; it throws only when every batch failed.
     * Titles left out of time keep their Wikidata pages.
     */
    fun links(titles: Map<TitleRef, String>, country: String): Map<TitleRef, Map<String, String>> {
        // The country goes into the query as it is
        if (!country.matches(Regex("[A-Z]{2}"))) return emptyMap()
        val deadline = System.currentTimeMillis() + BUDGET_MILLIS
        val batches = titles.entries.chunked(BATCH).map { batch ->
            if (System.currentTimeMillis() > deadline) Result.failure(java.io.IOException("JustWatch: out of time"))
            else runCatching { fetch(batch.map { it.key }, batch.map { it.value }, country) }
        }
        if (batches.isNotEmpty() && batches.all { it.isFailure }) throw batches.first().exceptionOrNull()!!
        return batches.flatMap { it.getOrNull().orEmpty().toList() }.toMap()
    }

    private fun fetch(refs: List<TitleRef>, names: List<String>, country: String): Map<TitleRef, Map<String, String>> {
        val body = JsonObject(mapOf("query" to JsonPrimitive(query(names, country)))).toString()
        val request = Request.Builder().url(URL).post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT).build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("JustWatch: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        return parse(text, refs)
    }

    /** One search per name ("t0", "t1"…): its first results, with their TMDB id and their streaming offers in [country]. */
    internal fun query(names: List<String>, country: String): String = names.withIndex().joinToString(" ", "{ ", " }") { (i, name) ->
        // A JSON string is a valid GraphQL string
        "t$i: popularTitles(country: $country, first: 5, filter: {searchQuery: ${JsonPrimitive(name)}}) " +
            "{ edges { node { objectType content(country: $country, language: en) { externalIds { tmdbId } } " +
            // Not rent or buy: a third of the response, and never a store's page
            "offers(country: $country, platform: WEB, filter: {monetizationTypes: [FLATRATE, FREE, ADS]}) { package { packageId } standardWebURL } } } }"
    }

    /**
     * Pages of [refs] (in the order of [query]'s names) in a response, by [Platform.key]. A query JustWatch
     * rejects (a country it doesn't cover, a field it renamed) comes back without data: that throws, to be logged.
     */
    internal fun parse(text: String, refs: List<TitleRef>): Map<TitleRef, Map<String, String>> {
        val response = Json.parseToJsonElement(text) as? JsonObject
        val data = response?.obj("data") ?: throw java.io.IOException(
            "JustWatch: " + (response?.objects("errors")?.firstOrNull()?.str("message") ?: "no data"))
        return refs.withIndex().mapNotNull { (i, ref) ->
            val title = data.obj("t$i")?.objects("edges").orEmpty().mapNotNull { it.obj("node") }.firstOrNull { node ->
                node.str("objectType") == (if (ref.type == MediaType.TV) "SHOW" else "MOVIE") &&
                    node.obj("content")?.obj("externalIds")?.str("tmdbId") == ref.tmdbId.toString()
            } ?: return@mapNotNull null
            val links = linkedMapOf<String, String>()
            for (offer in title.objects("offers")) {
                val platform = offer.obj("package")?.int("packageId")?.let(Platform::ofProvider) ?: continue
                offer.str("standardWebURL")?.let { links.putIfAbsent(platform.key, unwrapped(it)) }
            }
            if (links.isEmpty()) null else ref to links
        }.toMap()
    }

    /** An affiliate redirect (crunchyroll.pxf.io/…?u=<page>) is the page it leads to: no app takes the redirect's host. */
    internal fun unwrapped(url: String): String =
        url.toHttpUrlOrNull()?.takeIf { it.host.endsWith(".pxf.io") }?.queryParameter("u") ?: url

    private const val BATCH = 20
}
