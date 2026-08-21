package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = AniDBExtension()
    private val searchQuery = "One Piece"
    private val user = User("", "Test User")

    @Test
    fun testEmptySearch() = testIn("Testing Empty Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val search = extension.loadSearchFeed("").pagedDataOfFirst().loadPage(null).data
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Searching  : $searchQuery")
        val feed = extension.loadSearchFeed(searchQuery)
        println("Tabs : ${feed.tabs}")
        val items = feed.pagedDataOfFirst().loadPage(null).data
        items.forEach {
            println(it)
        }
        assert(items.isNotEmpty()) { "Search returned no items" }
    }

    @Test
    fun testQuickSearch() = testIn("Testing Quick Search") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val results = extension.quickSearch(searchQuery)
        println("Quick search results: ${results.size}")
        results.forEach { println(it) }
        assert(results.isNotEmpty()) { "Quick search returned no items" }
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        println("Tabs : ${feed.tabs}")
        assert(feed.tabs.isNotEmpty()) { "Home feed has no tabs" }
        feed.tabs.forEach { tab ->
            println("Tab: ${tab.title} (${tab.id})")
            val items = feed.getPagedData.invoke(tab).pagedData.loadPage(null).data
            println("  Items count: ${items.size}")
            items.take(3).forEach { println("    $it") }
            assert(items.isNotEmpty()) { "Tab ${tab.title} had no items" }
        }
    }

    private suspend fun searchAlbum(q: String? = null): Album {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = q ?: searchQuery
        println("Searching Album : $query")
        val album = extension.loadSearchFeed(query).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Album
                    is Shelf.Lists.Items -> it.list.firstNotNullOfOrNull { item -> item as? Album }
                    else -> null
                }
            }
        return album ?: error("Album not found, try a different search query")
    }

    private suspend fun searchTrack(q: String? = null): Track {
        val album = searchAlbum(q)
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val tracks = extension.loadTracks(album)?.loadAll() ?: emptyList()
        return tracks.firstOrNull() ?: error("Track not found for album ${album.title}")
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val search = searchAlbum()
        measureTimeMillis {
            val album = extension.loadAlbum(search)
            println("Loaded album: ${album.title}")
            println("Description: ${album.description?.take(200)}...")
            println("Cover: ${album.cover}")
            println("Studio: ${album.artists.firstOrNull()?.name}")
            assert(album.title.isNotEmpty())
        }.also { println("time : ${it}ms") }
    }

    @Test
    fun testAlbumTracks() = testIn("Testing Album Tracks") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val album = searchAlbum()
        val tracks = extension.loadTracks(album)?.loadAll() ?: emptyList()
        println("Loaded ${tracks.size} tracks for ${album.title}")
        tracks.take(5).forEach { println("  ${it.title} (id: ${it.id})") }
        assert(tracks.isNotEmpty()) { "No tracks found for album" }
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println("Track: ${track.title}, Servers: ${track.servers}")
            assert(track.servers.isNotEmpty())
        }.also { println("time : ${it}ms") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = searchTrack()
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            val streamable = track.servers.firstOrNull() ?: error("Track is not streamable")
            val media = extension.loadStreamableMedia(streamable, false)
            println("Streamable media: $media")
            if (media is Streamable.Media.Server) {
                println("Sources count: ${media.sources.size}")
                media.sources.forEach {
                    println("  Source: ${it.title} -> quality: ${it.quality}, isVideo: ${it.isVideo}")
                }
                assert(media.sources.isNotEmpty()) { "No stream sources returned" }
            }
        }.also { println("time : ${it}ms") }
    }

    @Test
    fun testSettings() = testIn("Testing Settings") {
        val settings = extension.getSettingItems()
        println("Settings count: ${settings.size}")
        settings.forEach { println("  $it") }
        assert(settings.isNotEmpty())
    }


    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
            if (extension is LoginClient) extension.setLoginUser(user)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}
