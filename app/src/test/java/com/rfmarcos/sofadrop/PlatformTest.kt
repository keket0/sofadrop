package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {

    @Test
    fun parsesWikidataRows() {
        val json = """
            {"head":{"vars":["tmdb","P1874_","P8298_"]},"results":{"bindings":[
              {"tmdb":{"type":"literal","value":"671"},"P1874_":{"type":"literal","value":"60004478"},"P8298_":{"type":"literal","value":"feature/urn:hbo:feature:GXdu2ZAglVJuAuwEAADbA"}},
              {"tmdb":{"type":"literal","value":"1184918"}},
              {"P1874_":{"type":"literal","value":"1"}}
            ]}}
        """.trimIndent()
        val ids = Wikidata.parse(json)
        assertEquals(setOf(671, 1184918), ids.keys)
        assertEquals("60004478", ids[671]?.get("P1874"))
        assertEquals("feature/urn:hbo:feature:GXdu2ZAglVJuAuwEAADbA", ids[671]?.get("P8298"))
        assertTrue(ids.getValue(1184918).isEmpty())
    }

    @Test
    fun queryAsksForEveryPlatformProperty() {
        val query = Wikidata.query("P4947", listOf(671, 603))
        assertTrue(query.contains("VALUES ?tmdb { \"671\" \"603\" }"))
        assertTrue(query.contains("?item wdt:P4947 ?tmdb"))
        for (property in Platform.entries.flatMap { platform -> platform.ids.map { it.property } }) {
            assertTrue(property, query.contains("OPTIONAL { ?item wdt:$property ?$property }"))
        }
    }

    @Test
    fun linksUseTheTypesProperty() {
        val ids = mapOf("P9586" to "umc.cmc.movie", "P9751" to "umc.cmc.show", "P7596" to "abc")
        val movie = Platform.links(MediaType.MOVIE, ids)
        assertEquals("https://tv.apple.com/movie/umc.cmc.movie", movie["apple"])
        assertNull(movie["disney"])
        val series = Platform.links(MediaType.TV, ids)
        assertEquals("https://tv.apple.com/show/umc.cmc.show", series["apple"])
        assertEquals("https://www.disneyplus.com/series/wp/abc", series["disney"])
    }

    @Test
    fun disneyBrowseIdFirst() {
        val ids = mapOf("P7596" to "abc", "P13902" to "entity-1")
        assertEquals("https://www.disneyplus.com/browse/entity-1", Platform.links(MediaType.TV, ids)["disney"])
        assertEquals("https://www.disneyplus.com/series/wp/abc", Platform.links(MediaType.TV, ids - "P13902")["disney"])
    }

    @Test
    fun primeGtiFirst() {
        val links = Platform.links(MediaType.MOVIE, mapOf("P8055" to "B0OLD", "P14462" to "amzn1.dv.gti.x"))
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.x", links["prime"])
    }

    @Test
    fun justWatchQueryHasOneSearchPerTitle() {
        val query = JustWatch.query(listOf("Severance", "Say \"hi\""), "NL")
        assertTrue(query.contains("t0: popularTitles(country: NL, first: 5, filter: {searchQuery: \"Severance\"})"))
        // Quotes in a name stay inside its string
        assertTrue(query.contains("t1: popularTitles(country: NL, first: 5, filter: {searchQuery: \"Say \\\"hi\\\"\"})"))
        assertTrue(query.contains("offers(country: NL, platform: WEB)"))
    }

    @Test
    fun justWatchLinksOfTheTitleWithThatTmdbId() {
        val json = """
            {"data":{
              "t0":{"edges":[
                {"node":{"objectType":"MOVIE","content":{"externalIds":{"tmdbId":"760195"}},"offers":[{"package":{"packageId":8},"standardWebURL":"https://www.netflix.com/title/wrong"}]}},
                {"node":{"objectType":"SHOW","content":{"externalIds":{"tmdbId":"95396"}},"offers":[
                  {"package":{"packageId":2243},"standardWebURL":"https://app.primevideo.com/detail?gti=channel"},
                  {"package":{"packageId":350},"standardWebURL":"https://tv.apple.com/nl/show/first"},
                  {"package":{"packageId":350},"standardWebURL":"https://tv.apple.com/nl/show/second"}
                ]}}
              ]},
              "t1":{"edges":[{"node":{"objectType":"SHOW","content":{"externalIds":{"tmdbId":"1"}},"offers":[]}}]},
              "t2":{"edges":[{"node":{"objectType":"MOVIE","content":{"externalIds":{"tmdbId":"872585"}},"offers":[{"package":{"packageId":71},"standardWebURL":"https://www.pathe-thuis.nl/film/35369"}]}}]}
            }}
        """.trimIndent()
        val severance = TitleRef(MediaType.TV, 95396)
        val refs = listOf(severance, TitleRef(MediaType.TV, 1), TitleRef(MediaType.MOVIE, 872585))
        // The show, not the movie found first; its first page on each platform; a channel inside Prime isn't Prime;
        // no title without a page on a known platform (Pathé Thuis isn't one)
        assertEquals(mapOf(severance to mapOf("apple" to "https://tv.apple.com/nl/show/first")), JustWatch.parse(json, refs))
        assertTrue(JustWatch.parse("""{"errors":[{"message":"bad country"}],"data":null}""", refs).isEmpty())
        assertTrue(JustWatch.links(mapOf(severance to "Severance"), "nl\"){").isEmpty())
    }

    @Test
    fun platformsWithoutWikidataIdsTakeJustWatchPages() {
        val json = """
            {"data":{"t0":{"edges":[{"node":{"objectType":"SHOW","content":{"externalIds":{"tmdbId":"73586"}},"offers":[
              {"package":{"packageId":1773},"standardWebURL":"https://www.skyshowtime.com/nl/stream/tv/yellowstone/x"},
              {"package":{"packageId":72},"standardWebURL":"https://v2.videoland.com/yellowstone-p_1"},
              {"package":{"packageId":2624},"standardWebURL":"https://tv2.dk/skyshowtime"}
            ]}}]}}}
        """.trimIndent()
        val yellowstone = TitleRef(MediaType.TV, 73586)
        // TV 2's SkyShowtime bundle (2624) isn't SkyShowtime
        val links = JustWatch.parse(json, listOf(yellowstone)).getValue(yellowstone)
        assertEquals(setOf("skyshowtime", "videoland"), links.keys)
        assertEquals(listOf(Platform.VIDEOLAND, Platform.SKYSHOWTIME),
            Platform.pick(listOf(Platform.VIDEOLAND, Platform.MUBI, Platform.SKYSHOWTIME), listOf(72, 1773, 11), links) { true }.map { it.first })
        assertEquals(Platform.PARAMOUNT, Platform.ofProvider(2303))
        assertNull(Platform.ofProvider(582)) // Paramount+ Amazon Channel
    }

    @Test
    fun picksTheUsersPlatformsInTheirOrder() {
        val links = mapOf("netflix" to "https://www.netflix.com/title/1", "max" to "https://play.hbomax.com/x", "apple" to "https://tv.apple.com/movie/y")
        val providers = listOf(149, 350, 1796, 1899)
        // The user's order, not TMDB's; Netflix through its ad tier (1796); Movistar (149) is no platform
        assertEquals(listOf(Platform.MAX, Platform.NETFLIX),
            Platform.pick(listOf(Platform.MAX, Platform.NETFLIX), providers, links) { true }.map { it.first })
        // Skipped: not installed, not in the user's list, or no page
        assertEquals(listOf(Platform.APPLE to "https://tv.apple.com/movie/y"),
            Platform.pick(listOf(Platform.MAX, Platform.APPLE, Platform.DISNEY), providers + 337, links) { it != Platform.MAX })
        // A page of a platform that doesn't have it in the country isn't used
        assertTrue(Platform.pick(Platform.entries, listOf(337), links) { true }.isEmpty())
        // A channel inside another service (HBO Max Amazon Channel) isn't that service
        assertTrue(Platform.pick(Platform.entries, listOf(1825), links) { true }.isEmpty())
    }
}
