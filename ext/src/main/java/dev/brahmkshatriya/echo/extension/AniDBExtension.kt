package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MemoryCache<K : Any, V : Any>(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val maxEntries: Int = 100,
) {
    private data class Entry<V>(val value: V, val timestamp: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
    }
}

class AniDBExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient {

    // The API host used by the working yuzono extension. The old anidb.app HTML site is no longer scraped.
    private val baseUrl = "https://anilab2.amdapi.click"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true // lets numeric JSON values decode into String fields
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val postCache = MemoryCache<Long, PostDto>(ttlMillis = 15 * 60 * 1000L, maxEntries = 400)
    private val homeCache = MemoryCache<String, HomeDto>(ttlMillis = 5 * 60 * 1000L, maxEntries = 1)
    private val fetchPermits = Semaphore(8)

    private var setting: Settings? = null

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun onInitialize() {}

    // ============================== Settings ==============================

    override suspend fun getSettingItems(): List<Setting> = mutableListOf(
        SettingList(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "Preferred video playback quality",
            entryTitles = mutableListOf("1080p", "720p", "360p", "Auto"),
            entryValues = mutableListOf("1080p", "720p", "360p", "Auto"),
            defaultEntryIndex = 0,
        ),
        SettingList(
            key = PREF_LANG_KEY,
            title = "Preferred Language",
            summary = "Preferred audio language, used to order servers",
            entryTitles = LANGUAGES.values.toMutableList(),
            entryValues = LANGUAGES.keys.toMutableList(),
            defaultEntryIndex = 0, // jpn
        ),
        SettingSwitch(
            key = PREF_FILLER_TAG_KEY,
            title = "Filler Detection",
            summary = "Adds '(Filler)' to episode names when available.",
            defaultValue = true,
        ),
        SettingSwitch(
            key = PREF_FILLER_HIDE_KEY,
            title = "Hide Filler Episodes",
            summary = "Hides detected filler episodes from the episode list.",
            defaultValue = false,
        ),
    )

    // ============================== Home Feed =============================

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val sections = getHome().sections.filter { it.posts.isNotEmpty() }
        val tabs = sections.map { Tab(it.name, it.name, false) } +
            Tab(LATEST_TAB, "Latest Updates", false)

        return Feed(tabs) { tab ->
            val id = tab?.id ?: tabs.first().id
            if (id == LATEST_TAB) {
                postsPaged { page -> "$baseUrl/api/latest?page=$page" }.toFeedData()
            } else {
                PagedData.Single<Shelf> {
                    sectionAlbums(id).map { it.toShelf() }
                }.toFeedData()
            }
        }
    }

    // ============================== Search Feed ===========================

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val url = query.toHttpUrlOrNull()
        if (url != null && url.host == LEGACY_HOST && url.pathSegments.firstOrNull() == "anime") {
            val album = fetchPost(url.encodedPath.toPostId()).toAlbum()
            return mutableListOf<Shelf>(album.toShelf()).toFeed()
        }

        if (query.isBlank()) {
            return postsPaged { page -> "$baseUrl/api/latest?page=$page" }.toFeed()
        }

        return postsPaged { page ->
            "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .build()
                .toString()
        }.toFeed()
    }

    // ============================ Quick Search ============================

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", "1")
            .build()
            .toString()
        val (albums, _) = loadPostsPage(url)
        return albums.take(8).map { QuickSearchItem.Media(it, false) }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}

    // ============================== Album Client ==========================

    override suspend fun loadAlbum(album: Album): Album =
        fetchPost(album.id.toPostId()).toAlbum()

    override suspend fun loadTracks(album: Album): Feed<Track> = PagedData.Single<Track> {
        val postId = album.id.toPostId()
        val episodes = json.decodeFromString<EpisodeListDto>(
            httpGet("$PLAY_URL/api/anime/$postId/episodes"),
        ).list

        val minEp = episodes.mapNotNull { it.number.toFloatOrNull() }.minOrNull() ?: 0f
        val offset = if (minEp > 1f) minEp - 1f else 0f

        val hideFiller = setting?.getBoolean(PREF_FILLER_HIDE_KEY) ?: false
        val showFillerTag = setting?.getBoolean(PREF_FILLER_TAG_KEY) ?: true

        episodes
            .filter { !hideFiller || !it.filler }
            .sortedBy { it.adjustedNumber(offset) }
            .map { ep ->
                val num = ep.adjustedNumber(offset)
                val label = num.toString().removeSuffix(".0")
                val title = "Episode $label" + if (ep.filler && showFillerTag) " (Filler)" else ""
                Track(
                    id = ep.id,
                    title = title,
                    type = Track.Type.Video,
                    cover = album.cover,
                    album = album,
                    artists = album.artists,
                    streamables = mutableListOf(
                        Streamable.server(
                            id = ep.id,
                            quality = 1080,
                            title = "AniDB",
                            extras = mapOf("episodeId" to ep.id),
                        ),
                    ),
                    extras = mapOf(
                        "postId" to postId.toString(),
                        "episodeId" to ep.id,
                        "number" to num.toString(),
                    ),
                )
            }
    }.toFeed()

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ============================== Track Client ==========================

    // One server per language: ids look like "<episodeId>/<lang>" and each is listed twice.
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val episodeId = track.extras["episodeId"] ?: track.id
        val langPref = setting?.getString(PREF_LANG_KEY) ?: PREF_LANG_DEFAULT

        val servers = json.decodeFromString<ServerListDto>(
            httpGet("$PLAY_URL/api/episode/$episodeId/servers"),
        ).list.distinctBy { it.id }

        val streamables = servers.mapIndexed { index, server ->
            val lang = server.id.substringAfterLast('/')
            Streamable.server(
                id = server.id,
                // Echo plays the highest quality server first, so rank the preferred language on top
                quality = if (lang == langPref) 2000 else 1000 - index,
                title = LANGUAGES[lang] ?: lang.uppercase(),
                extras = mapOf(
                    "episodeId" to episodeId,
                    "serverId" to server.id,
                ),
            )
        }

        if (streamables.isEmpty()) throw Exception("No servers available for this episode.")
        return track.copy(streamables = streamables)
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media {
        val serverId = streamable.extras["serverId"] ?: streamable.id

        val link = json.decodeFromString<IframeDto>(
            httpGet("$PLAY_URL/api/episode/$serverId/iframe", playHeaders),
        ).link

        val headers = mapOf("User-Agent" to USER_AGENT)
        val sources = buildSources(link, headers)
        return Streamable.Media.Server(sources = sortSources(sources), merged = false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ============================= Utilities ==============================

    private suspend fun buildSources(
        masterUrl: String,
        headers: Map<String, String>,
    ): List<Streamable.Source> {
        fun source(url: String, quality: Int, title: String) = Streamable.Source.Http(
            request = NetworkRequest(url = url, headers = headers),
            type = Streamable.SourceType.HLS,
            quality = quality,
            title = title,
            isVideo = true,
        )

        val content = try {
            httpGet(masterUrl)
        } catch (e: Exception) {
            return listOf(source(masterUrl, 1080, "Auto"))
        }

        if ("#EXT-X-STREAM-INF:" !in content) return listOf(source(masterUrl, 1080, "Auto"))

        val variants = HlsUtils.parseMasterPlaylist(masterUrl, content)
        val maxQuality = variants.maxOfOrNull { it.quality } ?: 1080
        return listOf(source(masterUrl, maxQuality, "Auto")) +
            variants.map { source(it.url, it.quality, it.resolutionLabel) }
    }

    private fun sortSources(sources: List<Streamable.Source>): List<Streamable.Source> {
        val pref = setting?.getString(PREF_QUALITY_KEY) ?: PREF_QUALITY_DEFAULT
        return sources.sortedWith(
            compareByDescending<Streamable.Source> { it.title == pref }
                .thenByDescending { it.quality },
        )
    }

    private suspend fun getHome(): HomeDto =
        homeCache.get("home") ?: json.decodeFromString<HomeDto>(httpGet("$baseUrl/api/home"))
            .also { homeCache.put("home", it) }

    private suspend fun sectionAlbums(name: String): List<Album> {
        val posts = getHome().sections.firstOrNull { it.name == name }?.posts.orEmpty()
        return posts.toAlbums()
    }

    private fun postsPaged(urlForPage: (Int) -> String): PagedData.Continuous<Shelf> =
        PagedData.Continuous { continuation ->
            val page = continuation?.toIntOrNull() ?: 1
            val (albums, hasNext) = loadPostsPage(urlForPage(page))
            Page(albums.map { it.toShelf() }, if (hasNext) (page + 1).toString() else null)
        }

    private suspend fun loadPostsPage(url: String): Pair<List<Album>, Boolean> {
        val posts = json.decodeFromString<PostListDto>(httpGet(url)).posts
        return Pair(posts.toAlbums(), posts.size >= PAGE_SIZE)
    }

    // List endpoints only return ids, so titles/posters come from each post
    private suspend fun List<PostItemDto>.toAlbums(): List<Album> = coroutineScope {
        map { item ->
            async(Dispatchers.IO) {
                fetchPermits.withPermit {
                    runCatching { fetchPost(item.id).toAlbum() }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchPost(id: Long): PostDto =
        postCache.get(id) ?: json.decodeFromString<PostDto>(httpGet("$baseUrl/api/post?id=$id"))
            .also { postCache.put(id, it) }

    private fun PostDto.toAlbum(): Album = Album(
        id = id.toString(),
        title = title,
        type = Album.Type.Show,
        cover = poster?.takeIf { it.isNotBlank() }?.toImageHolder(),
        description = buildDescription(),
    )

    // Old saved entries use "/anime/<slug>-<id>" where post id = id + 1e9
    private fun String.toPostId(): Long {
        val id = POST_ID_REGEX.find(trimEnd('/'))?.value?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid AniDB id: $this")
        return if (id < POST_ID_OFFSET) id + POST_ID_OFFSET else id
    }

    private val playHeaders = mapOf(
        "Referer" to "https://play.app/",
        "X-Requested-With" to "PLAY",
    )

    private suspend fun httpGet(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP error ${response.code} for URL: $url")
            response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        private const val PLAY_URL = "https://play.anidb.app"
        private const val LEGACY_HOST = "anidb.app"
        private const val LATEST_TAB = "__latest"
        private const val POST_ID_OFFSET = 1_000_000_000L
        private const val PAGE_SIZE = 30
        private val POST_ID_REGEX = Regex("""\d+$""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_DEFAULT = "jpn"

        private const val PREF_FILLER_TAG_KEY = "append_filler_tag"
        private const val PREF_FILLER_HIDE_KEY = "hide_filler"

        private val LANGUAGES = linkedMapOf(
            "jpn" to "Japanese",
            "eng" to "English",
            "chi" to "Chinese",
            "kor" to "Korean",
        )
    }
}
