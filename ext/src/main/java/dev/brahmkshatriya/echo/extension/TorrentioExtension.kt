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
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
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
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.torrent.TorrentServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TorrentioExtension :
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    ShareClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private var setting: Settings? = null

    // Lightweight caches
    private val anilistCache = ConcurrentHashMap<String, Any>()
    private val cinemetaCache = ConcurrentHashMap<String, Any>()
    private val anizipCache = ConcurrentHashMap<String, AniZipResponse>()
    private val kitsuCache = ConcurrentHashMap<String, String>()

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun onInitialize() {
        TorrentServerManager.logger = { println("[TorrentServerManager] $it") }
        TorrentServerManager.initNativeLibrary()
    }

    // ============================== Home Feed ==============================

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("anime_trending", "Trending Anime", false),
            Tab("popular_movies", "Popular Movies", false),
            Tab("popular_series", "Popular Series", false),
            Tab("anime_latest", "Latest Anime", false),
        )

        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                when (tab?.id) {
                    "popular_movies" -> {
                        val movies = loadCinemetaCatalog("movie", page)
                        Page(movies.map { it.toShelf() }, null)
                    }
                    "popular_series" -> {
                        val series = loadCinemetaCatalog("series", page)
                        Page(series.map { it.toShelf() }, null)
                    }
                    "anime_latest" -> {
                        val (animeList, hasNext) = loadAniListLatest(page)
                        val nextPage = if (hasNext) (page + 1).toString() else null
                        Page(animeList.map { it.toShelf() }, nextPage)
                    }
                    else -> {
                        // anime_trending (default)
                        val (animeList, hasNext) = loadAniListTrending(page)
                        val nextPage = if (hasNext) (page + 1).toString() else null
                        Page(animeList.map { it.toShelf() }, nextPage)
                    }
                }
            }
            pagedData.toFeedData()
        }
    }

    // ============================== Search Feed ==============================

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val trimmed = query.trim()

        // Direct IMDb ID search: e.g. tt0903747
        if (trimmed.startsWith("tt", ignoreCase = true) && trimmed.length in 9..12) {
            val album = loadCinemetaDirect(trimmed)
            if (album != null) {
                return listOf(album.toShelf()).toFeed()
            }
        }

        // Direct AniList ID: e.g. anime:21 or 21
        val directAniId = when {
            trimmed.startsWith("anime:") -> trimmed.removePrefix("anime:").toIntOrNull()
            trimmed.startsWith("anilist:") -> trimmed.removePrefix("anilist:").toIntOrNull()
            trimmed.toIntOrNull() != null -> trimmed.toIntOrNull()
            else -> null
        }
        if (directAniId != null) {
            val album = runCatching { fetchAniListDetails(directAniId) }.getOrNull()
            if (album != null) {
                return listOf(album.toShelf()).toFeed()
            }
        }

        val tabs = listOf(
            Tab("all", "All", false),
            Tab("anime", "Anime", false),
            Tab("movies", "Movies", false),
            Tab("series", "TV Series", false),
        )

        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                if (trimmed.isBlank()) {
                    // Empty search returns trending
                    val (animeList, hasNext) = loadAniListTrending(page)
                    Page(animeList.map { it.toShelf() }, if (hasNext) (page + 1).toString() else null)
                } else {
                    when (tab?.id) {
                        "anime" -> {
                            val (animeList, hasNext) = searchAniList(trimmed, page)
                            Page(animeList.map { it.toShelf() }, if (hasNext) (page + 1).toString() else null)
                        }
                        "movies" -> {
                            val movies = if (page == 1) searchCinemeta("movie", trimmed) else emptyList()
                            Page(movies.map { it.toShelf() }, null)
                        }
                        "series" -> {
                            val series = if (page == 1) searchCinemeta("series", trimmed) else emptyList()
                            Page(series.map { it.toShelf() }, null)
                        }
                        else -> {
                            // "all" - Combined search in parallel
                            if (page == 1) {
                                coroutineScope {
                                    val animeDeferred = async { runCatching { searchAniList(trimmed, 1).first }.getOrDefault(emptyList()) }
                                    val moviesDeferred = async { runCatching { searchCinemeta("movie", trimmed) }.getOrDefault(emptyList()) }
                                    val seriesDeferred = async { runCatching { searchCinemeta("series", trimmed) }.getOrDefault(emptyList()) }

                                    val anime = animeDeferred.await()
                                    val movies = moviesDeferred.await()
                                    val series = seriesDeferred.await()

                                    val combined = (anime + movies + series).distinctBy { it.id }
                                    Page(combined.map { it.toShelf() }, null)
                                }
                            } else {
                                Page(emptyList(), null)
                            }
                        }
                    }
                }
            }
            pagedData.toFeedData()
        }
    }

    // ============================ Quick Search ============================

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        return coroutineScope {
            val animeDeferred = async { runCatching { searchAniList(trimmed, 1).first.take(4) }.getOrDefault(emptyList()) }
            val moviesDeferred = async { runCatching { searchCinemeta("movie", trimmed).take(3) }.getOrDefault(emptyList()) }
            val seriesDeferred = async { runCatching { searchCinemeta("series", trimmed).take(3) }.getOrDefault(emptyList()) }

            (animeDeferred.await() + moviesDeferred.await() + seriesDeferred.await())
                .distinctBy { it.id }
                .take(8)
                .map { QuickSearchItem.Media(it, false) }
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        // No-op
    }

    // ============================== Album Client ==============================

    override suspend fun loadAlbum(album: Album): Album {
        val id = album.id
        return when {
            id.startsWith("anime:") || id.toIntOrNull() != null -> {
                val anilistId = id.removePrefix("anime:").removePrefix("anilist:").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid AniList ID: $id")
                fetchAniListDetails(anilistId, album)
            }
            id.startsWith("movie:") -> {
                val imdbId = id.removePrefix("movie:")
                fetchCinemetaDetails("movie", imdbId, album)
            }
            id.startsWith("series:") -> {
                val imdbId = id.removePrefix("series:")
                fetchCinemetaDetails("series", imdbId, album)
            }
            id.startsWith("tt") -> {
                // Determine whether movie or series from Cinemeta
                runCatching { fetchCinemetaDetails("series", id, album) }
                    .getOrElse { fetchCinemetaDetails("movie", id, album) }
            }
            else -> album
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track> {
        val id = album.id

        if (id.startsWith("anime:") || id.toIntOrNull() != null || album.extras["type"] == "anime") {
            val anilistId = id.removePrefix("anime:").removePrefix("anilist:").toIntOrNull()
                ?: album.extras["anilistId"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Missing AniList ID for album: $id")

            val aniZip = fetchAniZipMappings(anilistId = anilistId.toString())
            var kitsuId = aniZip?.mappings?.kitsuId?.toString()
                ?: resolveKitsuIdFromAnilist(anilistId.toString())
                ?: ""

            if (kitsuId.isBlank()) {
                kitsuId = searchKitsuByTitle(album.title) ?: ""
            }

            val imdbId = aniZip?.mappings?.imdbId ?: ""

            val format = aniZip?.mappings?.type ?: album.extras["format"] ?: ""
            if (format.equals("MOVIE", ignoreCase = true)) {
                val track = Track(
                    id = "anime_${anilistId}_1",
                    title = album.title,
                    type = Track.Type.Video,
                    cover = album.cover,
                    album = album,
                    artists = album.artists,
                    description = album.description,
                    streamables = mutableListOf(
                        Streamable.server(
                            id = "stream_anime_${kitsuId}_movie",
                            title = "Torrentio Stream",
                            quality = 1080,
                            extras = mapOf(
                                "mediaType" to "anime_movie",
                                "kitsuId" to kitsuId,
                                "anilistId" to anilistId.toString(),
                                "title" to album.title,
                                "imdbId" to imdbId
                            )
                        )
                    ),
                    extras = mapOf(
                        "mediaType" to "anime_movie",
                        "kitsuId" to kitsuId,
                        "anilistId" to anilistId.toString(),
                        "title" to album.title,
                        "imdbId" to imdbId
                    )
                )
                return listOf(track).toFeed()
            }

            // Series / TV / OVA / ONA
            val episodesMap = aniZip?.episodes.orEmpty()
            if (episodesMap.isNotEmpty()) {
                val tracks = episodesMap.entries.mapNotNull { (epKey, ep) ->
                    val epNumber = ep?.episode?.toFloatOrNull()
                        ?: ep?.episodeNumber?.toFloat()
                        ?: epKey.toFloatOrNull()
                        ?: return@mapNotNull null

                    val epTitle = ep?.getEpisodeTitle()
                    val titleFormatted = if (!epTitle.isNullOrBlank()) {
                        "Episode ${formatEpNumber(epNumber)}: $epTitle"
                    } else {
                        "Episode ${formatEpNumber(epNumber)}"
                    }

                    val epCover = ep?.image?.takeIf { it.isNotBlank() }?.toImageHolder() ?: album.cover
                    val durationMs = ep?.runtime?.let { it * 60 * 1000L } ?: ep?.length?.let { it * 60 * 1000L }

                    Track(
                        id = "anime_${anilistId}_${formatEpNumber(epNumber)}",
                        title = titleFormatted,
                        type = Track.Type.Video,
                        cover = epCover,
                        album = album,
                        artists = album.artists,
                        duration = durationMs,
                        description = ep?.overview ?: ep?.summary ?: album.description,
                        streamables = mutableListOf(
                            Streamable.server(
                                id = "stream_anime_${kitsuId}_${formatEpNumber(epNumber)}",
                                title = "Torrentio Stream",
                                quality = 1080,
                                extras = mapOf(
                                    "mediaType" to "anime_series",
                                    "kitsuId" to kitsuId,
                                    "epNum" to formatEpNumber(epNumber),
                                    "anilistId" to anilistId.toString(),
                                    "title" to album.title,
                                    "imdbId" to imdbId
                                )
                            )
                        ),
                        extras = mapOf(
                            "mediaType" to "anime_series",
                            "kitsuId" to kitsuId,
                            "epNum" to formatEpNumber(epNumber),
                            "anilistId" to anilistId.toString(),
                            "title" to album.title,
                            "imdbId" to imdbId
                        )
                    )
                }.sortedBy { it.extras["epNum"]?.toFloatOrNull() ?: 0f }

                return tracks.toFeed()
            }

            // Fallback: Generate tracks using album's total episodes
            val totalEpisodes = album.extras["episodes"]?.toIntOrNull() ?: 1
            val fallbackTracks = (1..totalEpisodes).map { epNum ->
                Track(
                    id = "anime_${anilistId}_$epNum",
                    title = "Episode $epNum",
                    type = Track.Type.Video,
                    cover = album.cover,
                    album = album,
                    artists = album.artists,
                    description = album.description,
                    streamables = mutableListOf(
                        Streamable.server(
                            id = "stream_anime_${kitsuId}_$epNum",
                            title = "Torrentio Stream",
                            quality = 1080,
                            extras = mapOf(
                                "mediaType" to "anime_series",
                                "kitsuId" to kitsuId,
                                "epNum" to epNum.toString(),
                                "anilistId" to anilistId.toString(),
                                "title" to album.title,
                                "imdbId" to imdbId
                            )
                        )
                    ),
                    extras = mapOf(
                        "mediaType" to "anime_series",
                        "kitsuId" to kitsuId,
                        "epNum" to epNum.toString(),
                        "anilistId" to anilistId.toString(),
                        "title" to album.title,
                        "imdbId" to imdbId
                    )
                )
            }
            return fallbackTracks.toFeed()
        }

        // Movie
        if (id.startsWith("movie:") || album.extras["type"] == "movie") {
            val imdbId = id.removePrefix("movie:")
            val track = Track(
                id = "movie_$imdbId",
                title = album.title,
                type = Track.Type.Video,
                cover = album.cover,
                album = album,
                artists = album.artists,
                description = album.description,
                streamables = mutableListOf(
                    Streamable.server(
                        id = "stream_movie_$imdbId",
                        title = "Torrentio Stream",
                        quality = 1080,
                        extras = mapOf(
                            "mediaType" to "movie",
                            "imdbId" to imdbId,
                            "title" to album.title
                        )
                    )
                ),
                extras = mapOf(
                    "mediaType" to "movie",
                    "imdbId" to imdbId
                )
            )
            return listOf(track).toFeed()
        }

        // TV Series
        val imdbId = id.removePrefix("series:")
        val metaDetail = fetchCinemetaMetaDetail("series", imdbId)
        val videos = metaDetail?.videos.orEmpty()
            .filter { (it.season ?: 0) > 0 }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: it.episode ?: 0 }))

        val tracks = videos.map { video ->
            val season = video.season ?: 1
            val episode = video.number ?: video.episode ?: 1
            val videoId = video.id ?: "$imdbId:$season:$episode"
            val epTitle = video.name?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

            Track(
                id = "series_${imdbId}_${season}_${episode}",
                title = "S${season}:E${episode}$epTitle",
                type = Track.Type.Video,
                cover = video.thumbnail?.takeIf { it.isNotBlank() }?.toImageHolder() ?: album.cover,
                album = album,
                artists = album.artists,
                description = video.overview ?: video.description ?: album.description,
                streamables = mutableListOf(
                    Streamable.server(
                        id = "stream_series_$videoId",
                        title = "Torrentio Stream",
                        quality = 1080,
                        extras = mapOf(
                            "mediaType" to "series",
                            "imdbId" to imdbId,
                            "videoId" to videoId,
                            "season" to season.toString(),
                            "episode" to episode.toString(),
                            "title" to album.title
                        )
                    )
                ),
                extras = mapOf(
                    "mediaType" to "series",
                    "imdbId" to imdbId,
                    "videoId" to videoId,
                    "season" to season.toString(),
                    "episode" to episode.toString()
                )
            )
        }

        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    // ============================== Track Client ==============================

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        if (track.streamables.isNotEmpty()) return track

        val extras = track.extras.toMutableMap()
        val mediaType = extras["mediaType"] ?: when {
            track.id.startsWith("movie_") -> "movie"
            track.id.startsWith("series_") -> "series"
            track.id.startsWith("anime_") -> "anime_series"
            else -> "movie"
        }
        extras["mediaType"] = mediaType

        val streamable = Streamable.server(
            id = "stream_${track.id}",
            title = "Torrentio Stream",
            quality = 1080,
            extras = extras
        )
        return track.copy(streamables = mutableListOf(streamable))
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    // ============================== Track Media Client ==============================

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val mediaType = streamable.extras["mediaType"] ?: "movie"
        val kitsuId = streamable.extras["kitsuId"]?.takeIf { it.isNotBlank() }
        val epNum = streamable.extras["epNum"] ?: "1"
        val imdbId = streamable.extras["imdbId"]?.takeIf { it.isNotBlank() }
        val videoId = streamable.extras["videoId"]?.takeIf { it.isNotBlank() }
        val anilistId = streamable.extras["anilistId"]?.takeIf { it.isNotBlank() }
        val title = streamable.extras["title"] ?: ""

        val debridProvider = setting?.getString(PREF_DEBRID_PROVIDER) ?: "none"
        val debridToken = setting?.getString(PREF_DEBRID_TOKEN)?.trim() ?: ""

        val configPath = if (debridProvider != "none" && debridToken.isNotBlank()) {
            "$debridProvider=$debridToken"
        } else ""

        var streams: List<TorrentioStream> = emptyList()

        // 1. Try Kitsu ID if available
        if (!kitsuId.isNullOrBlank()) {
            val endpoint = if (mediaType == "anime_movie") {
                "stream/movie/kitsu:$kitsuId.json"
            } else {
                "stream/series/kitsu:$kitsuId:$epNum.json"
            }
            streams = fetchTorrentioStreams(configPath, endpoint)
        }

        // 2. If no streams and anilistId available, try dynamic Kitsu resolution / search
        if (streams.isEmpty() && !anilistId.isNullOrBlank()) {
            val resolvedKitsuId = resolveKitsuIdFromAnilist(anilistId)
                ?: (if (title.isNotBlank()) searchKitsuByTitle(title) else null)
            if (!resolvedKitsuId.isNullOrBlank() && resolvedKitsuId != kitsuId) {
                val endpoint = if (mediaType == "anime_movie") {
                    "stream/movie/kitsu:$resolvedKitsuId.json"
                } else {
                    "stream/series/kitsu:$resolvedKitsuId:$epNum.json"
                }
                streams = fetchTorrentioStreams(configPath, endpoint)
            }
        }

        // 3. Fallback to IMDb ID for anime (AniZip mappings provide IMDb IDs for anime too)
        if (streams.isEmpty() && !imdbId.isNullOrBlank()) {
            val endpoint = if (mediaType == "anime_movie" || mediaType == "movie") {
                "stream/movie/$imdbId.json"
            } else {
                val ep = epNum
                "stream/series/$imdbId:1:$ep.json"
            }
            streams = fetchTorrentioStreams(configPath, endpoint)
        }

        // 4. Movies and TV Series from Cinemeta
        if (streams.isEmpty()) {
            when (mediaType) {
                "movie" -> if (!imdbId.isNullOrBlank()) {
                    streams = fetchTorrentioStreams(configPath, "stream/movie/$imdbId.json")
                }
                "series" -> if (!videoId.isNullOrBlank()) {
                    streams = fetchTorrentioStreams(configPath, "stream/series/$videoId.json")
                }
            }
        }

        // 5. Ultimate title-based fallback on Cinemeta/IMDb if still empty
        if (streams.isEmpty() && title.isNotBlank() && (mediaType == "movie" || mediaType == "anime_movie")) {
            val cinemetaMovies = runCatching { searchCinemeta("movie", title) }.getOrNull().orEmpty()
            val fallbackImdbId = cinemetaMovies.firstOrNull()?.extras?.get("imdbId")
            if (!fallbackImdbId.isNullOrBlank() && fallbackImdbId != imdbId) {
                streams = fetchTorrentioStreams(configPath, "stream/movie/$fallbackImdbId.json")
            }
        }

        if (streams.isEmpty()) {
            throw Exception("No streams found on Torrentio for '$title'.")
        }

        // Sort streams by health: Debrid links first (if enabled), then seeds descending, then quality
        val isDebridActive = debridProvider != "none"
        val sortedStreams = streams.sortedWith(
            compareByDescending<TorrentioStream> {
                if (isDebridActive && !it.url.isNullOrBlank()) 100_000 else parseSeeds(it)
            }.thenByDescending {
                parseQuality(it.name, it.title)
            }
        )

        val sources = mutableListOf<Streamable.Source>()

        for (stream in sortedStreams) {
            val quality = parseQuality(stream.name, stream.title)
            val streamTitle = formatStreamTitle(stream, isDebridActive && !stream.url.isNullOrBlank())

            if (!stream.url.isNullOrBlank()) {
                // Debrid direct stream
                sources.add(
                    Streamable.Source.Http(
                        request = NetworkRequest(
                            url = stream.url,
                            headers = mapOf("User-Agent" to USER_AGENT)
                        ),
                        type = if (stream.url.contains(".m3u8")) Streamable.SourceType.HLS else Streamable.SourceType.Progressive,
                        quality = quality,
                        title = streamTitle,
                        isVideo = true
                    )
                )
            } else if (!stream.infoHash.isNullOrBlank()) {
                // Local P2P torrent stream via embedded Dantotsu TorrentServer
                val infoHash = stream.infoHash
                val fileIdx = stream.fileIdx ?: 0

                val magnet = buildString {
                    append("magnet:?xt=urn:btih:").append(infoHash)
                    val dn = stream.behaviorHints?.filename ?: stream.title ?: infoHash
                    append("&dn=").append(runCatching { URLEncoder.encode(dn, "UTF-8") }.getOrDefault(dn))
                    append("&index=").append(fileIdx)
                    // Append trackers from stream if available
                    stream.sources?.forEach { tracker ->
                        val cleanTracker = tracker.removePrefix("tracker:")
                        append("&tr=").append(runCatching { URLEncoder.encode(cleanTracker, "UTF-8") }.getOrDefault(cleanTracker))
                    }
                }

                // Register stream metadata in-memory (instant ~0ms, non-blocking)
                TorrentServerManager.registerStream(infoHash, fileIdx, magnet, stream.title ?: infoHash)

                val localStreamUrl = TorrentServerManager.getStreamUrl(
                    infoHash,
                    fileIdx,
                    stream.behaviorHints?.filename ?: stream.title ?: "video.mkv"
                )
                sources.add(
                    Streamable.Source.Http(
                        request = NetworkRequest(url = localStreamUrl, headers = emptyMap()),
                        type = Streamable.SourceType.Progressive,
                        quality = quality,
                        title = streamTitle,
                        isVideo = true
                    )
                )
            }
        }

        if (sources.isEmpty()) {
            throw Exception("Failed to generate any playable streams.")
        }

        return Streamable.Media.Server(sources = sources, merged = false)
    }

    private fun fetchTorrentioStreams(configPath: String, endpoint: String): List<TorrentioStream> {
        val path = if (configPath.isNotBlank()) "$configPath/$endpoint" else endpoint
        val url = "$TORRENTIO_BASE_URL/$path"
        return runCatching {
            val body = httpGet(url)
            val data = json.decodeFromString<StreamDataTorrent>(body)
            data.streams.orEmpty()
        }.getOrDefault(emptyList())
    }

    // ============================== Share Client ==============================

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Album -> {
                val id = item.id
                when {
                    id.startsWith("anime:") || id.toIntOrNull() != null -> {
                        val anilistId = id.removePrefix("anime:").removePrefix("anilist:")
                        "https://anilist.co/anime/$anilistId"
                    }
                    id.startsWith("movie:") || id.startsWith("series:") || id.startsWith("tt") -> {
                        val imdbId = id.removePrefix("movie:").removePrefix("series:")
                        "https://www.imdb.com/title/$imdbId"
                    }
                    else -> TORRENTIO_BASE_URL
                }
            }
            is Track -> {
                val album = item.album
                if (album != null) onShare(album) else TORRENTIO_BASE_URL
            }
            else -> TORRENTIO_BASE_URL
        }
    }

    // ============================== Settings ==============================

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                key = PREF_DEBRID_PROVIDER,
                title = "Debrid Provider",
                summary = "Select Debrid provider for high-speed direct downloads (or None for P2P Torrents)",
                entryTitles = mutableListOf("None (P2P Torrent Streaming)", "RealDebrid", "AllDebrid", "Premiumize", "DebridLink", "TorBox"),
                entryValues = mutableListOf("none", "realdebrid", "alldebrid", "premiumize", "debridlink", "torbox"),
                defaultEntryIndex = 0
            ),
            SettingTextInput(
                key = PREF_DEBRID_TOKEN,
                title = "Debrid API Key / Token",
                summary = "API token for your selected Debrid service",
                defaultValue = ""
            ),
            SettingList(
                key = PREF_TITLE_LANG,
                title = "Preferred Anime Title Language",
                summary = "Choose Romaji, English, or Native Japanese titles",
                entryTitles = mutableListOf("Romaji", "English", "Native"),
                entryValues = mutableListOf("romaji", "english", "native"),
                defaultEntryIndex = 0
            )
        )
    }

    // ============================== AniList API Helpers ==============================

    private suspend fun loadAniListTrending(page: Int): Pair<List<Album>, Boolean> = withContext(Dispatchers.IO) {
        val cacheKey = "trending_$page"
        @Suppress("UNCHECKED_CAST")
        (anilistCache[cacheKey] as? Pair<List<Album>, Boolean>)?.let { return@withContext it }

        val variables = buildJsonObject {
            put("page", page)
            put("perPage", 25)
            put("sort", "TRENDING_DESC")
        }.toString()

        val jsonStr = makeGraphQLRequest(AniListQueries.anilistQuery(), variables)
        val meta = json.decodeFromString<AnilistMeta>(jsonStr)
        val mediaList = meta.data?.page?.media.orEmpty()
        val hasNext = meta.data?.page?.pageInfo?.hasNextPage ?: false

        val preferredTitleLang = setting?.getString(PREF_TITLE_LANG) ?: "romaji"
        val albums = mediaList.mapNotNull { media ->
            val mediaId = media.id ?: return@mapNotNull null
            val title = media.title?.preferredTitle(preferredTitleLang)?.takeIf { it.isNotBlank() }
                ?: media.title?.romaji
                ?: return@mapNotNull null
            val cover = (media.coverImage?.extraLarge ?: media.coverImage?.large)?.toImageHolder()

            Album(
                id = "anime:$mediaId",
                title = title,
                cover = cover,
                artists = emptyList(),
                description = cleanHtml(media.description),
                extras = mapOf(
                    "anilistId" to mediaId.toString(),
                    "type" to "anime",
                    "format" to (media.format ?: ""),
                    "episodes" to (media.episodes?.toString() ?: "")
                )
            )
        }

        val result = albums to hasNext
        anilistCache[cacheKey] = result
        result
    }

    private suspend fun loadAniListLatest(page: Int): Pair<List<Album>, Boolean> = withContext(Dispatchers.IO) {
        val cacheKey = "latest_$page"
        @Suppress("UNCHECKED_CAST")
        (anilistCache[cacheKey] as? Pair<List<Album>, Boolean>)?.let { return@withContext it }

        val variables = buildJsonObject {
            put("page", page)
            put("perPage", 25)
            put("sort", "TIME_DESC")
        }.toString()

        val jsonStr = makeGraphQLRequest(AniListQueries.anilistLatestQuery(), variables)
        val meta = json.decodeFromString<AnilistMetaLatest>(jsonStr)
        val mediaList = meta.data?.page?.airingSchedules.orEmpty().mapNotNull { it.media }
        val hasNext = meta.data?.page?.pageInfo?.hasNextPage ?: false

        val preferredTitleLang = setting?.getString(PREF_TITLE_LANG) ?: "romaji"
        val albums = mediaList.mapNotNull { media ->
            val mediaId = media.id ?: return@mapNotNull null
            val title = media.title?.preferredTitle(preferredTitleLang)?.takeIf { it.isNotBlank() }
                ?: media.title?.romaji
                ?: return@mapNotNull null
            val cover = (media.coverImage?.extraLarge ?: media.coverImage?.large)?.toImageHolder()

            Album(
                id = "anime:$mediaId",
                title = title,
                cover = cover,
                artists = emptyList(),
                description = cleanHtml(media.description),
                extras = mapOf(
                    "anilistId" to mediaId.toString(),
                    "type" to "anime",
                    "format" to (media.format ?: ""),
                    "episodes" to (media.episodes?.toString() ?: "")
                )
            )
        }

        val result = albums to hasNext
        anilistCache[cacheKey] = result
        result
    }

    private suspend fun searchAniList(query: String, page: Int): Pair<List<Album>, Boolean> = withContext(Dispatchers.IO) {
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", 25)
            put("search", query)
            put("sort", "SEARCH_MATCH")
        }.toString()

        val jsonStr = makeGraphQLRequest(AniListQueries.anilistQuery(), variables)
        val meta = json.decodeFromString<AnilistMeta>(jsonStr)
        val mediaList = meta.data?.page?.media.orEmpty()
        val hasNext = meta.data?.page?.pageInfo?.hasNextPage ?: false

        val preferredTitleLang = setting?.getString(PREF_TITLE_LANG) ?: "romaji"
        val albums = mediaList.mapNotNull { media ->
            val mediaId = media.id ?: return@mapNotNull null
            val title = media.title?.preferredTitle(preferredTitleLang)?.takeIf { it.isNotBlank() }
                ?: media.title?.romaji
                ?: return@mapNotNull null
            val cover = (media.coverImage?.extraLarge ?: media.coverImage?.large)?.toImageHolder()

            Album(
                id = "anime:$mediaId",
                title = title,
                cover = cover,
                artists = emptyList(),
                description = cleanHtml(media.description),
                extras = mapOf(
                    "anilistId" to mediaId.toString(),
                    "type" to "anime",
                    "format" to (media.format ?: ""),
                    "episodes" to (media.episodes?.toString() ?: "")
                )
            )
        }

        albums to hasNext
    }

    private suspend fun fetchAniListDetails(anilistId: Int, existingAlbum: Album? = null): Album = withContext(Dispatchers.IO) {
        val cacheKey = "detail_$anilistId"
        (anilistCache[cacheKey] as? Album)?.let { return@withContext it }

        val variables = buildJsonObject {
            put("id", anilistId)
        }.toString()

        val jsonStr = makeGraphQLRequest(AniListQueries.getDetailsQuery(), variables)
        val details = json.decodeFromString<DetailsById>(jsonStr)
        val media = details.data?.media ?: return@withContext existingAlbum ?: Album(id = "anime:$anilistId", title = "Anime", cover = null, artists = emptyList())

        val preferredTitleLang = setting?.getString(PREF_TITLE_LANG) ?: "romaji"
        val title = media.title?.preferredTitle(preferredTitleLang)?.takeIf { it.isNotBlank() }
            ?: media.title?.romaji
            ?: existingAlbum?.title
            ?: "Anime"
        val cover = (media.coverImage?.extraLarge ?: media.coverImage?.large)?.toImageHolder() ?: existingAlbum?.cover

        val album = Album(
            id = "anime:$anilistId",
            title = title,
            cover = cover,
            artists = media.studios?.nodes?.mapNotNull { it.name }?.map { dev.brahmkshatriya.echo.common.models.Artist(it, it, null) } ?: emptyList(),
            description = cleanHtml(media.description),
            extras = mapOf(
                "anilistId" to anilistId.toString(),
                "type" to "anime",
                "format" to (media.format ?: ""),
                "status" to (media.status ?: ""),
                "episodes" to (media.episodes?.toString() ?: "")
            )
        )
        anilistCache[cacheKey] = album
        album
    }

    private fun makeGraphQLRequest(query: String, variables: String): String {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", json.parseToJsonElement(variables))
        }.toString()

        val requestBody = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Referer", "https://anilist.co")
            .header("User-Agent", USER_AGENT)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("AniList GraphQL failed: ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    // ============================== Ani.zip & Kitsu Mapping ==============================

    private suspend fun fetchAniZipMappings(
        anilistId: String? = null,
        kitsuId: String? = null,
        imdbId: String? = null
    ): AniZipResponse? = withContext(Dispatchers.IO) {
        val param = when {
            !anilistId.isNullOrBlank() -> "anilist_id=$anilistId"
            !kitsuId.isNullOrBlank() -> "kitsu_id=$kitsuId"
            !imdbId.isNullOrBlank() -> "imdb_id=$imdbId"
            else -> return@withContext null
        }

        anizipCache[param]?.let { return@withContext it }

        val url = "https://api.ani.zip/mappings?$param"
        val responseStr = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        val aniZip = runCatching { json.decodeFromString<AniZipResponse>(responseStr) }.getOrNull()
        if (aniZip != null) {
            anizipCache[param] = aniZip
        }
        aniZip
    }

    private suspend fun resolveKitsuIdFromAnilist(anilistId: String): String? = withContext(Dispatchers.IO) {
        kitsuCache[anilistId]?.let { return@withContext it }

        val url = "https://kitsu.io/api/edge/mappings?filter[externalSite]=anilist/anime&filter[externalId]=$anilistId&include=item"
        val responseStr = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        val kitsuResp = runCatching { json.decodeFromString<KitsuMappingsResponse>(responseStr) }.getOrNull()
        val kitsuId = kitsuResp?.data?.firstOrNull()?.relationships?.item?.data?.id
        if (!kitsuId.isNullOrBlank()) {
            kitsuCache[anilistId] = kitsuId
        }
        kitsuId
    }

    private suspend fun searchKitsuByTitle(title: String): String? = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
        if (clean.isBlank()) return@withContext null
        val cacheKey = "title_$clean"
        kitsuCache[cacheKey]?.let { return@withContext it }

        val encoded = runCatching { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
        val url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1"
        val responseStr = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        val kitsuResp = runCatching { json.decodeFromString<KitsuMappingsResponse>(responseStr) }.getOrNull()
        val id = kitsuResp?.data?.firstOrNull()?.id
        if (!id.isNullOrBlank()) {
            kitsuCache[cacheKey] = id
        }
        id
    }

    // ============================== Cinemeta API Helpers ==============================

    private suspend fun loadCinemetaCatalog(type: String, page: Int): List<Album> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        val cacheKey = "cinemeta_top_$type"
        @Suppress("UNCHECKED_CAST")
        (cinemetaCache[cacheKey] as? List<Album>)?.let { return@withContext it }

        val url = "$CINEMETA_BASE_URL/catalog/$type/top.json"
        val responseStr = httpGet(url)
        val searchResp = json.decodeFromString<CinemetaSearchResponse>(responseStr)

        val albums = searchResp.metas.orEmpty().mapNotNull { meta ->
            val id = meta.id ?: meta.imdbId ?: return@mapNotNull null
            val title = meta.name ?: return@mapNotNull null
            val cover = meta.poster?.toImageHolder()

            Album(
                id = "$type:$id",
                title = title,
                cover = cover,
                artists = emptyList(),
                description = meta.description,
                extras = mapOf(
                    "imdbId" to id,
                    "type" to type
                )
            )
        }

        cinemetaCache[cacheKey] = albums
        albums
    }

    private suspend fun searchCinemeta(type: String, query: String): List<Album> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$CINEMETA_BASE_URL/catalog/$type/top/search=$encoded.json"
        val responseStr = httpGet(url)
        val searchResp = json.decodeFromString<CinemetaSearchResponse>(responseStr)

        searchResp.metas.orEmpty().mapNotNull { meta ->
            val id = meta.id ?: meta.imdbId ?: return@mapNotNull null
            val title = meta.name ?: return@mapNotNull null
            val cover = meta.poster?.toImageHolder()

            Album(
                id = "$type:$id",
                title = title,
                cover = cover,
                artists = emptyList(),
                description = meta.description,
                extras = mapOf(
                    "imdbId" to id,
                    "type" to type
                )
            )
        }
    }

    private suspend fun loadCinemetaDirect(imdbId: String): Album? = withContext(Dispatchers.IO) {
        // Try series first then movie
        runCatching { fetchCinemetaDetails("series", imdbId) }
            .getOrElse { runCatching { fetchCinemetaDetails("movie", imdbId) }.getOrNull() }
    }

    private suspend fun fetchCinemetaDetails(type: String, imdbId: String, existingAlbum: Album? = null): Album = withContext(Dispatchers.IO) {
        val metaDetail = fetchCinemetaMetaDetail(type, imdbId)
            ?: return@withContext existingAlbum ?: Album(id = "$type:$imdbId", title = "Title", cover = null, artists = emptyList())

        val title = metaDetail.name ?: existingAlbum?.title ?: "Title"
        val cover = metaDetail.poster?.toImageHolder() ?: existingAlbum?.cover

        val artists = (metaDetail.director.orEmpty() + metaDetail.cast.orEmpty().take(3))
            .distinct()
            .map { dev.brahmkshatriya.echo.common.models.Artist(it, it, null) }

        Album(
            id = "$type:$imdbId",
            title = title,
            cover = cover,
            artists = artists,
            description = metaDetail.description ?: existingAlbum?.description,
            extras = mapOf(
                "imdbId" to imdbId,
                "type" to type,
                "released" to (metaDetail.released ?: "")
            )
        )
    }

    private suspend fun fetchCinemetaMetaDetail(type: String, imdbId: String): CinemetaMetaDetail? = withContext(Dispatchers.IO) {
        val cacheKey = "cinemeta_detail_${type}_$imdbId"
        (cinemetaCache[cacheKey] as? CinemetaMetaDetail)?.let { return@withContext it }

        val url = "$CINEMETA_BASE_URL/meta/$type/$imdbId.json"
        val responseStr = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        val detailResp = runCatching { json.decodeFromString<CinemetaMetaDetailResponse>(responseStr) }.getOrNull()
        val meta = detailResp?.meta
        if (meta != null) {
            cinemetaCache[cacheKey] = meta
        }
        meta
    }

    // ============================== Helper Functions ==============================

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun cleanHtml(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return html
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace(Regex("<[^>]*>"), "")
            .trim()
    }

    private fun formatEpNumber(number: Float): String {
        return if (number == number.toInt().toFloat()) {
            number.toInt().toString()
        } else {
            number.toString()
        }
    }

    private fun parseQuality(name: String?, title: String?): Int {
        val combined = "${name.orEmpty()} ${title.orEmpty()}".lowercase()
        return when {
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> 2160
            combined.contains("1440p") || combined.contains("2k") -> 1440
            combined.contains("1080p") || combined.contains("fhd") -> 1080
            combined.contains("720p") || combined.contains("hd") -> 720
            combined.contains("480p") || combined.contains("sd") -> 480
            else -> 1080
        }
    }

    private fun parseQualityString(name: String?, title: String?): String {
        val combined = "${name.orEmpty()} ${title.orEmpty()}".lowercase()
        return when {
            combined.contains("2160p") || combined.contains("4k") || combined.contains("uhd") -> "4K"
            combined.contains("1440p") || combined.contains("2k") -> "1440p"
            combined.contains("1080p") || combined.contains("fhd") -> "1080p"
            combined.contains("720p") || combined.contains("hd") -> "720p"
            combined.contains("480p") || combined.contains("sd") -> "480p"
            else -> "1080p"
        }
    }

    private fun parseSeeds(stream: TorrentioStream): Int {
        val title = stream.title.orEmpty()
        val match = Regex("""👤\s*(\d+)""").find(title)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun parseSize(stream: TorrentioStream): String {
        val title = stream.title.orEmpty()
        val match = Regex("""💾\s*([\d\.]+\s*[MGK]B)""").find(title)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    private fun formatStreamTitle(stream: TorrentioStream, isDebrid: Boolean): String {
        val res = parseQualityString(stream.name, stream.title)
        val seeds = parseSeeds(stream)
        val size = parseSize(stream)
        val releaseName = stream.title?.lines()?.firstOrNull()?.trim() ?: stream.name.orEmpty()
        val tag = if (isDebrid) "⚡ [Debrid]" else "🧲"

        val meta = buildString {
            append("[$res]")
            if (size.isNotBlank()) append(" 💾 $size")
            if (!isDebrid && seeds > 0) append(" 👤 $seeds seeds")
        }

        return if (releaseName.isNotBlank() && !releaseName.contains("👤")) {
            "$tag $meta • $releaseName"
        } else {
            "$tag $meta"
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val TORRENTIO_BASE_URL = "https://torrentio.strem.fun"
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"

        const val PREF_DEBRID_PROVIDER = "debrid_provider"
        const val PREF_DEBRID_TOKEN = "debrid_token"
        const val PREF_TITLE_LANG = "title_lang"
    }
}
