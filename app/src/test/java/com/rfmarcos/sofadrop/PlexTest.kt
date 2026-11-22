package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex: library items and the account's servers as their APIs return them (server 1.43, November 2026). */
class PlexTest {

    @Test
    fun items() {
        val body = Json.parseToJsonElement(
            """
            {"MediaContainer": {"size": 6, "Metadata": [
              {"ratingKey": "5", "type": "movie", "title": "The Matrix", "guid": "plex://movie/5d776827880197001ec90904",
               "slug": "the-matrix-1999", "addedAt": 1794661479,
               "Guid": [{"id": "imdb://tt0133093"}, {"id": "tmdb://603"}, {"id": "tvdb://169"}]},
              {"ratingKey": "57", "type": "show", "title": "Severance", "slug": "severance", "addedAt": 1794661566,
               "Guid": [{"id": "imdb://tt11280740"}, {"id": "tmdb://95396"}]},
              {"ratingKey": "59", "type": "episode", "title": "Good News About Hell", "grandparentRatingKey": "57",
               "parentRatingKey": "58", "guid": "plex://episode/5e1622d34f2292003e913150"},
              {"ratingKey": "58", "type": "season", "title": "Season 1", "parentRatingKey": "57"},
              {"ratingKey": "9", "type": "movie", "title": "Old agent", "guid": "com.plexapp.agents.themoviedb://27205?lang=en"},
              {"ratingKey": "10", "type": "movie", "title": "Home video", "guid": "local://10"}
            ]}}
            """
        )
        val items = PlexClient.parseItems(body)
        assertEquals(TitleRef(MediaType.MOVIE, 603), items[0].ref)
        assertEquals("the-matrix-1999", items[0].slug)
        assertEquals(1794661479L, items[0].addedAt)
        assertEquals(TitleRef(MediaType.TV, 95396), items[1].ref)
        // Episodes and seasons count as their show, read by its key
        assertNull(items[2].ref)
        assertEquals("57", items[2].showKey)
        assertEquals("57", items[3].showKey)
        // Libraries still on the old TMDB agent
        assertEquals(TitleRef(MediaType.MOVIE, 27205), items[4].ref)
        assertNull(items[5].ref)
        assertTrue(PlexClient.parseItems(null).isEmpty())
    }

    @Test
    fun watched() {
        val body = Json.parseToJsonElement(
            """
            {"MediaContainer": {"Metadata": [
              {"ratingKey": "5", "type": "movie", "viewCount": 2, "Guid": [{"id": "tmdb://603"}]},
              {"ratingKey": "6", "type": "movie", "Guid": [{"id": "tmdb://550"}]},
              {"ratingKey": "57", "type": "show", "leafCount": 19, "viewedLeafCount": 3, "Guid": [{"id": "tmdb://95396"}]},
              {"ratingKey": "70", "type": "show", "leafCount": 7, "viewedLeafCount": 7, "Guid": [{"id": "tmdb://1396"}]},
              {"ratingKey": "80", "type": "show", "leafCount": 10, "viewedLeafCount": 0, "Guid": [{"id": "tmdb://1399"}]}
            ]}}
            """
        )
        val items = PlexClient.parseItems(body)
        assertEquals(listOf(WatchStatus.Watched, null, WatchStatus.InProgress(3, 19), WatchStatus.Watched, null), items.map { it.watched })
    }

    @Test
    fun servers() {
        val body = Json.parseToJsonElement(
            """
            [
              {"name": "Salón", "product": "Plex Media Server", "provides": "server", "clientIdentifier": "b12b2c",
               "accessToken": "server-token", "owned": true, "connections": [
                 {"protocol": "https", "address": "192.168.1.10", "port": 32400, "uri": "https://192-168-1-10.abc.plex.direct:32400", "local": true, "relay": false},
                 {"protocol": "https", "address": "80.1.2.3", "port": 32400, "uri": "https://80-1-2-3.abc.plex.direct:32400", "local": false, "relay": false},
                 {"protocol": "https", "address": "1.2.3.4", "port": 8443, "uri": "https://1-2-3-4.abc.plex.direct:8443", "local": false, "relay": true}
               ]},
              {"name": "Android TV", "product": "Plex for Android (TV)", "provides": "client,player", "clientIdentifier": "tv1"},
              {"name": "Friend", "provides": "server", "clientIdentifier": "f00", "accessToken": "shared-token", "owned": false, "connections": []}
            ]
            """
        )
        val servers = PlexClient.parseResources(body)
        assertEquals(listOf("b12b2c", "f00"), servers.map { it.id })
        assertEquals("server-token", servers[0].token)
        assertTrue(servers[0].owned)
        assertEquals(listOf(true, false, false), servers[0].connections.map { it.local })
        assertTrue(servers[0].connections[2].relay)
        assertEquals(false, servers[1].owned)
        assertTrue(PlexClient.parseResources(null).isEmpty())
    }

    /** A plex.direct name gives its IP without DNS, which a router's rebind protection may block (#33). */
    @Test
    fun plexDirectIp() {
        fun ip(host: String) = PlexClient.plexDirectIp(host)?.hostAddress
        assertEquals("192.168.1.10", ip("192-168-1-10.0123456789abcdef0123456789abcdef.plex.direct"))
        assertEquals("80.1.2.3", ip("80-1-2-3.ABC.PLEX.DIRECT"))
        assertEquals("2001:db8:0:0:0:0:0:1", ip("2001-db8--1.abc.plex.direct"))
        assertNull(ip("plex.tv"))
        assertNull(ip("192-168-1-10.example.com"))
        assertNull(ip("abc.plex.direct"))
        assertNull(ip("my-server.abc.plex.direct"))
    }

    @Test
    fun wallpaperSourceLabels() {
        fun label(key: String, language: String) = Languages.sourceLabel(language, Sources.PLEX.first { it.key == key })
        assertEquals("Recently added", label(PlexClient.LATEST_MOVIES, "en-US"))
        assertEquals("Viendo", label(PlexClient.WATCHING, "es-ES"))
        assertEquals("Tu biblioteca", label(PlexClient.LIBRARY_SHOWS, "es-ES"))
    }
}
