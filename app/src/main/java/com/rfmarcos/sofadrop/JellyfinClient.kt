package com.rfmarcos.sofadrop

import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** A Jellyfin server: its base [address] ("http://192.168.1.10:8096"), [id] and [name]. */
data class JellyfinServer(val address: String, val id: String, val name: String)

/** The server refused the user and password. */
class JellyfinLoginException : IOException("Wrong user or password")

/**
 * Jellyfin's API (10.9 or later), on the user's own server. Signing in gives a token for [Settings]; the
 * lists are read from the library, each title by the TMDB id Jellyfin matched it to (titles without one
 * are skipped). [address] is the server's base URL.
 */
class JellyfinClient(private val address: String, private val token: String = "", private val userId: String = "") {

    // --- Signing in ---

    /** Name and id of the server, which also proves the address is a Jellyfin server. */
    fun publicInfo(): JellyfinServer {
        val body = request("System/Info/Public") as? JsonObject ?: throw IOException("Not a Jellyfin server")
        val id = body.str("Id") ?: throw IOException("Not a Jellyfin server")
        return JellyfinServer(address, id, body.str("ServerName").orEmpty().ifBlank { address })
    }

    /** Quick Connect can be turned off on the server; then only the user and password work. */
    fun quickConnectEnabled(): Boolean = runCatching { request("QuickConnect/Enabled")?.toString() == "true" }.getOrDefault(false)

    data class QuickConnect(val secret: String, val code: String)

    fun initiateQuickConnect(): QuickConnect {
        val body = request("QuickConnect/Initiate", post = "") as? JsonObject ?: throw IOException("No Quick Connect code")
        return QuickConnect(body.str("Secret") ?: throw IOException("No Quick Connect code"), body.str("Code").orEmpty())
    }

    /** True once the code was approved on another device signed in to the server. */
    fun quickConnectApproved(secret: String): Boolean =
        (request("QuickConnect/Connect", "secret" to secret) as? JsonObject)?.bool("Authenticated") == true

    /** The session of an approved Quick Connect code. */
    fun authenticate(secret: String): Session =
        session(request("Users/AuthenticateWithQuickConnect", post = buildJsonObject { put("Secret", secret) }.toString()))

    fun authenticate(user: String, password: String): Session = try {
        session(request("Users/AuthenticateByName", post = buildJsonObject { put("Username", user); put("Pw", password) }.toString()))
    } catch (e: HttpException) {
        throw if (e.code == 401) JellyfinLoginException() else e
    }

    data class Session(val token: String, val userId: String, val userName: String)

    private fun session(body: JsonElement?): Session {
        val obj = body as? JsonObject
        val user = obj?.obj("User")
        return Session(
            obj?.str("AccessToken") ?: throw IOException("No access token"),
            user?.str("Id") ?: throw IOException("No user"),
            user.str("Name").orEmpty(),
        )
    }

    // --- Lists ---

    /** TMDB ids of a Jellyfin list key such as "jellyfin:latest_movies", best first. */
    fun titles(listKey: String, limit: Int): List<TitleRef> = when (listKey) {
        LATEST_MOVIES -> items("includeItemTypes" to "Movie", "sortBy" to "DateCreated", "sortOrder" to "Descending", "limit" to "$limit")
        // A series is new when an episode is: its last added content
        LATEST_SHOWS -> items("includeItemTypes" to "Series", "sortBy" to "DateLastContentAdded", "sortOrder" to "Descending", "limit" to "$limit")
        WATCHING -> watching(limit)
        FAVORITES -> items("includeItemTypes" to "Movie,Series", "isFavorite" to "true", "sortBy" to "DateCreated",
            "sortOrder" to "Descending", "limit" to "$limit")
        LIBRARY_MOVIES -> items("includeItemTypes" to "Movie", "sortBy" to "Random", "limit" to "$limit")
        LIBRARY_SHOWS -> items("includeItemTypes" to "Series", "sortBy" to "Random", "limit" to "$limit")
        else -> emptyList()
    }.mapNotNull { it.ref }.distinct().take(limit)

    /**
     * What Jellyfin's home shows to go on with: movies and episodes started (most recent first), then the
     * next episode of the series being watched; episodes count as their series.
     */
    private fun watching(limit: Int): List<Item> {
        val started = parseItems(request("UserItems/Resume", "userId" to userId, "limit" to "$limit", "fields" to "ProviderIds",
            "mediaTypes" to "Video", "enableImages" to "false"))
        val next = parseItems(request("Shows/NextUp", "userId" to userId, "limit" to "$limit", "fields" to "ProviderIds",
            "enableImages" to "false", "enableUserData" to "false"))
        val all = started + next
        val seriesIds = all.mapNotNull { it.seriesId }.distinct()
        val series = if (seriesIds.isEmpty()) emptyMap()
            else items("ids" to seriesIds.joinToString(",")).associateBy { it.id }
        return all.mapNotNull { item -> if (item.seriesId != null) series[item.seriesId] else item.takeIf { it.type == MediaType.MOVIE } }
    }

    /**
     * Every movie and series of the library with a TMDB id, to the Jellyfin item id the Jellyfin app
     * opens (see [TitleApp.JELLYFIN]).
     */
    fun libraryIndex(): Map<TitleRef, String> = items("includeItemTypes" to "Movie,Series").mapNotNull { item ->
        item.ref?.let { it to item.id }
    }.toMap()

    /**
     * What the user has watched: movies played, and series by how many of their episodes in the library
     * were played (all of them: watched).
     */
    fun watched(): Map<TitleRef, WatchStatus> {
        val movies = items("includeItemTypes" to "Movie", "isPlayed" to "true", "enableUserData" to "true").filter { it.played }
            .mapNotNull { it.ref }.associateWith { WatchStatus.Watched }
        val series = items("includeItemTypes" to "Series", "enableUserData" to "true", "fields" to "ProviderIds,RecursiveItemCount")
            .mapNotNull { item -> item.ref?.let { ref -> item.watched?.let { ref to it } } }
        return movies + series
    }

    /** Movies and series of the user's libraries; [params] override the defaults. */
    private fun items(vararg params: Pair<String, String>): List<Item> {
        val defaults = mapOf(
            "userId" to userId, "recursive" to "true", "fields" to "ProviderIds", "hasTmdbId" to "true",
            "enableImages" to "false", "enableUserData" to "false",
        )
        return parseItems(request("Items", *(defaults + params).toList().toTypedArray()))
    }

    // --- HTTP ---

    class HttpException(val code: Int, path: String) : IOException("Jellyfin HTTP $code for $path")

    /** GET, or POST with a JSON [post] body (empty for none). */
    private fun request(path: String, vararg params: Pair<String, String>, post: String? = null): JsonElement? {
        val url = "${address.trimEnd('/')}/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val builder = Request.Builder().url(url).header("Authorization", authorization(Settings.jellyfinDeviceId, token))
        if (post != null) builder.post(post.toRequestBody(JSON_TYPE))
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, path)
            val text = response.body?.string().orEmpty()
            return if (text.isBlank()) null else json.parseToJsonElement(text)
        }
    }

    companion object {
        const val PREFIX = "jellyfin:"
        const val LATEST_MOVIES = "jellyfin:latest_movies"
        const val LATEST_SHOWS = "jellyfin:latest_shows"
        const val WATCHING = "jellyfin:watching"
        const val FAVORITES = "jellyfin:favorites"
        const val LIBRARY_MOVIES = "jellyfin:library_movies"
        const val LIBRARY_SHOWS = "jellyfin:library_shows"

        private const val DEFAULT_PORT = 8096
        private const val DISCOVERY_PORT = 7359
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // A server at home that's off answers nothing: give up soon
        private val http = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** The connected server, or null. */
        fun fromSettings(): JellyfinClient? = if (!Settings.jellyfinConnected) null
            else JellyfinClient(Settings.jellyfinAddress, Settings.jellyfinToken, Settings.jellyfinUserId)

        /** Jellyfin's "MediaBrowser" header: which app and device asks, and the session's token once signed in. */
        fun authorization(deviceId: String, token: String): String {
            val device = (Build.MODEL ?: "Android TV").replace("\"", "")
            val fields = listOf(
                "Client" to "Sofadrop", "Device" to device, "DeviceId" to deviceId, "Version" to BuildConfig.VERSION_NAME,
            ) + listOfNotNull(("Token" to token).takeIf { token.isNotBlank() })
            return "MediaBrowser " + fields.joinToString(", ") { (name, value) -> "$name=\"$value\"" }
        }

        /**
         * Base URLs to try for what the user typed: as it is when it has a scheme, else HTTP on Jellyfin's
         * port (when none is given), then HTTPS and HTTP as typed (behind a proxy).
         */
        fun addressCandidates(typed: String): List<String> {
            val text = typed.trim().trimEnd('/')
            if (text.isEmpty()) return emptyList()
            if ("://" in text) return listOf(text)
            val portMatch = Regex(":(\\d+)(/.*)?$").find(text)
            return if (portMatch != null) {
                val port = portMatch.groupValues[1].toIntOrNull()
                if (port == 8920 || port == 443) listOf("https://$text", "http://$text")
                else listOf("http://$text", "https://$text")
            } else listOf("http://$text:$DEFAULT_PORT", "https://$text", "http://$text")
        }

        /** The first of [addressCandidates] that is a Jellyfin server. */
        fun find(typed: String): JellyfinServer {
            var error: Exception = IOException("No address")
            for (candidate in addressCandidates(typed)) {
                try {
                    return JellyfinClient(candidate).publicInfo()
                } catch (e: Exception) {
                    error = e
                }
            }
            throw error
        }

        /** Servers that answer Jellyfin's discovery broadcast on the local network within [timeoutMs]. */
        fun discover(timeoutMs: Int = 2500): List<JellyfinServer> {
            val found = linkedMapOf<String, JellyfinServer>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val message = "who is JellyfinServer?".toByteArray()
                socket.send(DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    parseDiscovery(String(packet.data, 0, packet.length))?.let { found.putIfAbsent(it.id, it) }
                }
            }
            return found.values.toList()
        }

        /** A discovery answer: `{"Address": "http://…:8096", "Id": "…", "Name": "…"}`. */
        fun parseDiscovery(text: String): JellyfinServer? = runCatching {
            val body = json.parseToJsonElement(text) as JsonObject
            JellyfinServer(body.str("Address")!!.trimEnd('/'), body.str("Id")!!, body.str("Name").orEmpty())
        }.getOrNull()

        /** A library item: a movie or a series by its TMDB id, or an episode of [seriesId]. */
        data class Item(
            val id: String, val type: MediaType?, val tmdbId: Int?, val seriesId: String?,
            /** With user data: played (a series, all its episodes), and a series' episodes and those not played. */
            val played: Boolean = false, val episodes: Int? = null, val unplayed: Int? = null,
        ) {
            val ref: TitleRef? get() = if (type != null && tmdbId != null) TitleRef(type, tmdbId) else null

            /** A series' progress (with RecursiveItemCount and user data). */
            val watched: WatchStatus? get() {
                if (played) return WatchStatus.Watched
                val total = episodes ?: return null
                return WatchStatus.episodes(total - (unplayed ?: total), total)
            }
        }

        /** The `Items` of a query result. */
        fun parseItems(body: JsonElement?): List<Item> = (body as? JsonObject)?.objects("Items").orEmpty().mapNotNull { item ->
            val id = item.str("Id") ?: return@mapNotNull null
            val kind = item.str("Type")
            val type = when (kind) {
                "Movie" -> MediaType.MOVIE
                "Series" -> MediaType.TV
                else -> null
            }
            // ProviderIds keys are written "Tmdb", but older libraries have other casings
            val tmdb = item.obj("ProviderIds")?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
                ?.let { (it.value as? JsonPrimitive)?.content?.toIntOrNull() }
            val user = item.obj("UserData")
            Item(id, type, tmdb.takeIf { type != null }, item.str("SeriesId").takeIf { kind == "Episode" },
                user?.bool("Played") == true, item.int("RecursiveItemCount"), user?.int("UnplayedItemCount"))
        }
    }
}
