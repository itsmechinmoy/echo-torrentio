package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================== AniList GraphQL ==============================

@Serializable
data class AnilistMeta(
    val data: AnilistPageData? = null,
)

@Serializable
data class AnilistPageData(
    @SerialName("Page")
    val page: AnilistPage? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: AnilistPageInfo? = null,
    val media: List<AnilistMedia>? = null,
)

@Serializable
data class AnilistMetaLatest(
    val data: AnilistPageDataLatest? = null,
)

@Serializable
data class AnilistPageDataLatest(
    @SerialName("Page")
    val page: AnilistPageLatest? = null,
)

@Serializable
data class AnilistPageLatest(
    val pageInfo: AnilistPageInfo? = null,
    val airingSchedules: List<AnilistAiringSchedule>? = null,
)

@Serializable
data class AnilistAiringSchedule(
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistPageInfo(
    val currentPage: Int = 0,
    val hasNextPage: Boolean = false,
)

@Serializable
data class DetailsById(
    val data: DetailsByIdData? = null,
)

@Serializable
data class DetailsByIdData(
    @SerialName("Media")
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistMedia(
    val id: Int? = null,
    val siteUrl: String? = null,
    val title: AnilistTitle? = null,
    val coverImage: AnilistCoverImage? = null,
    val description: String? = null,
    val status: String? = null,
    val tags: List<AnilistTag>? = null,
    val genres: List<String>? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val studios: AnilistStudios? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val countryOfOrigin: String? = null,
    val isAdult: Boolean = false,
)

@Serializable
data class AnilistTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    fun preferredTitle(preference: String = "romaji"): String {
        return when (preference) {
            "english" -> english?.takeIf { it.isNotBlank() } ?: romaji ?: native ?: ""
            "native" -> native?.takeIf { it.isNotBlank() } ?: romaji ?: english ?: ""
            else -> romaji?.takeIf { it.isNotBlank() } ?: english ?: native ?: ""
        }
    }
}

@Serializable
data class AnilistCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistNode>? = null,
)

@Serializable
data class AnilistNode(
    val name: String? = null,
)

@Serializable
data class AnilistTag(
    val name: String? = null,
)

// ============================== Ani.zip ==============================

@Serializable
data class AniZipResponse(
    val titles: Map<String, String?>? = null,
    val episodes: Map<String, AniZipEpisode?>? = null,
    val episodeCount: Int? = null,
    val specialCount: Int? = null,
    val images: List<AniZipImage?>? = null,
    val mappings: AniZipMappings? = null,
)

@Serializable
data class AniZipEpisode(
    val episode: String? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val title: Map<String, String?>? = null,
    val length: Int? = null,
    val runtime: Int? = null,
    @SerialName("airdate")
    val airDate: String? = null,
    val rating: String? = null,
    @SerialName("anidbEid")
    val aniDbEpisodeId: Long? = null,
    val tvdbShowId: Long? = null,
    val tvdbId: Long? = null,
    val overview: String? = null,
    val summary: String? = null,
    val image: String? = null,
) {
    fun getEpisodeTitle(): String? {
        return title?.get("en")
            ?: title?.get("x-jat")
            ?: title?.get("ja")
            ?: overview?.take(60)
    }
}

@Serializable
data class AniZipImage(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniZipMappings(
    @SerialName("animeplanet_id")
    val animePlanetId: String? = null,
    @SerialName("kitsu_id")
    val kitsuId: Long? = null,
    @SerialName("mal_id")
    val myAnimeListId: Long? = null,
    val type: String? = null,
    @SerialName("anilist_id")
    val aniListId: Long? = null,
    @SerialName("anisearch_id")
    val aniSearchId: Long? = null,
    @SerialName("anidb_id")
    val aniDbId: Long? = null,
    @SerialName("notifymoe_id")
    val notifyMoeId: String? = null,
    @SerialName("livechart_id")
    val liveChartId: Long? = null,
    @SerialName("thetvdb_id")
    val theTvDbId: Long? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    @SerialName("themoviedb_id")
    val theMovieDbId: String? = null,
)

// ============================== Kitsu Mappings ==============================

@Serializable
data class KitsuMappingsResponse(
    val data: List<KitsuMapping> = emptyList(),
)

@Serializable
data class KitsuMapping(
    val id: String? = null,
    val type: String? = null,
    val relationships: KitsuMappingRelationships? = null,
)

@Serializable
data class KitsuMappingRelationships(
    val item: KitsuRelationshipItem? = null,
)

@Serializable
data class KitsuRelationshipItem(
    val data: KitsuRelationshipData? = null,
)

@Serializable
data class KitsuRelationshipData(
    val type: String? = null,
    val id: String? = null,
)

// ============================== Cinemeta ==============================

@Serializable
data class CinemetaSearchResponse(
    val query: String? = null,
    val metas: List<CinemetaMeta>? = null,
)

@Serializable
data class CinemetaMeta(
    val id: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val releaseInfo: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val imdbRating: String? = null,
)

@Serializable
data class CinemetaMetaDetailResponse(
    val meta: CinemetaMetaDetail? = null,
)

@Serializable
data class CinemetaMetaDetail(
    val id: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val writer: List<String>? = null,
    val imdbRating: String? = null,
    val status: String? = null,
    val released: String? = null,
    val runtime: String? = null,
    val videos: List<CinemetaVideo>? = null,
)

@Serializable
data class CinemetaVideo(
    val id: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val number: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val rating: String? = null,
)

// ============================== Torrentio Streams ==============================

@Serializable
data class StreamDataTorrent(
    val streams: List<TorrentioStream>? = null,
)

@Serializable
data class TorrentioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val behaviorHints: BehaviorHints? = null,
    val sources: List<String>? = null,
)

@Serializable
data class BehaviorHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
)

// ============================== GraphQL Queries ==============================

object AniListQueries {
    private fun String.toQuery() = this.trimIndent().replace("%", "$")

    fun anilistQuery() = """
        query (
            %page: Int,
            %perPage: Int,
            %sort: [MediaSort],
            %search: String,
            %genres: [String],
            %status: [MediaStatus]
        ) {
            Page(page: %page, perPage: %perPage) {
                pageInfo {
                    currentPage
                    hasNextPage
                }
                media(
                    type: ANIME,
                    sort: %sort,
                    search: %search,
                    status_in: %status,
                    genre_in: %genres,
                    isAdult: false
                ) {
                    id
                    title {
                        romaji
                        english
                        native
                    }
                    coverImage {
                        extraLarge
                        large
                    }
                    description
                    status
                    tags {
                        name
                    }
                    genres
                    studios {
                        nodes {
                            name
                        }
                    }
                    episodes
                    format
                    season
                    seasonYear
                    countryOfOrigin
                    isAdult
                }
            }
        }
    """.toQuery()

    fun anilistLatestQuery() = """
        query (%page: Int, %perPage: Int, %sort: [AiringSort]) {
            Page(page: %page, perPage: %perPage) {
                pageInfo {
                    currentPage
                    hasNextPage
                }
                airingSchedules(
                    airingAt_greater: 0,
                    sort: %sort
                ) {
                    media {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        description
                        status
                        tags {
                            name
                        }
                        genres
                        studios {
                            nodes {
                                name
                            }
                        }
                        episodes
                        format
                        season
                        seasonYear
                        countryOfOrigin
                        isAdult
                    }
                }
            }
        }
    """.toQuery()

    fun getDetailsQuery() = """
        query media(%id: Int) {
            Media(id: %id, isAdult: false) {
                id
                title {
                    romaji
                    english
                    native
                }
                coverImage {
                    extraLarge
                    large
                    medium
                }
                description
                season
                seasonYear
                format
                status
                genres
                episodes
                countryOfOrigin
                isAdult
                tags {
                    name
                }
                studios {
                    nodes {
                        name
                    }
                }
            }
        }
    """.toQuery()
}
