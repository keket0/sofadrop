package com.rfmarcos.sofadrop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Collator
import java.util.Locale

/**
 * One genre as the filter screen shows it. TMDB uses separate ids for movies and TV shows, even when
 * the name is identical (e.g. "Comedy"); a genre only on one side (Kids, War) has the other id null.
 * [description] explains a virtual genre (Anime).
 */
data class GenreOption(val name: String, val movieId: Int?, val tvId: Int?, val description: String? = null) {
    /** The id(s) this checkbox adds to or removes from a [GenreFilter]. */
    val ids: Set<Int> get() = setOfNotNull(movieId, tvId)
}

object Genres {

    /** TMDB Animation genre id (shared by movies and TV). */
    const val ANIMATION_ID = 16

    /** Virtual genre id for Japanese animation, never used by TMDB (issue #41). */
    const val ANIME_ID = -1

    /** True for Japanese animation: Animation genre plus Japanese original language or JP origin country. */
    fun isAnime(item: JsonObject): Boolean {
        val genreIds = item.objects("genres").mapNotNull { it.int("id") }
        if (ANIMATION_ID !in genreIds) return false
        if (item.str("original_language")?.equals("ja", ignoreCase = true) == true) return true
        return item.array("origin_country").any { (it as? JsonPrimitive)?.content.equals("JP", ignoreCase = true) }
    }

    /** TMDB genre ids of [item], plus [ANIME_ID] when it is anime so [GenreFilter] handles it like any other genre. */
    fun effectiveIds(item: JsonObject): List<Int> {
        val ids = item.objects("genres").mapNotNull { it.int("id") }
        return if (isAnime(item)) ids + ANIME_ID else ids
    }

    /**
     * Movie and TV genres from TMDB, in the configured language, merged by name into one list so the
     * screen shows one checkbox per genre instead of two near-duplicates ("Comedy" from movies and
     * "Comedy" from TV), plus the virtual Anime genre when [animeLabel] is given. Sorted alphabetically;
     * null on a network failure (shown as an error, not an empty list, so it isn't mistaken for "TMDB has no genres").
     */
    fun options(client: TmdbClient, language: String, animeLabel: String? = null, animeDescription: String? = null): List<GenreOption>? {
        val movies = client.get("genre/movie/list?language=$language")?.objects("genres") ?: return null
        val shows = client.get("genre/tv/list?language=$language")?.objects("genres") ?: return null
        val movieIds = linkedMapOf<String, Int>()
        val tvIds = linkedMapOf<String, Int>()
        // Named like the wallpapers name them (TMDB leaves some untranslated or, in Norwegian, blank)
        fun name(genre: JsonObject) = Languages.genreName(language, genre.int("id"), genre.str("name"))
        movies.forEach { g -> val name = name(g) ?: return@forEach; g.int("id")?.let { movieIds[name] = it } }
        shows.forEach { g -> val name = name(g) ?: return@forEach; g.int("id")?.let { tvIds[name] = it } }
        val tmdb = (movieIds.keys + tvIds.keys).distinct().map { name -> GenreOption(name, movieIds[name], tvIds[name]) }
        val virtual = listOfNotNull(animeLabel?.let { GenreOption(it, ANIME_ID, null, animeDescription) })
        val collator = Collator.getInstance(Locale.forLanguageTag(language))
        return (tmdb + virtual).sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
}
