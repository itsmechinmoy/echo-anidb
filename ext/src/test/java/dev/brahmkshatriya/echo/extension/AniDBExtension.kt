package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AniDBExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    ShareClient {

    val baseUrl = "https://anidb.app"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var setting: Settings? = null

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun onInitialize() {
        // Initialization if needed
    }

    // ============================== Settings ==============================

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                key = PREF_QUALITY_KEY,
                title = PREF_QUALITY_TITLE,
                summary = "Preferred video playback quality",
                entryTitles = PREF_QUALITY_ENTRIES,
                entryValues = PREF_QUALITY_ENTRIES,
                defaultEntryIndex = 0,
            ),
            SettingList(
                key = PREF_LANG_KEY,
                title = PREF_LANG_TITLE,
                summary = "Preferred audio language (Dub/Sub)",
                entryTitles = PREF_LANG_ENTRIES,
                entryValues = PREF_LANG_VALUES,
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
        )
    }

    // ============================== Home Feed =============================

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("order_top_airing", "Top Airing", false),
            Tab("order_trending", "Trending", false),
            Tab("order_updated", "Latest Updates", false),
            Tab("order_popular", "Most Popular", false),
            Tab("order_top", "Top Rated", false),
        )

        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                val sort = tab?.id ?: "order_top_airing"
                val url = "$baseUrl/browse?sort=$sort&page=$page"
                val (albums, hasNext) = loadBrowsePage(url)
                val shelves = albums.map { it.toShelf() }
                Page(shelves, if (hasNext) (page + 1).toString() else null)
            }
            pagedData.toFeedData()
        }
    }

    // ============================== Search Feed ===========================

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && (url.host == "anidb.app" || url.host == "anidb.net") && url.pathSegments.contains("anime")) {
                val album = fetchAnimeDetails(query)
                return listOf<Shelf>(album.toShelf()).toFeed()
            }
        }

        val pagedData = PagedData.Continuous<Shelf> { continuation ->
            val page = continuation?.toIntOrNull() ?: 1
            val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()

            if (query.isNotBlank()) {
                urlBuilder.addQueryParameter("q", query)
            } else {
                urlBuilder.addQueryParameter("sort", "order_top_airing")
            }
            urlBuilder.addQueryParameter("page", page.toString())

            val (albums, hasNext) = loadBrowsePage(urlBuilder.build().toString())
            val shelves = albums.map { it.toShelf() }
            Page(shelves, if (hasNext) (page + 1).toString() else null)
        }

        return pagedData.toFeed()
    }

    // ============================ Quick Search ============================

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", "1")
        val (albums, _) = loadBrowsePage(urlBuilder.build().toString())
        return albums.take(8).map { QuickSearchItem.Media(it, false) }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // No-op
    }

    // ============================== Album Client ==========================

    override suspend fun loadAlbum(album: Album): Album {
        val path = if (album.id.startsWith("http")) {
            album.id
        } else if (album.id.startsWith("/")) {
            "$baseUrl${album.id}"
        } else {
            "$baseUrl/anime/${album.id}"
        }
        return fetchAnimeDetails(path, album)
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val path = album.id
        val lastSegment = (if (path.startsWith("http")) path else "$baseUrl$path").toHttpUrl().pathSegments.last()
        val animeId = ANIME_ID_REGEX.find(lastSegment)?.groupValues?.get(1) ?: lastSegment

        val episodesJson = httpGet("$baseUrl/api/frontend/anime/$animeId/episodes")
        val episodesArr = json.decodeFromString<EpisodeResponseDto>(episodesJson).episodes

        val minEpNumber = episodesArr.minOfOrNull { it.number.toFloat() } ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        val hideFiller = setting?.getBoolean(PREF_FILLER_HIDE_KEY) ?: PREF_FILLER_HIDE_DEFAULT
        val showFillerTag = setting?.getBoolean(PREF_FILLER_TAG_KEY) ?: PREF_FILLER_TAG_DEFAULT

        val tracks = episodesArr
            .filter { !hideFiller || !it.filler }
            .map { ep ->
                val epTitle = ep.getFormattedTitle(offset, showFillerTag)
                val epNumber = ep.getAdjustedNumber(offset)
                val epId = ep.id.toString()
                Track(
                    id = epId,
                    title = epTitle,
                    type = Track.Type.Video,
                    cover = album.cover,
                    album = album,
                    artists = album.artists,
                    streamables = listOf(
                        Streamable(
                            id = epId,
                            title = "AniDB Stream",
                            quality = 1080,
                            type = Streamable.MediaType.Server,
                            extras = mapOf("episodeId" to epId),
                        )
                    ),
                    extras = mapOf(
                        "animeId" to animeId,
                        "episodeId" to epId,
                        "number" to epNumber.toString(),
                    ),
                )
            }

        return PagedData.Single<Track> { tracks }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val path = if (album.id.startsWith("http")) {
            album.id
        } else if (album.id.startsWith("/")) {
            "$baseUrl${album.id}"
        } else {
            "$baseUrl/anime/${album.id}"
        }

        val html = httpGet(path)
        val document = Jsoup.parse(html, baseUrl)
        val relatedAlbums = parseRelatedAnime(document)
        if (relatedAlbums.isEmpty()) return null

        val shelf = Shelf.Lists.Items(
            id = "related",
            title = "Related & Recommendations",
            list = relatedAlbums,
        )
        return listOf<Shelf>(shelf).toFeed()
    }

    // ============================== Track Client ==========================

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val epId = track.extras["episodeId"] ?: track.id
        return track.copy(
            streamables = listOf(
                Streamable(
                    id = epId,
                    title = "AniDB Stream",
                    quality = 1080,
                    type = Streamable.MediaType.Server,
                    extras = mapOf("episodeId" to epId),
                )
            )
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media {
        val episodeId = streamable.extras["episodeId"] ?: streamable.id
        val languagesJson = httpGet("$baseUrl/api/frontend/episode/$episodeId/languages")
        val languages = json.decodeFromString<LanguageResponseDto>(languagesJson).languages

        val sources = coroutineScope {
            languages.map { language ->
                async(Dispatchers.IO) {
                    try {
                        val embedHtml = httpGet(language.embedUrl)
                        val m3u8Url = M3U8_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return@async emptyList()
                        val masterContent = try {
                            httpGet(m3u8Url)
                        } catch (e: Exception) {
                            ""
                        }

                        val hlsStreams = if (masterContent.isNotEmpty()) {
                            HlsUtils.parseMasterPlaylist(m3u8Url, masterContent)
                        } else {
                            listOf(HlsStream(1080, "1080p", m3u8Url))
                        }

                        hlsStreams.map { stream ->
                            val streamTitle = "${language.name} - ${stream.resolutionLabel}"
                            Streamable.Source.Http(
                                request = NetworkRequest(
                                    url = stream.url,
                                    headers = mapOf(
                                        "Referer" to "$baseUrl/",
                                        "User-Agent" to USER_AGENT,
                                    ),
                                ),
                                type = Streamable.SourceType.HLS,
                                decryption = null,
                                quality = stream.quality,
                                title = streamTitle,
                                isVideo = true,
                                isLive = false,
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val sortedSources = sortSources(sources)
        return Streamable.Media.Server(sources = sortedSources, merged = false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ============================== Share Client ==========================

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Album -> {
                if (item.id.startsWith("http")) item.id
                else if (item.id.startsWith("/")) "$baseUrl${item.id}"
                else "$baseUrl/anime/${item.id}"
            }
            is Track -> {
                val animeId = item.extras["animeId"]
                if (animeId != null) "$baseUrl/anime/$animeId" else baseUrl
            }
            else -> baseUrl
        }
    }

    // ============================= Utilities ==============================

    private suspend fun loadBrowsePage(url: String): Pair<List<Album>, Boolean> {
        val html = httpGet(url)
        val document = Jsoup.parse(html, baseUrl)
        return parseBrowsePage(document)
    }

    private fun parseBrowsePage(document: Document): Pair<List<Album>, Boolean> {
        val animeMap = linkedMapOf<String, Album>()

        // Priority 1: Seasons carousel / row
        document.select("div.overflow-x-auto.snap-x a[href*=/anime/]").forEach { a ->
            val href = a.absUrl("href")
            val relativeUrl = a.attr("href")
            val id = if (relativeUrl.isNotEmpty()) relativeUrl else href
            if (id.isNotEmpty() && id !in animeMap) {
                val title = a.attr("title").takeIf { it.isNotEmpty() } ?: a.text()
                val coverUrl = a.selectFirst("img")?.absUrl("src")
                val cover = coverUrl?.toImageHolder()
                animeMap[id] = Album(
                    id = id,
                    title = title,
                    type = Album.Type.Show,
                    cover = cover,
                )
            }
        }

        // Priority 2: Standard anime grid
        document.select(".anime-grid a.anime-card").forEach { card ->
            val href = card.absUrl("href")
            val relativeUrl = card.attr("href")
            val id = if (relativeUrl.isNotEmpty()) relativeUrl else href
            if (id.isNotEmpty() && id !in animeMap) {
                val title = card.selectFirst("p.text-xs, .card-overlay p")?.text()
                    ?: card.attr("title")
                val coverUrl = card.selectFirst("img")?.absUrl("src")
                val cover = coverUrl?.toImageHolder()
                animeMap[id] = Album(
                    id = id,
                    title = title,
                    type = Album.Type.Show,
                    cover = cover,
                )
            }
        }

        val hasNextPage = document.select("a").any {
            it.text().contains("Next") && it.attr("href").contains("page=")
        }

        return Pair(animeMap.values.toList(), hasNextPage)
    }

    private suspend fun fetchAnimeDetails(url: String, baseAlbum: Album? = null): Album {
        val html = httpGet(url)
        val document = Jsoup.parse(html, baseUrl)
        val dl = document.selectFirst("dl.grid")

        val title = document.selectFirst("h1")?.text() ?: baseAlbum?.title ?: "Unknown Anime"
        val thumbnailUrl = document.selectFirst("img[src*=poster]")?.attr("abs:src")
        val cover = thumbnailUrl?.toImageHolder() ?: baseAlbum?.cover

        val altTitles = mutableListOf<String>()
        document.selectFirst("p.text-sm.text-muted.mb-3")?.text()?.takeIf { it.isNotEmpty() }?.let { altTitles.add(it) }
        dl?.selectFirst("dt:contains(Synonyms) + dd")?.text()?.takeIf { it.isNotEmpty() }?.let { syns ->
            syns.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { altTitles.add(it) }
        }

        val scoreText = dl?.selectFirst("dt:contains(Score) + dd")?.text()
        val scoreValue = scoreText?.toFloatOrNull()
        val stars = if (scoreValue != null) {
            val filled = (scoreValue / 2.0).roundToInt().coerceIn(0, 5)
            "★".repeat(filled) + "☆".repeat(5 - filled) + " $scoreValue"
        } else {
            null
        }

        val type = dl?.selectFirst("dt:contains(Type) + dd")?.text()
        val season = dl?.selectFirst("dt:contains(Season) + dd")?.text()
        val duration = dl?.selectFirst("dt:contains(Duration) + dd")?.text()
        val rating = dl?.selectFirst("dt:contains(Rating) + dd")?.text()
        val metaLine1 = listOfNotNull(
            type?.let { "**Type:** $it" },
            season?.let { "**Season:** $it" },
            duration?.let { "**Duration:** $it" },
            rating?.let { "**Rating:** $it" },
        ).joinToString(" | ")

        val airedRaw = dl?.selectFirst("dt:contains(Aired) + dd")?.text()
        val metaLine2 = if (airedRaw != null) {
            val parts = airedRaw.split(Regex("\\s*[–—-]\\s*"))
            val dateAired = parts.getOrNull(0)?.trim()
            val dateEnded = parts.getOrNull(1)?.trim()
            buildString {
                if (dateAired != null) append("**Date Aired:** $dateAired")
                if (dateEnded != null) {
                    if (isNotEmpty()) append("\n")
                    append("**Date Ended:** $dateEnded")
                }
            }
        } else {
            ""
        }

        val trailerUrl = document.selectFirst("a:contains(Trailer)")?.absUrl("href")
        val synopsis = document.select("h2:contains(Synopsis) + div p")
            .joinToString("\n\n") { it.text() }

        val allowedDomains = listOf("myanimelist.net", "anilist.co", "anidb.net", "kitsu.app")
        val links = document.select("div[class*='gap-2'].mb-4 a[target=_blank]")
            .filter { a ->
                val href = a.attr("href").lowercase()
                allowedDomains.any { domain -> href.contains(domain) }
            }
            .joinToString(" | ") { a -> "[${a.text()}](${a.absUrl("href")})" }

        val fullDescription = buildString {
            if (stars != null) {
                append("$stars\n\n")
            }
            if (synopsis.isNotEmpty()) {
                append(synopsis)
            }
            if (metaLine1.isNotEmpty()) {
                append("\n\n$metaLine1")
            }
            if (metaLine2.isNotEmpty()) {
                append("\n$metaLine2")
            }
            if (altTitles.isNotEmpty()) {
                append("\n\n**Alternative Titles:**\n")
                altTitles.distinct().forEach { append("- $it\n") }
            }
            if (links.isNotEmpty()) {
                append("\n\n**Links:** $links")
            }
            if (!trailerUrl.isNullOrEmpty()) {
                append("\n\n[Trailer]($trailerUrl)")
            }
        }.trim()

        val studioName = dl?.selectFirst("dt:contains(Studios) + dd a, dt:contains(Studio) + dd a")?.text()
        val artists = listOfNotNull(studioName?.let { Artist(id = it, name = it, cover = null) })

        val id = baseAlbum?.id ?: (url.toHttpUrlOrNull()?.encodedPath ?: url)

        return Album(
            id = id,
            title = title,
            type = Album.Type.Show,
            cover = cover,
            artists = artists,
            description = fullDescription,
        )
    }

    private fun parseRelatedAnime(document: Document): List<Album> {
        val list = mutableListOf<Album>()
        document.select(".anime-grid a.anime-card").forEach { card ->
            val href = card.attr("href")
            if (href.isNotEmpty()) {
                val title = card.selectFirst("p.text-xs, .card-overlay p")?.text()
                    ?: card.attr("title")
                val coverUrl = card.selectFirst("img")?.absUrl("src")
                val cover = coverUrl?.toImageHolder()
                list.add(
                    Album(
                        id = href,
                        title = title,
                        type = Album.Type.Show,
                        cover = cover,
                    )
                )
            }
        }
        return list.distinctBy { it.id }
    }

    private fun sortSources(sources: List<Streamable.Source>): List<Streamable.Source> {
        val qualityPref = setting?.getString(PREF_QUALITY_KEY) ?: PREF_QUALITY_DEFAULT
        val langPref = setting?.getString(PREF_LANG_KEY) ?: PREF_LANG_DEFAULT

        val primaryLang = if (langPref == "eng") "English" else "Japanese"
        val secondaryLang = if (langPref == "eng") "Japanese" else "English"

        val qualityOrder = listOfNotNull(
            qualityPref,
            "1080p".takeIf { it != qualityPref },
            "720p".takeIf { it != qualityPref },
            "480p",
            "360p".takeIf { it != qualityPref },
        )

        val langOrder = listOf(primaryLang, secondaryLang)

        val idealOrder = qualityOrder.flatMap { res ->
            langOrder.map { lang -> "$lang - $res" }
        }

        return sources.sortedBy { src ->
            val title = src.title ?: ""
            val idx = idealOrder.indexOfFirst { title.startsWith(it) }
            if (idx != -1) idx else Int.MAX_VALUE
        }
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error ${response.code} for URL: $url")
            }
            response.body?.string() ?: ""
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred Language"
        private const val PREF_LANG_DEFAULT = "jpn"
        private val PREF_LANG_ENTRIES = listOf("Japanese", "English")
        private val PREF_LANG_VALUES = listOf("jpn", "eng")

        private const val PREF_FILLER_TAG_KEY = "append_filler_tag"
        private const val PREF_FILLER_TAG_TITLE = "Filler Detection"
        private const val PREF_FILLER_TAG_DEFAULT = true

        private const val PREF_FILLER_HIDE_KEY = "hide_filler"
        private const val PREF_FILLER_HIDE_TITLE = "Hide Filler Episodes"
        private const val PREF_FILLER_HIDE_DEFAULT = false

        private val ANIME_ID_REGEX = Regex("-(\\d+)$")
        private val M3U8_REGEX = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")
    }
}
