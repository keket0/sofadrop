package com.rfmarcos.sofadrop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The upcoming date of the header, from trimmed TMDB details (shapes as of November 2026). */
class ReleaseDateTest {

    private val today = "2026-11-20"

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun movie(vararg es: String) = obj(
        """{"release_date":"2026-11-25","release_dates":{"results":[{"iso_3166_1":"ES","release_dates":[${es.joinToString(",")}]},
           {"iso_3166_1":"US","release_dates":[{"type":3,"release_date":"2026-11-25T00:00:00.000Z"}]}]}}"""
    )
    private fun release(type: Int, date: String) = """{"type":$type,"release_date":"${date}T00:00:00.000Z","certification":""}"""

    private fun movieDate(item: JsonObject, country: String = "ES") = TmdbCatalog.upcomingDate(MediaType.MOVIE, item, country, today)

    @Test
    fun movieTakesTheCountrysFirstCinemaReleaseElseItsDigitalOne() {
        // Limited before wide, festival (type 1) ignored
        assertEquals("2026-11-26", movieDate(movie(release(1, "2026-10-17"), release(3, "2026-12-25"), release(2, "2026-11-26"))))
        // In cinemas already: the digital date to come
        assertEquals("2026-12-23", movieDate(movie(release(3, "2026-11-01"), release(4, "2026-12-23"))))
        // Straight to streaming
        assertEquals("2026-12-04", movieDate(movie(release(4, "2026-12-04"))))
        // Comes out today
        assertEquals(today, movieDate(movie(release(3, today))))
    }

    @Test
    fun noDateWhenItIsPastOrTheCountryHasNone() {
        // Out in cinemas and digitally: a re-release (type 2 later on) doesn't count
        assertNull(movieDate(movie(release(3, "2006-10-05"), release(4, "2007-01-26"), release(2, "2026-12-04"))))
        assertNull(movieDate(movie(release(3, "2026-10-01"))))
        // Only festival dates, or no entry for the country: neither the primary date nor the US one stand in
        assertNull(movieDate(movie(release(1, "2026-12-01"))))
        assertNull(movieDate(movie(release(3, "2026-12-01")), "FR"))
        assertNull(movieDate(obj("""{"release_date":"2026-12-01"}""")))
    }

    @Test
    fun seriesTakeTheirNextEpisode() {
        val next = obj("""{"first_air_date":"1989-12-17","next_episode_to_air":{"air_date":"2026-11-22","episode_number":7}}""")
        assertEquals("2026-11-22", TmdbCatalog.upcomingDate(MediaType.TV, next, "ES", today))
        val ended = obj("""{"first_air_date":"2008-01-20","next_episode_to_air":null}""")
        assertNull(TmdbCatalog.upcomingDate(MediaType.TV, ended, "ES", today))
        val stale = obj("""{"next_episode_to_air":{"air_date":"2026-11-19"}}""")
        assertNull(TmdbCatalog.upcomingDate(MediaType.TV, stale, "ES", today))
        val undated = obj("""{"next_episode_to_air":{"air_date":null}}""")
        assertNull(TmdbCatalog.upcomingDate(MediaType.TV, undated, "ES", today))
    }

    @Test
    fun dateTakesTheYearsPlaceOnlyInTheSameYear() {
        val date = "2026" to "22 nov 2026"
        // An upcoming movie or a new series: the year would repeat
        assertEquals("22 nov 2026", WallpaperFactory.joinedYearAndDate("2026", date))
        // A series that started long before keeps its year
        assertEquals("1989  ·  22 nov 2026", WallpaperFactory.joinedYearAndDate("1989", date))
        // Year hidden, or no date
        assertEquals("22 nov 2026", WallpaperFactory.joinedYearAndDate(null, date))
        assertEquals("1989", WallpaperFactory.joinedYearAndDate("1989", null))
    }
}
