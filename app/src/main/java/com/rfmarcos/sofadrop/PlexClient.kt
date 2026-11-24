package com.rfmarcos.sofadrop

import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A Plex Media Server the account can use: its [id] (machine identifier), [name], the [token] to use on it
 * (the account's own for an owned server, another for a shared one), whether the account [owned] it, and
 * the addresses plex.tv knows for it, best first.
 */
data class PlexServer(val id: String, val name: String, val token: String, val owned: Boolean, val connections: List<PlexConnection>)

/** One way to reach a server: on the local network, from outside, or through Plex's relay. */
data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)

/**
 * Plex: the account on plex.tv (signed in with a PIN, see [PlexLoginDialog]) and the server it picked.
 * The lists are read from the server's libraries, each title by the TMDB id Plex matched it to (titles
 * without one are skipped). [address] is the server's base URL, [token] the server's token.
 */
class PlexClient(private val address: String, private val token: String) {

    // --- Lists ---

    /** TMDB ids of a Plex list key such as "plex:latest_movies", best first. */
    fun titles(listKey: String, limit: Int): List<TitleRef> = when (listKey) {
        LATEST_MOVIES -> fromSections("movie", "type" to "1", "sort" to "addedAt:desc", size = limit).sortedByDescending { it.addedAt }
        // A show is new when an episode is: the newest episodes, as their shows
        LATEST_SHOWS -> asShows(fromSections("show", "type" to "4", "sort" to "addedAt:desc", size = limit * 3).sortedByDescending { it.addedAt })
        WATCHING -> asShows(watching())
        LIBRARY_MOVIES -> fromSections("movie", "type" to "1", "sort" to "random", size = limit).shuffled()
        LIBRARY_SHOWS -> fromSections("show", "type" to "2", "sort" to "random", size = limit).shuffled()
        else -> emptyList()
    }.mapNotNull { it.ref }.distinct().take(limit)

    /**
     * Every movie and show of the libraries with a TMDB id, to its Plex slug (the title's page on
     * watch.plex.tv, see [TitleApp.PLEX]).
     */
    fun librarySlugs(): Map<TitleRef, String> = (fromSections("movie", "type" to "1") + fromSections("show", "type" to "2"))
        .mapNotNull { item -> item.ref?.let { ref -> item.slug?.let { ref to it } } }.toMap()

    /**
     * What the account has watched in the libraries: movies seen at least once, and shows by how many of
     * their episodes on the server were seen (all of them: watched).
     */
    fun watched(): Map<TitleRef, WatchStatus> = (fromSections("movie", "type" to "1") + fromSections("show", "type" to "2"))
        .mapNotNull { item -> item.ref?.let { ref -> item.watched?.let { ref to it } } }.toMap()

    /** Plex's Continue Watching: movies and episodes started, then the next episode of the shows being watched. */
    private fun watching(): List<Item> {
        val hub = runCatching { parseItems(request("hubs/home/continueWatching", "includeGuids" to "1")) }.getOrDefault(emptyList())
        val onDeck = runCatching { parseItems(request("library/onDeck", "includeGuids" to "1")) }.getOrDefault(emptyList())
        return (hub + onDeck).distinctBy { it.ratingKey }
    }

    /** Episodes (and seasons) as their shows, read in one request, in the order they come. */
    private fun asShows(items: List<Item>): List<Item> {
        val showKeys = items.mapNotNull { it.showKey }.distinct()
        val shows = if (showKeys.isEmpty()) emptyMap()
            else parseItems(request("library/metadata/${showKeys.joinToString(",")}", "includeGuids" to "1")).associateBy { it.ratingKey }
        return items.mapNotNull { item -> if (item.showKey != null) shows[item.showKey] else item.takeIf { it.type != null } }
    }

    /** Items of every library of [kind] ("movie" or "show"); [size] of each one, all when null. */
    private fun fromSections(kind: String, vararg params: Pair<String, String>, size: Int? = null): List<Item> =
        sections().filter { it.second == kind }.flatMap { (key, _) ->
            val page = size?.let { listOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "$it") }.orEmpty()
            parseItems(request("library/sections/$key/all", *params, "includeGuids" to "1", *page.toTypedArray()))
        }

    /** The libraries: key and type ("movie", "show", "artist", "photo"). */
    private fun sections(): List<Pair<String, String>> =
        (request("library/sections") as? JsonObject)?.obj("MediaContainer")?.objects("Directory").orEmpty().mapNotNull { section ->
            val key = section.str("key") ?: return@mapNotNull null
            key to section.str("type").orEmpty()
        }

    /** The server's machine identifier: it answers at [address] and is the one expected. */
    fun identity(): String? = (request("identity") as? JsonObject)?.obj("MediaContainer")?.str("machineIdentifier")

    // --- HTTP ---

    private fun request(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = "${address.trimEnd('/')}/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        return call(Request.Builder().url(url).headers(token), http)
    }

    companion object {
        const val PREFIX = "plex:"
        const val LATEST_MOVIES = "plex:latest_movies"
        const val LATEST_SHOWS = "plex:latest_shows"
        const val WATCHING = "plex:watching"
        const val LIBRARY_MOVIES = "plex:library_movies"
        const val LIBRARY_SHOWS = "plex:library_shows"

        private const val PLEX_TV = "https://plex.tv/api/v2"
        private const val CLIENTS = "https://clients.plex.tv/api/v2"
        private const val METADATA = "https://metadata.provider.plex.tv"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // A server at home that's off answers nothing: give up soon
        private val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = plexDirectIp(hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** For trying a server's addresses: a wrong one (another network, a container's) fails fast. */
        private val probe = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

        /**
         * The IP a server's `192-168-1-10.<hash>.plex.direct` name spells out, read from the name instead of
         * asked to DNS: routers with DNS rebind protection (a Fritz!Box) don't answer names that point into the
         * local network, so no address of the server would work (#33). TLS still checks the server's
         * certificate against the name. Null for other hosts.
         */
        internal fun plexDirectIp(host: String): InetAddress? {
            if (!host.endsWith(".plex.direct", ignoreCase = true)) return null
            val label = host.substringBefore('.')
            val literal = when {
                label.matches(Regex("""\d{1,3}(-\d{1,3}){3}""")) -> label.replace('-', '.')
                // IPv6: "2001-db8--1" is 2001:db8::1
                '-' in label && label.all { it == '-' || Character.digit(it, 16) >= 0 } -> label.replace('-', ':')
                else -> return null
            }
            return runCatching { InetAddress.getByName(literal) }.getOrNull()
        }

        class HttpException(val code: Int, path: String) : IOException("Plex HTTP $code for $path")

        /** Plex's headers: which app and device asks (a sign-in is shown by these), and [token] when there's one. */
        private fun Request.Builder.headers(token: String): Request.Builder = apply {
            header("Accept", "application/json")
            header("X-Plex-Product", "Sofadrop")
            header("X-Plex-Version", BuildConfig.VERSION_NAME)
            header("X-Plex-Client-Identifier", Settings.plexClientId)
            header("X-Plex-Platform", "Android")
            header("X-Plex-Device", "Android TV")
            header("X-Plex-Device-Name", (Build.MODEL ?: "Android TV"))
            if (token.isNotBlank()) header("X-Plex-Token", token)
        }

        private fun call(builder: Request.Builder, client: OkHttpClient): JsonElement? =
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code, response.request.url.encodedPath)
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) null else json.parseToJsonElement(text)
            }

        private fun plexTv(url: String, token: String = "", post: Boolean = false, vararg params: Pair<String, String>): JsonElement? {
            val built = url.toHttpUrl().newBuilder().apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
            val builder = Request.Builder().url(built).headers(token)
            if (post) builder.post(ByteArray(0).toRequestBody())
            return call(builder, http)
        }

        /** The connected server, or null. */
        fun fromSettings(): PlexClient? = if (!Settings.plexConnected) null else PlexClient(Settings.plexAddress, Settings.plexServerToken)

        // --- Signing in (plex.tv) ---

        /**
         * A sign-in code: [strong] ones go in the link of the QR (app.plex.tv/auth), short ones are typed at
         * plex.tv/link. Both are approved by a signed-in Plex account.
         */
        data class Pin(val id: Long, val code: String, val expiresIn: Int)

        fun createPin(strong: Boolean): Pin {
            val body = plexTv("$PLEX_TV/pins", post = true, params = arrayOf("strong" to "$strong")) as? JsonObject
                ?: throw IOException("No Plex code")
            return Pin(
                (body["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: throw IOException("No Plex code"),
                body.str("code") ?: throw IOException("No Plex code"),
                body.int("expiresIn") ?: 900,
            )
        }

        /** The account's token once [pin] was approved, else null. */
        fun pinToken(pin: Pin): String? = (plexTv("$PLEX_TV/pins/${pin.id}") as? JsonObject)?.str("authToken")?.takeIf { it.isNotBlank() }

        /** The page that approves a strong [code] after signing in, for the QR. */
        fun authUrl(code: String): String =
            "https://app.plex.tv/auth#?clientID=${Settings.plexClientId}&code=$code&context%5Bdevice%5D%5Bproduct%5D=Sofadrop"

        /** The account's user name. */
        fun accountName(token: String): String = (plexTv("$PLEX_TV/user", token) as? JsonObject)?.let { it.str("username") ?: it.str("title") }.orEmpty()

        /** The servers the account can use: its own first. */
        fun servers(token: String): List<PlexServer> =
            parseResources(plexTv("$CLIENTS/resources", token, params = arrayOf("includeHttps" to "1", "includeRelay" to "1")))
                .sortedByDescending { it.owned }

        /**
         * The address of [server] that answers from this TV, tried all at once: on the local network first,
         * then from outside, then through Plex's relay (slow, limited, but it works anywhere).
         */
        fun reachable(server: PlexServer): String? {
            val tries = server.connections.sortedBy { if (it.relay) 2 else if (it.local) 0 else 1 }
            if (tries.isEmpty()) return null
            val pool = Executors.newFixedThreadPool(tries.size.coerceAtMost(6))
            try {
                val answers = tries.map { connection ->
                    pool.submit(Callable {
                        runCatching {
                            val url = "${connection.uri.trimEnd('/')}/identity".toHttpUrl()
                            val body = call(Request.Builder().url(url).headers(server.token), probe) as? JsonObject
                            body?.obj("MediaContainer")?.str("machineIdentifier") == server.id
                        }.getOrDefault(false)
                    })
                }
                return tries.zip(answers).firstOrNull { (_, answer) -> runCatching { answer.get(12, TimeUnit.SECONDS) }.getOrDefault(false) }?.first?.uri
            } finally {
                pool.shutdownNow()
            }
        }

        /** The address [Settings] has for the server if it still answers, else a new one (saved). */
        fun connect(): PlexClient? {
            if (!Settings.plexConnected) return null
            val saved = PlexClient(Settings.plexAddress, Settings.plexServerToken)
            if (runCatching { saved.identity() == Settings.plexServerId }.getOrDefault(false)) return saved
            // Moved (a new IP, remote access): plex.tv knows where it is now
            val server = servers(Settings.plexToken).firstOrNull { it.id == Settings.plexServerId } ?: return saved
            val address = reachable(server) ?: return saved
            Settings.savePlexServer(server, address)
            return PlexClient(address, server.token)
        }

        /** The title's slug on watch.plex.tv ("the-matrix-1999"), from Plex's own catalogue; null when it has none. */
        fun slug(token: String, ref: TitleRef): String? {
            val body = plexTv("$METADATA/library/metadata/matches", token,
                params = arrayOf("type" to if (ref.type == MediaType.TV) "2" else "1", "guid" to "tmdb://${ref.tmdbId}"))
            return (body as? JsonObject)?.obj("MediaContainer")?.objects("Metadata")?.firstOrNull()?.str("slug")
        }

        /** The `resources` of an account: the servers among its devices, each with its addresses. */
        fun parseResources(body: JsonElement?): List<PlexServer> = (body as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { device ->
            if ("server" !in device.str("provides").orEmpty().split(',')) return@mapNotNull null
            val connections = device.objects("connections").mapNotNull { connection ->
                PlexConnection(connection.str("uri") ?: return@mapNotNull null, connection.bool("local") == true, connection.bool("relay") == true)
            }
            PlexServer(
                device.str("clientIdentifier") ?: return@mapNotNull null,
                device.str("name").orEmpty(),
                device.str("accessToken").orEmpty(),
                device.bool("owned") == true,
                connections,
            )
        }

        /** A library item: a movie or show by its TMDB id, or an episode or season of the show [showKey]. */
        data class Item(
            val ratingKey: String, val type: MediaType?, val tmdbId: Int?, val showKey: String?, val slug: String?, val addedAt: Long,
            /** Times a movie was played; a show's episodes, and how many of them were played. */
            val views: Int = 0, val episodes: Int = 0, val episodesViewed: Int = 0,
        ) {
            val ref: TitleRef? get() = if (type != null && tmdbId != null) TitleRef(type, tmdbId) else null

            val watched: WatchStatus? get() = when (type) {
                MediaType.MOVIE -> WatchStatus.Watched.takeIf { views > 0 }
                MediaType.TV -> WatchStatus.episodes(episodesViewed, episodes)
                null -> null
            }
        }

        /** The `Metadata` of a server answer. */
        fun parseItems(body: JsonElement?): List<Item> =
            (body as? JsonObject)?.obj("MediaContainer")?.objects("Metadata").orEmpty().mapNotNull { item ->
                val key = item.str("ratingKey") ?: return@mapNotNull null
                val kind = item.str("type")
                val type = when (kind) {
                    "movie" -> MediaType.MOVIE
                    "show" -> MediaType.TV
                    else -> null
                }
                val show = when (kind) {
                    "episode" -> item.str("grandparentRatingKey")
                    "season" -> item.str("parentRatingKey")
                    else -> null
                }
                Item(key, type, tmdbId(item).takeIf { type != null }, show, item.str("slug"), (item["addedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                    item.int("viewCount") ?: 0, item.int("leafCount") ?: 0, item.int("viewedLeafCount") ?: 0)
            }

        /**
         * The TMDB id of an item: in its `Guid` list ("tmdb://603", Plex's own agents), or in its `guid` with
         * the old TMDB agent ("com.plexapp.agents.themoviedb://603?lang=en").
         */
        private fun tmdbId(item: JsonObject): Int? =
            item.objects("Guid").firstNotNullOfOrNull { it.str("id")?.removePrefix("tmdb://")?.takeIf { id -> id.all(Char::isDigit) }?.toIntOrNull() }
                ?: item.str("guid")?.takeIf { it.startsWith("com.plexapp.agents.themoviedb://") }
                    ?.substringAfter("://")?.substringBefore('?')?.toIntOrNull()
    }
}

/**
 * The slugs of the titles' pages on watch.plex.tv, kept between sets: a slug doesn't change, and asking
 * Plex for a hundred of them on every update would be slow. Titles Plex has no page for are asked again.
 */
class PlexSlugs(context: android.content.Context) {
    private val file = java.io.File(context.filesDir, "plex_slugs.json")
    private val slugs: MutableMap<String, String> = runCatching {
        (Json.parseToJsonElement(file.readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun key(ref: TitleRef) = "${ref.type.path}/${ref.tmdbId}"

    operator fun get(ref: TitleRef): String? = synchronized(slugs) { slugs[key(ref)] }

    operator fun set(ref: TitleRef, slug: String) = synchronized(slugs) { slugs[key(ref)] = slug }

    /** Asks [find] for each of [refs], a few at a time; how many failed (no network, Plex down). */
    fun fetch(refs: List<TitleRef>, find: (TitleRef) -> String?): Int {
        if (refs.isEmpty()) return 0
        val pool = Executors.newFixedThreadPool(4)
        try {
            return refs.map { ref -> pool.submit(Callable { runCatching { find(ref)?.let { this[ref] = it } }.isFailure }) }
                .count { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    fun save() = runCatching {
        val body = synchronized(slugs) { JsonObject(slugs.mapValues { JsonPrimitive(it.value) }) }
        file.writeText(body.toString())
    }
}
