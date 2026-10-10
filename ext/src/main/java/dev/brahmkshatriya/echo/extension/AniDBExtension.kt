package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackChapterClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Chapter
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MemoryCache<K : Any, V : Any>(
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val maxEntries: Int = 150
) {
    private data class Entry<V>(val value: V, val timestamp: Long)
    private val map = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
            return size > maxEntries
        }
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

    @Synchronized
    fun clear() {
        map.clear()
    }
}

class AniDBExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    TrackChapterClient,
    LyricsClient,
    ShareClient {

    val baseUrl = "https://anilab2.amdapi.click"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(20, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val postCache = MemoryCache<Long, PostDto>(ttlMillis = 30 * 60 * 1000L, maxEntries = 300)
    private val homeCache = MemoryCache<String, HomeDto>(ttlMillis = 10 * 60 * 1000L, maxEntries = 5)
    private val episodesCache = MemoryCache<Long, List<EpisodeDto>>(ttlMillis = 15 * 60 * 1000L, maxEntries = 100)
    private val streamSourcesCache = MemoryCache<String, List<Streamable.Source>>(ttlMillis = 15 * 60 * 1000L, maxEntries = 100)
    private val aniZipCache = MemoryCache<String, AniZipResponseDto>(ttlMillis = 30 * 60 * 1000L, maxEntries = 100)
    private val chaptersCache = MemoryCache<String, List<Chapter>>(ttlMillis = 30 * 60 * 1000L, maxEntries = 100)

    private var setting: Settings? = null

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun onInitialize() {
        // Initialization if needed
    }

    // ============================== Settings ==============================

    override suspend fun getSettingItems(): List<Setting> {
        return mutableListOf(
            SettingList(
                key = PREF_QUALITY_KEY,
                title = PREF_QUALITY_TITLE,
                summary = "Preferred video playback quality",
                entryTitles = mutableListOf("1080p", "720p", "480p", "360p", "Auto"),
                entryValues = mutableListOf("1080p", "720p", "480p", "360p", "Auto"),
                defaultEntryIndex = 0,
            ),
            SettingList(
                key = PREF_LANG_KEY,
                title = PREF_LANG_TITLE,
                summary = "Preferred audio language (Sub / Dub)",
                entryTitles = PREF_LANG_ENTRIES.toMutableList(),
                entryValues = PREF_LANG_VALUES.toMutableList(),
                defaultEntryIndex = 0,
            ),
            SettingSwitch(
                key = PREF_FILLER_TAG_KEY,
                title = PREF_FILLER_TAG_TITLE,
                summary = "Adds '(Filler)' to episode names when available.",
                defaultValue = PREF_FILLER_TAG_DEFAULT,
            ),
            SettingSwitch(
                key = PREF_FILLER_HIDE_KEY,
                title = PREF_FILLER_HIDE_TITLE,
                summary = "Hides detected filler episodes from episode list.",
                defaultValue = PREF_FILLER_HIDE_DEFAULT,
            ),
            SettingSwitch(
                key = PREF_AUTOSKIP_KEY,
                title = PREF_AUTOSKIP_TITLE,
                summary = "Automatically skip Opening and Ending segments without asking.",
                defaultValue = PREF_AUTOSKIP_DEFAULT,
            ),
        )
    }

    // ============================== Home Feed =============================

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val homeDto = fetchHomeData()
        val tabs = homeDto.sections.map { section ->
            Tab(section.name, section.name, false)
        }.toMutableList()

        if (tabs.isEmpty()) {
            tabs.add(Tab(POPULAR_SECTION, POPULAR_SECTION, false))
        }

        return Feed(tabs) { tab ->
            val tabName = tab?.id ?: POPULAR_SECTION
            if (tabName == "Latest Updates") {
                val pagedData = PagedData.Continuous<Shelf> { continuation ->
                    val page = continuation?.toIntOrNull() ?: 1
                    val url = "$baseUrl/api/latest?page=$page"
                    val body = httpGet(url)
                    val listDto = json.decodeFromString<PostListDto>(body)
                    val posts = listDto.posts.parallelCatchingMapNotNull { fetchPost(it.id) }
                    val shelves: List<Shelf> = posts.map { it.toAlbum().toShelf() }
                    val nextPage = if (listDto.posts.size >= PAGE_SIZE) (page + 1).toString() else null
                    Page(shelves, nextPage)
                }
                pagedData.toFeedData()
            } else {
                val section = homeDto.sections.firstOrNull { it.name == tabName }
                    ?: homeDto.sections.firstOrNull()
                val posts = section?.posts.orEmpty().parallelCatchingMapNotNull { fetchPost(it.id) }
                val shelves: List<Shelf> = posts.map { it.toAlbum().toShelf() }
                PagedData.Single<Shelf> { shelves }.toFeedData()
            }
        }
    }

    private suspend fun fetchHomeData(): HomeDto {
        val cached = homeCache.get("home")
        if (cached != null) return cached
        return try {
            val body = httpGet("$baseUrl/api/home")
            val parsed = json.decodeFromString<HomeDto>(body)
            homeCache.put("home", parsed)
            parsed
        } catch (_: Exception) {
            HomeDto()
        }
    }

    // ============================== Search Feed ===========================

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val cleanQuery = query.trim()

        if (cleanQuery.startsWith("http://") || cleanQuery.startsWith("https://")) {
            val url = cleanQuery.toHttpUrlOrNull()
            if (url != null && (url.host == LEGACY_HOST || url.host.contains("anidb")) && url.pathSegments.contains("anime")) {
                val postId = cleanQuery.toPostId()
                val post = fetchPost(postId)
                return mutableListOf<Shelf>(post.toAlbum().toShelf()).toFeed()
            }
        }

        if (cleanQuery.isBlank()) {
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                val url = "$baseUrl/api/latest?page=$page"
                val body = httpGet(url)
                val listDto = json.decodeFromString<PostListDto>(body)
                val posts = listDto.posts.parallelCatchingMapNotNull { fetchPost(it.id) }
                val shelves = posts.map { it.toAlbum().toShelf() }
                val nextPage = if (listDto.posts.size >= PAGE_SIZE) (page + 1).toString() else null
                Page(shelves, nextPage)
            }
            return pagedData.toFeed()
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val pagedData = PagedData.Continuous<Shelf> { continuation ->
            val page = continuation?.toIntOrNull() ?: 1
            val url = "$baseUrl/api/search?query=$encoded&page=$page"
            val body = httpGet(url)
            val listDto = json.decodeFromString<PostListDto>(body)
            val posts = listDto.posts.parallelCatchingMapNotNull { fetchPost(it.id) }
            val shelves = posts.map { it.toAlbum().toShelf() }
            val nextPage = if (listDto.posts.size >= PAGE_SIZE) (page + 1).toString() else null
            Page(shelves, nextPage)
        }

        return pagedData.toFeed()
    }

    // ============================ Quick Search ============================

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val url = "$baseUrl/api/search?query=$encoded&page=1"
                val body = httpGet(url)
                val listDto = json.decodeFromString<PostListDto>(body)
                val posts = listDto.posts.take(8).parallelCatchingMapNotNull { fetchPost(it.id) }
                posts.map { QuickSearchItem.Media(it.toAlbum(), false) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // No-op
    }

    // ============================== Album Client ==========================

    override suspend fun loadAlbum(album: Album): Album {
        val postId = album.id.toPostId()
        val post = fetchPost(postId)
        return post.toAlbum(album)
    }

    override suspend fun loadTracks(album: Album): Feed<Track> = PagedData.Single<Track> {
        val postId = album.id.toPostId()

        val episodes = episodesCache.get(postId) ?: run {
            val url = "$PLAY_URL/api/anime/$postId/episodes"
            val body = httpGet(url, headers = playHeaders)
            val parsed = json.decodeFromString<EpisodeListDto>(body).list
            episodesCache.put(postId, parsed)
            parsed
        }

        val minEpNumber = episodes.mapNotNull { it.number.toFloatOrNull() }.minOrNull() ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        val hideFiller = setting?.getBoolean(PREF_FILLER_HIDE_KEY) ?: PREF_FILLER_HIDE_DEFAULT
        val showFillerTag = setting?.getBoolean(PREF_FILLER_TAG_KEY) ?: PREF_FILLER_TAG_DEFAULT

        episodes
            .filter { !hideFiller || !it.filler }
            .map { ep ->
                val adjustedNum = ep.getAdjustedNumber(offset)
                val title = ep.getFormattedTitle(offset, showFillerTag)

                Track(
                    id = "${postId}_${ep.id}",
                    title = title,
                    type = Track.Type.Video,
                    album = album,
                    cover = album.cover,
                    artists = emptyList(),
                    extras = mapOf(
                        "postId" to postId.toString(),
                        "episodeId" to ep.id,
                        "number" to adjustedNum.toString(),
                        "animeTitle" to album.title,
                    )
                )
            }
    }.toFeed()

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return withContext(Dispatchers.IO) {
            val postId = album.id.toPostId()
            val post = try {
                fetchPost(postId)
            } catch (_: Exception) {
                return@withContext null
            }

            val shelves = mutableListOf<Shelf>()

            // 1. Seasons / Franchise
            if (post.seasons.isNotEmpty()) {
                val seasonAlbums = post.seasons.parallelCatchingMapNotNull { season ->
                    val seasonPost = fetchPost(season.id)
                    val relTitle = season.title?.takeIf { it.isNotBlank() } ?: "Related"
                    seasonPost.toAlbum().copy(subtitle = relTitle)
                }
                if (seasonAlbums.isNotEmpty()) {
                    shelves.add(
                        Shelf.Lists.Items(
                            id = "seasons",
                            title = "Seasons & Franchise",
                            list = seasonAlbums
                        )
                    )
                }
            }

            // 2. Similar Anime
            if (post.similar.isNotEmpty()) {
                val similarAlbums = post.similar.parallelCatchingMapNotNull { sim ->
                    val simPost = fetchPost(sim.id)
                    simPost.toAlbum()
                }
                if (similarAlbums.isNotEmpty()) {
                    shelves.add(
                        Shelf.Lists.Items(
                            id = "similar",
                            title = "Similar Anime",
                            list = similarAlbums
                        )
                    )
                }
            }

            if (shelves.isEmpty()) null else shelves.toFeed()
        }
    }

    // ============================== Track Client ==========================

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return withContext(Dispatchers.IO) {
            val episodeId = track.extras["episodeId"] ?: track.id.substringAfter("_")
            val postId = track.extras["postId"] ?: track.id.substringBefore("_")

            val streamables = mutableListOf<Streamable>()

            try {
                val url = "$PLAY_URL/api/episode/$episodeId/servers"
                val body = httpGet(url, headers = playHeaders)
                val servers = json.decodeFromString<ServerListDto>(body).list.distinctBy { it.id }

                val langPref = setting?.getString(PREF_LANG_KEY) ?: PREF_LANG_DEFAULT

                val sortedServers = servers.sortedByDescending { server ->
                    val langCode = server.id.substringAfterLast('/')
                    if (langCode == langPref) 2 else if (langCode == "jpn") 1 else 0
                }

                sortedServers.forEachIndexed { index, server ->
                    val langCode = server.id.substringAfterLast('/')
                    val langName = LANGUAGES[langCode] ?: server.name ?: langCode.uppercase()
                    val quality = 1080 - index

                    streamables.add(
                        Streamable.server(
                            id = "server_${episodeId}_$langCode",
                            quality = quality,
                            title = "$langName (Audio)",
                            extras = mapOf(
                                "postId" to postId,
                                "episodeId" to episodeId,
                                "serverId" to server.id,
                                "langCode" to langCode,
                                "langName" to langName,
                            )
                        )
                    )
                }
            } catch (_: Exception) {
            }

            if (streamables.isEmpty()) {
                streamables.add(
                    Streamable.server(
                        id = "server_${episodeId}_jpn",
                        quality = 1080,
                        title = "Japanese (Audio)",
                        extras = mapOf(
                            "postId" to postId,
                            "episodeId" to episodeId,
                            "serverId" to "$episodeId/jpn",
                            "langCode" to "jpn",
                            "langName" to "Japanese",
                        )
                    )
                )
            }

            track.copy(streamables = streamables)
        }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media {
        return withContext(Dispatchers.IO) {
            val serverId = streamable.extras["serverId"]
                ?: streamable.id.removePrefix("server_")
            val langName = streamable.extras["langName"] ?: "Video"

            val cached = streamSourcesCache.get(serverId)
            if (!cached.isNullOrEmpty()) {
                return@withContext Streamable.Media.Server(sources = cached, merged = false)
            }

            val url = "$PLAY_URL/api/episode/$serverId/iframe"
            val body = httpGet(url, headers = playHeaders)
            val iframeDto = json.decodeFromString<IframeDto>(body)
            val masterUrl = iframeDto.link

            val sources = extractHlsSources(masterUrl, langName)
            val sortedSources = sortSources(sources)
            streamSourcesCache.put(serverId, sortedSources)
            Streamable.Media.Server(sources = sortedSources, merged = false)
        }
    }

    private suspend fun extractHlsSources(
        masterUrl: String,
        languageName: String,
    ): List<Streamable.Source> {
        val streamHeaders = mapOf(
            "Referer" to "https://play.app/",
            "Origin" to "https://play.app",
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*"
        )

        val masterContent = try {
            httpGet(masterUrl, headers = playHeaders)
        } catch (_: Exception) {
            return listOf(
                Streamable.Source.Http(
                    request = NetworkRequest(url = masterUrl, headers = streamHeaders),
                    type = if (masterUrl.contains(".m3u8")) Streamable.SourceType.HLS else Streamable.SourceType.Progressive,
                    quality = 1080,
                    title = "$languageName - Auto",
                    isVideo = true
                )
            )
        }

        if ("#EXT-X-STREAM-INF:" !in masterContent) {
            return listOf(
                Streamable.Source.Http(
                    request = NetworkRequest(url = masterUrl, headers = streamHeaders),
                    type = if (masterUrl.contains(".m3u8")) Streamable.SourceType.HLS else Streamable.SourceType.Progressive,
                    quality = 1080,
                    title = "$languageName - Stream",
                    isVideo = true
                )
            )
        }

        val sources = mutableListOf<Streamable.Source>()

        // Add Master Auto Stream
        sources.add(
            Streamable.Source.Http(
                request = NetworkRequest(url = masterUrl, headers = streamHeaders),
                type = Streamable.SourceType.HLS,
                quality = 1080,
                title = "$languageName - Auto",
                isVideo = true
            )
        )

        val entries = masterContent.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
        for (entry in entries) {
            val lines = entry.trim().lines()
            val streamInf = lines.firstOrNull() ?: continue
            val urlLine = lines.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue

            val streamUrl = if (urlLine.startsWith("http://") || urlLine.startsWith("https://")) {
                urlLine
            } else {
                val baseUri = masterUrl.substringBeforeLast("/")
                "$baseUri/$urlLine"
            }

            val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(streamInf)
            val height = resMatch?.groupValues?.get(2)?.toIntOrNull()
            val qualityName = if (height != null) "${height}p" else "Variant"

            sources.add(
                Streamable.Source.Http(
                    request = NetworkRequest(url = streamUrl, headers = streamHeaders),
                    type = Streamable.SourceType.HLS,
                    quality = height ?: 1080,
                    title = "$languageName - $qualityName",
                    isVideo = true
                )
            )
        }

        return sources.distinctBy { it.id }
    }

    private fun sortSources(sources: List<Streamable.Source>): List<Streamable.Source> {
        val prefQuality = setting?.getString(PREF_QUALITY_KEY) ?: PREF_QUALITY_DEFAULT
        val idealOrder = when (prefQuality) {
            "1080p" -> listOf("1080p", "Auto", "720p", "480p", "360p")
            "720p" -> listOf("720p", "1080p", "Auto", "480p", "360p")
            "480p" -> listOf("480p", "720p", "360p", "1080p", "Auto")
            "360p" -> listOf("360p", "480p", "720p", "Auto", "1080p")
            else -> listOf("Auto", "1080p", "720p", "480p", "360p")
        }

        return sources.sortedBy { src ->
            val title = src.title?.substringAfter(" - ") ?: ""
            val idx = idealOrder.indexOfFirst { title.startsWith(it, ignoreCase = true) }
            if (idx != -1) idx else Int.MAX_VALUE
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // =========================== Chapter Client ===========================

    override suspend fun getChapters(track: Track): List<Chapter> {
        val postId = track.extras["postId"] ?: return emptyList()
        val epNumberStr = track.extras["number"]?.toFloatOrNull()?.toInt()?.toString() ?: return emptyList()
        val cacheKey = "$postId:$epNumberStr"

        val cached = chaptersCache.get(cacheKey)
        if (cached != null) return cached

        val autoSkip = setting?.getBoolean(PREF_AUTOSKIP_KEY) ?: PREF_AUTOSKIP_DEFAULT

        val anilistId = track.album?.extras?.get("anilist_id")
        val malId = track.album?.extras?.get("mal_id")

        val aniSkipUrl = when {
            malId != null -> "https://api.aniskip.com/v2/skip-times/$malId/$epNumberStr?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap&episodeLength=0"
            anilistId != null -> "https://api.aniskip.com/v2/skip-times/$anilistId/$epNumberStr?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap&episodeLength=0"
            else -> null
        }

        val chapters = if (aniSkipUrl != null) {
            try {
                val jsonStr = httpGet(aniSkipUrl)
                val parsed = json.decodeFromString<AniSkipResponseDto>(jsonStr)
                if (parsed.found && parsed.results.isNotEmpty()) {
                    parsed.results.mapNotNull { result ->
                        val start = (result.interval.startTime * 1000).toLong()
                        val end = (result.interval.endTime * 1000).toLong()
                        if (start >= end) return@mapNotNull null

                        val title = when (result.skipType.lowercase()) {
                            "op", "mixed-op" -> "Opening"
                            "ed", "mixed-ed" -> "Ending"
                            "recap" -> "Recap"
                            else -> result.skipType.replaceFirstChar { it.uppercase() }
                        }

                        Chapter(
                            name = title,
                            startTime = start,
                            endTime = end,
                            skipType = if (autoSkip) Chapter.SkipType.SKIP else Chapter.SkipType.ASK
                        )
                    }
                } else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        if (chapters.isNotEmpty()) {
            chaptersCache.put(cacheKey, chapters)
        }
        return chapters
    }

    // ============================ Lyrics Client ===========================

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val episodeNum = track.extras["number"]?.removeSuffix(".0") ?: ""
        val animeTitle = track.extras["animeTitle"] ?: track.album?.title ?: "Anime"
        val title = if (episodeNum.isNotBlank()) "$animeTitle - Episode $episodeNum" else animeTitle

        val chapters = getChapters(track)
        val chaptersDesc = if (chapters.isNotEmpty()) {
            chapters.joinToString("\n") { ch ->
                val startSec = ch.startTime / 1000
                val endSec = (ch.endTime ?: ch.startTime) / 1000
                val startFormatted = String.format("%02d:%02d", startSec / 60, startSec % 60)
                val endFormatted = String.format("%02d:%02d", endSec / 60, endSec % 60)
                "• ${ch.name}: $startFormatted - $endFormatted"
            }
        } else null

        val synopsis = track.description ?: track.album?.description

        val text = buildString {
            if (!synopsis.isNullOrBlank()) {
                append("SYNOPSIS\n")
                append(synopsis)
            }
            if (!chaptersDesc.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("EPISODE CHAPTERS\n")
                append(chaptersDesc)
            }
        }.trim()

        if (text.isEmpty()) return emptyList<Lyrics>().toFeed()

        val lyric = Lyrics(
            id = "info_${track.id}",
            title = title,
            subtitle = "Overview & Timestamps",
            lyrics = Lyrics.Simple(text)
        )
        return listOf(lyric).toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    // ============================ Share Client ============================

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Album -> {
                val postId = item.id.toPostId()
                "https://anidb.app/anime/$postId"
            }
            is Track -> {
                val postId = item.extras["postId"] ?: item.id.substringBefore("_")
                val epId = item.extras["episodeId"] ?: item.id.substringAfter("_")
                "https://anidb.app/anime/$postId?ep=$epId"
            }
            else -> "https://anidb.app"
        }
    }

    // ============================= Utilities ==============================

    private suspend fun fetchPost(id: Long): PostDto {
        val cached = postCache.get(id)
        if (cached != null) return cached
        val url = "$baseUrl/api/post?id=$id"
        val body = httpGet(url)
        val post = json.decodeFromString<PostDto>(body)
        postCache.put(id, post)
        return post
    }

    private fun PostDto.toAlbum(baseAlbum: Album? = null): Album {
        return Album(
            id = id.toString(),
            title = title,
            type = Album.Type.Show,
            cover = poster?.toProxiedImageHolder() ?: baseAlbum?.cover,
            description = buildDescription(),
            extras = mapOf(
                "postId" to id.toString(),
            )
        )
    }

    private fun String?.toProxiedImageHolder(): ImageHolder? {
        if (this.isNullOrBlank()) return null
        val fullUrl = if (startsWith("http")) this else if (startsWith("//")) "https:$this" else "$baseUrl$this"
        val proxied = "https://wsrv.nl/?url=$fullUrl"
        return proxied.toImageHolder()
    }

    private fun String.toPostId(): Long {
        val clean = this.trimEnd('/')
        val lastSegment = clean.substringAfterLast('/')
        val id = POST_ID_REGEX.find(lastSegment)?.value?.toLongOrNull()
            ?: clean.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid AniDB ID: $this")
        return if (id < POST_ID_OFFSET) id + POST_ID_OFFSET else id
    }

    private suspend fun <T, R> Iterable<T>.parallelCatchingMapNotNull(
        transform: suspend (T) -> R?
    ): List<R> = coroutineScope {
        map { item ->
            async(Dispatchers.IO) {
                try {
                    transform(item)
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun httpGet(url: String, headers: Headers = defaultHeaders): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} for URL: $url")
            }
            response.body?.string() ?: ""
        }
    }

    companion object {
        private const val PLAY_URL = "https://play.anidb.app"
        private const val LEGACY_HOST = "anidb.app"
        private const val POPULAR_SECTION = "Most Popular"
        private const val POST_ID_OFFSET = 1_000_000_000L
        private const val PAGE_SIZE = 30

        private val POST_ID_REGEX = Regex("""\d+$""")

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val defaultHeaders: Headers by lazy {
            Headers.Builder()
                .add("User-Agent", USER_AGENT)
                .add("Accept", "application/json, text/plain, */*")
                .build()
        }

        private val playHeaders: Headers by lazy {
            Headers.Builder()
                .add("User-Agent", USER_AGENT)
                .add("Referer", "https://play.app/")
                .add("X-Requested-With", "PLAY")
                .add("Accept", "application/json, text/plain, */*")
                .build()
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred Language"
        private const val PREF_LANG_DEFAULT = "jpn"

        private val LANGUAGES = mapOf(
            "jpn" to "Japanese",
            "eng" to "English",
            "chi" to "Chinese",
            "kor" to "Korean",
        )
        private val PREF_LANG_ENTRIES = LANGUAGES.values.toList()
        private val PREF_LANG_VALUES = LANGUAGES.keys.toList()

        private const val PREF_FILLER_TAG_KEY = "append_filler_tag"
        private const val PREF_FILLER_TAG_TITLE = "Filler Detection"
        private const val PREF_FILLER_TAG_DEFAULT = true

        private const val PREF_FILLER_HIDE_KEY = "hide_filler"
        private const val PREF_FILLER_HIDE_TITLE = "Hide Filler Episodes"
        private const val PREF_FILLER_HIDE_DEFAULT = false

        private const val PREF_AUTOSKIP_KEY = "autoskip_op_ed"
        private const val PREF_AUTOSKIP_TITLE = "Auto-Skip Opening/Ending"
        private const val PREF_AUTOSKIP_DEFAULT = false
    }
}
