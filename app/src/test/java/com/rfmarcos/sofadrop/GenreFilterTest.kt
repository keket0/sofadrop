package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreFilterTest {

    @Test
    fun onlyOrAllBut() {
        val comedy = 35
        val horror = 27
        assertTrue(GenreFilter().allows(listOf(horror)))
        assertTrue(GenreFilter(only = true).allows(listOf(horror)))

        val only = GenreFilter(setOf(comedy), only = true)
        assertTrue(only.allows(listOf(horror, comedy)))
        assertFalse(only.allows(listOf(horror)))
        assertFalse(only.allows(emptyList()))

        // The default: checked genres are skipped
        val allBut = GenreFilter(setOf(horror))
        assertFalse(allBut.allows(listOf(horror, comedy)))
        assertTrue(allBut.allows(listOf(comedy)))
        assertTrue(allBut.allows(emptyList()))
    }

    @Test
    fun animeVirtualGenreDistinguishesFromOtherAnimationAndLiveAction() {
        fun item(json: String) = Json.parseToJsonElement(json) as JsonObject
        val anime = item("""{"genres":[{"id":16},{"id":10759}],"original_language":"ja","origin_country":["JP"]}""")
        val animeByOrigin = item("""{"genres":[{"id":16}],"original_language":"en","origin_country":["JP"]}""")
        val westernAnimation = item("""{"genres":[{"id":16},{"id":35}],"original_language":"en","origin_country":["US"]}""")
        val japaneseLiveAction = item("""{"genres":[{"id":18}],"original_language":"ja","origin_country":["JP"]}""")

        assertTrue(Genres.isAnime(anime))
        assertTrue(Genres.isAnime(animeByOrigin))
        assertFalse(Genres.isAnime(westernAnimation))
        assertFalse(Genres.isAnime(japaneseLiveAction))

        // Excluding Anime keeps Western animation and Japanese live-action
        val skipAnime = GenreFilter(setOf(Genres.ANIME_ID), only = false)
        assertFalse(skipAnime.allows(Genres.effectiveIds(anime)))
        assertFalse(skipAnime.allows(Genres.effectiveIds(animeByOrigin)))
        assertTrue(skipAnime.allows(Genres.effectiveIds(westernAnimation)))
        assertTrue(skipAnime.allows(Genres.effectiveIds(japaneseLiveAction)))

        // Including only Anime keeps only Japanese animation
        val onlyAnime = GenreFilter(setOf(Genres.ANIME_ID), only = true)
        assertTrue(onlyAnime.allows(Genres.effectiveIds(anime)))
        assertFalse(onlyAnime.allows(Genres.effectiveIds(westernAnimation)))
        assertFalse(onlyAnime.allows(Genres.effectiveIds(japaneseLiveAction)))
    }

    @Test
    fun listFiltersStoredBeforeGenresStillRead() {
        val old = Json.decodeFromString<Map<String, ListFilters>>("""{"tmdb:trending_movie":{"maxAge":12}}""")
        assertEquals(ListFilters(maxAge = 12), old["tmdb:trending_movie"])
        assertFalse(old.getValue("tmdb:trending_movie").isDefault)
        assertTrue(ListFilters(genres = null).isDefault)
        assertFalse(ListFilters(genres = GenreFilter()).isDefault)
    }
}
