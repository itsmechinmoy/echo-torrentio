package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.torrent.TorrentServerManager
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

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = TorrentioExtension()
    private val user = User("", "Test User")

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed Tabs") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed()
        println("Home tabs: ${feed.tabs.map { it.title }}")
        assert(feed.tabs.isNotEmpty()) { "Home feed has no tabs" }

        feed.tabs.forEach { tab ->
            val items = feed.getPagedData.invoke(tab).pagedData.loadPage(null).data
            println("Tab '${tab.title}' loaded ${items.size} items")
            assert(items.isNotEmpty()) { "Tab '${tab.title}' returned 0 items" }
        }
    }

    @Test
    fun testSearchAnime() = testIn("Testing Search Anime") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = "Attack on Titan"
        println("Searching anime: $query")
        val feed = extension.loadSearchFeed(query)
        val items = feed.pagedDataOfFirst().loadPage(null).data
        println("Found ${items.size} items in search")
        items.take(3).forEach { shelf ->
            println("  ${shelf.title}")
        }
        assert(items.isNotEmpty()) { "Search returned no items" }
    }

    @Test
    fun testSearchMovie() = testIn("Testing Search Movie") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = "Inception"
        println("Searching movie: $query")
        val feed = extension.loadSearchFeed(query)
        val items = feed.pagedDataOfFirst().loadPage(null).data
        println("Found ${items.size} items in search")
        items.take(3).forEach { shelf ->
            println("  ${shelf.title}")
        }
        assert(items.isNotEmpty()) { "Search returned no items" }
    }

    @Test
    fun testQuickSearch() = testIn("Testing Quick Search") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val results = extension.quickSearch("One Piece")
        println("Quick search returned ${results.size} items")
        results.forEach { item ->
            println("  ${item.title}")
        }
        assert(results.isNotEmpty()) { "Quick search returned 0 items" }
    }

    @Test
    fun testAnimeDetailsAndTracks() = testIn("Testing Anime Details and Tracks") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        // Attack on Titan AniList ID = 16498
        val initialAlbum = Album(id = "anime:16498", title = "Attack on Titan", cover = null, artists = emptyList())
        val album = extension.loadAlbum(initialAlbum)
        println("Loaded Album: ${album.title}")
        println("Cover: ${album.cover}")
        println("Description: ${album.description?.take(100)}...")

        val tracks = extension.loadTracks(album)?.loadAll() ?: emptyList()
        println("Loaded ${tracks.size} episodes")
        tracks.take(5).forEach { track ->
            println("  Episode: ${track.title} (duration: ${track.duration}ms)")
        }
        assert(tracks.isNotEmpty()) { "No tracks found for anime" }
    }

    @Test
    fun testMovieDetailsAndTracks() = testIn("Testing Movie Details and Tracks") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        // Fight Club IMDb ID = tt0137523
        val initialAlbum = Album(id = "movie:tt0137523", title = "Fight Club", cover = null, artists = emptyList())
        val album = extension.loadAlbum(initialAlbum)
        println("Loaded Movie: ${album.title}")
        println("Description: ${album.description?.take(100)}...")

        val tracks = extension.loadTracks(album)?.loadAll() ?: emptyList()
        println("Loaded ${tracks.size} tracks for movie")
        assert(tracks.size == 1) { "Expected 1 track for movie" }
        println("Track: ${tracks.first().title}")
    }

    @Test
    fun testSeriesDetailsAndTracks() = testIn("Testing Series Details and Tracks") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        // Breaking Bad IMDb ID = tt0903747
        val initialAlbum = Album(id = "series:tt0903747", title = "Breaking Bad", cover = null, artists = emptyList())
        val album = extension.loadAlbum(initialAlbum)
        println("Loaded Series: ${album.title}")

        val tracks = extension.loadTracks(album)?.loadAll() ?: emptyList()
        println("Loaded ${tracks.size} episodes for series")
        tracks.take(5).forEach { track ->
            println("  ${track.title}")
        }
        assert(tracks.isNotEmpty()) { "No tracks found for series" }
    }

    @Test
    fun testTrackStreamQuery() = testIn("Testing Track Media Stream Query") {
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        if (extension !is TrackClient) error("TrackClient is not implemented")

        // 1. Series: Breaking Bad
        val initialAlbum = Album(id = "series:tt0903747", title = "Breaking Bad", cover = null, artists = emptyList())
        val tracks = extension.loadTracks(initialAlbum)?.loadAll() ?: emptyList()
        val firstTrack = tracks.firstOrNull() ?: error("No tracks")
        val streamable = firstTrack.servers.firstOrNull() ?: error("Track has no servers")

        val media = extension.loadStreamableMedia(streamable, false)
        println("Loaded series media: $media")
        if (media is Streamable.Media.Server) {
            println("Found ${media.sources.size} stream sources for Breaking Bad:")
            media.sources.take(2).forEach { source ->
                println("  Source: ${source.title} | Quality: ${source.quality} | URL: ${(source as? Streamable.Source.Http)?.request?.url}")
            }
            assert(media.sources.isNotEmpty()) { "No stream sources returned for series" }
        }

        // 2. Movie: Fight Club
        val movieAlbum = Album(id = "movie:tt0137523", title = "Fight Club", cover = null, artists = emptyList())
        val movieTracks = extension.loadTracks(movieAlbum)?.loadAll() ?: emptyList()
        val firstMovieTrack = movieTracks.firstOrNull() ?: error("No movie tracks")
        val movieStreamable = firstMovieTrack.servers.firstOrNull() ?: error("Movie track has no servers")
        val movieMedia = extension.loadStreamableMedia(movieStreamable, false)
        println("Loaded movie media: $movieMedia")
        if (movieMedia is Streamable.Media.Server) {
            println("Found ${movieMedia.sources.size} stream sources for Fight Club:")
            movieMedia.sources.take(2).forEach { source ->
                println("  Source: ${source.title} | Quality: ${source.quality} | URL: ${(source as? Streamable.Source.Http)?.request?.url}")
            }
            assert(movieMedia.sources.isNotEmpty()) { "No stream sources returned for movie" }
        }

        // 3. Anime: Attack on Titan (16498)
        val animeAlbum = Album(id = "anime:16498", title = "Attack on Titan", cover = null, artists = emptyList())
        val animeTracks = extension.loadTracks(animeAlbum)?.loadAll() ?: emptyList()
        val firstAnimeTrack = animeTracks.firstOrNull() ?: error("No anime tracks")
        val animeStreamable = firstAnimeTrack.servers.firstOrNull() ?: error("Anime track has no servers")
        val animeMedia = extension.loadStreamableMedia(animeStreamable, false)
        println("Loaded anime media: $animeMedia")
        if (animeMedia is Streamable.Media.Server) {
            println("Found ${animeMedia.sources.size} stream sources for Attack on Titan:")
            animeMedia.sources.take(2).forEach { source ->
                println("  Source: ${source.title} | Quality: ${source.quality} | URL: ${(source as? Streamable.Source.Http)?.request?.url}")
            }
            assert(animeMedia.sources.isNotEmpty()) { "No stream sources returned for anime" }
        }

        // 4. Anime: Dandadan (171018)
        val dddAlbum = Album(id = "anime:171018", title = "Dandadan", cover = null, artists = emptyList())
        val dddTracks = extension.loadTracks(dddAlbum)?.loadAll() ?: emptyList()
        val firstDddTrack = dddTracks.firstOrNull() ?: error("No Dandadan tracks")
        val dddStreamable = firstDddTrack.servers.firstOrNull() ?: error("Dandadan track has no servers")
        val dddMedia = extension.loadStreamableMedia(dddStreamable, false)
        println("Loaded Dandadan media: $dddMedia")
        if (dddMedia is Streamable.Media.Server) {
            println("Found ${dddMedia.sources.size} stream sources for Dandadan:")
            dddMedia.sources.take(2).forEach { source ->
                println("  Source: ${source.title} | Quality: ${source.quality} | URL: ${(source as? Streamable.Source.Http)?.request?.url}")
            }
            assert(dddMedia.sources.isNotEmpty()) { "No stream sources returned for Dandadan" }
        }
    }

    @Test
    fun testTorrentServerManagerLifecycle() = testIn("Testing TorrentServerManager") {
        println("Checking TorrentServerManager port and start...")
        TorrentServerManager.start()
        val port = TorrentServerManager.serverPort
        println("Server running on port: $port")
        assert(port >= 8090) { "Expected serverPort >= 8090" }

        val cacheDir = TorrentServerManager.getTorrentCacheDir()
        println("Cache dir: ${cacheDir.absolutePath}")
        assert(cacheDir.exists()) { "Cache dir should exist" }

        TorrentServerManager.stop()
        println("TorrentServerManager stopped successfully")
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
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}
