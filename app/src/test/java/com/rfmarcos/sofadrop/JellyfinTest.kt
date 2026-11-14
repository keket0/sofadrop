package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Jellyfin: addresses typed on the TV, discovery answers and items as the API returns them (10.11, November 2026). */
class JellyfinTest {

    @Test
    fun typedAddresses() {
        // Without a port: Jellyfin's own first, then a reverse proxy's
        assertEquals(
            listOf("http://192.168.1.10:8096", "https://192.168.1.10", "http://192.168.1.10"),
            JellyfinClient.addressCandidates(" 192.168.1.10/ "),
        )
        assertEquals(listOf("http://jellyfin.lan:8920"), JellyfinClient.addressCandidates("jellyfin.lan:8920"))
        assertEquals(listOf("https://media.example.com/jellyfin"), JellyfinClient.addressCandidates("https://media.example.com/jellyfin/"))
        assertEquals(emptyList<String>(), JellyfinClient.addressCandidates("  "))
    }

    @Test
    fun discoveryAnswer() {
        val server = JellyfinClient.parseDiscovery("""{"Address":"http://192.168.1.10:8096/","Id":"f2b1c","Name":"Salón","EndpointAddress":null}""")
        assertEquals(JellyfinServer("http://192.168.1.10:8096", "f2b1c", "Salón"), server)
        assertNull(JellyfinClient.parseDiscovery("who is JellyfinServer?"))
        assertNull(JellyfinClient.parseDiscovery("""{"Name":"No address"}"""))
    }

    @Test
    fun items() {
        val body = Json.parseToJsonElement(
            """
            {"Items": [
              {"Name": "The Matrix", "Id": "a1", "Type": "Movie", "ProviderIds": {"Tmdb": "603", "Imdb": "tt0133093"}},
              {"Name": "Severance", "Id": "b2", "Type": "Series", "ProviderIds": {"tmdb": "95396"}},
              {"Name": "Good News About Hell", "Id": "c3", "Type": "Episode", "SeriesId": "b2", "ProviderIds": {"Tvdb": "7000000"}},
              {"Name": "Home video", "Id": "d4", "Type": "Movie", "ProviderIds": {}},
              {"Name": "A folder", "Id": "e5", "Type": "BoxSet", "ProviderIds": {"Tmdb": "2344"}}
            ], "TotalRecordCount": 5, "StartIndex": 0}
            """
        )
        val items = JellyfinClient.parseItems(body)
        assertEquals(TitleRef(MediaType.MOVIE, 603), items[0].ref)
        // Older libraries write the provider in another case
        assertEquals(TitleRef(MediaType.TV, 95396), items[1].ref)
        // An episode counts as its series, looked up by its id
        assertNull(items[2].ref)
        assertEquals("b2", items[2].seriesId)
        assertNull(items[3].ref)
        // Collections aren't titles
        assertNull(items[4].ref)
        assertNull(items[4].seriesId)
        assertEquals(emptyList<JellyfinClient.Companion.Item>(), JellyfinClient.parseItems(null))
    }

    @Test
    fun watchedSeries() {
        val body = Json.parseToJsonElement(
            """
            {"Items": [
              {"Id": "a1", "Type": "Series", "ProviderIds": {"Tmdb": "95396"}, "RecursiveItemCount": 19,
               "UserData": {"UnplayedItemCount": 16, "PlayedPercentage": 15.8, "Played": false}},
              {"Id": "b2", "Type": "Series", "ProviderIds": {"Tmdb": "1396"}, "RecursiveItemCount": 7,
               "UserData": {"UnplayedItemCount": 0, "PlayedPercentage": 100, "Played": true}},
              {"Id": "c3", "Type": "Series", "ProviderIds": {"Tmdb": "1399"}, "RecursiveItemCount": 10,
               "UserData": {"UnplayedItemCount": 10, "Played": false}},
              {"Id": "d4", "Type": "Movie", "ProviderIds": {"Tmdb": "603"}, "UserData": {"PlayCount": 1, "Played": true}}
            ]}
            """
        )
        val items = JellyfinClient.parseItems(body)
        assertEquals(WatchStatus.InProgress(3, 19), items[0].watched)
        assertEquals(WatchStatus.Watched, items[1].watched)
        assertNull(items[2].watched)
        assertEquals(true, items[3].played)
    }

    @Test
    fun wallpaperSourceLabels() {
        fun label(key: String, language: String) = Languages.sourceLabel(language, Sources.JELLYFIN.first { it.key == key })
        assertEquals("Recently added", label(JellyfinClient.LATEST_MOVIES, "en-US"))
        assertEquals("Recién añadidas", label(JellyfinClient.LATEST_SHOWS, "es-ES"))
        assertEquals("Viendo", label(JellyfinClient.WATCHING, "es-ES"))
        assertEquals("Your favourites", label(JellyfinClient.FAVORITES, "en-GB"))
        assertEquals("Tu biblioteca", label(JellyfinClient.LIBRARY_SHOWS, "es-ES"))
        Languages.OPTIONS.keys.filterNot { it.startsWith("en") }.forEach { language ->
            assertNotEquals(language, "Your library", label(JellyfinClient.LIBRARY_MOVIES, language))
            assertNotEquals(language, "Recently added", label(JellyfinClient.LATEST_MOVIES, language))
        }
    }
}
