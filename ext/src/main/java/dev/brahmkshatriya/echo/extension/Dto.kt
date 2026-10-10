package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToInt

@Serializable
data class HomeDto(
    val sections: List<SectionDto> = emptyList(),
)

@Serializable
data class SectionDto(
    val name: String,
    val posts: List<PostItemDto> = emptyList(),
)

@Serializable
data class PostListDto(
    val posts: List<PostItemDto> = emptyList(),
)

@Serializable
data class PostItemDto(
    val id: Long,
    val poster: String? = null,
    val age: String? = null,
)

@Serializable
data class PostDto(
    val id: Long,
    val title: String,
    val type: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val premiered: String? = null,
    val age: String? = null,
    val score: String? = null,
    val genres: String? = null,
    val share: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val related: List<PostItemDto> = emptyList(),
    val similar: List<PostItemDto> = emptyList(),
) {
    fun buildDescription(): String = buildString {
        score?.toFloatOrNull()?.let {
            val filled = (it / 2.0).roundToInt().coerceIn(0, 5)
            append("★".repeat(filled) + "☆".repeat(5 - filled) + " $it\n\n")
        }
        overview?.takeIf { it.isNotBlank() }?.let { append("$it\n\n") }
        val meta = listOfNotNull(
            type?.takeIf { it.isNotBlank() }?.let { "**Type:** $it" },
            premiered?.takeIf { it.isNotBlank() }?.let { "**Season:** $it" },
            runtime?.takeIf { it.isNotBlank() }?.let { "**Duration:** $it" },
            status?.takeIf { it.isNotBlank() }?.let { "**Status:** $it" },
            age?.takeIf { it.isNotBlank() }?.let { "**Rating:** $it" },
            genres?.takeIf { it.isNotBlank() }?.let { "**Genres:** $it" },
        ).joinToString(" | ")
        if (meta.isNotEmpty()) append(meta)
    }.trim()
}

@Serializable
data class SeasonDto(
    val id: Long,
    val poster: String? = null,
    val title: String? = null,
)

@Serializable
data class EpisodeListDto(
    val list: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: String,
    val name: String? = null,
    val number: String,
    val filler: Boolean = false,
) {
    fun getFormattedTitle(offset: Float, showFillerTag: Boolean = true): String {
        val adjustedNumber = number.toFloatOrNull()?.minus(offset) ?: 1f
        val numStr = adjustedNumber.toString().removeSuffix(".0")
        var title = if (!name.isNullOrBlank() && name != "Episode $number") {
            name
        } else {
            "Episode $numStr"
        }
        if (filler && showFillerTag) title += " (Filler)"
        return title
    }

    fun getAdjustedNumber(offset: Float): Float {
        return number.toFloatOrNull()?.minus(offset) ?: 1f
    }
}

@Serializable
data class ServerListDto(
    val list: List<ServerDto> = emptyList(),
)

@Serializable
data class ServerDto(
    val id: String,
    val lang: String? = null,
    val name: String? = null,
)

@Serializable
data class IframeDto(
    val link: String,
)

@Serializable
data class AniZipResponseDto(
    val titles: Map<String, String?> = emptyMap(),
    val episodes: Map<String, AniZipEpisodeDto> = emptyMap(),
    val images: List<AniZipImageDto> = emptyList(),
    val mappings: Map<String, JsonElement?> = emptyMap(),
)

@Serializable
data class AniZipEpisodeDto(
    @SerialName("tvdbId") val tvdbId: Long? = null,
    val title: Map<String, String?> = emptyMap(),
    @SerialName("airDate") val airDate: String? = null,
    val airdate: String? = null,
    val runtime: Long? = null,
    val length: Long? = null,
    val overview: String? = null,
    val summary: String? = null,
    val image: String? = null,
    val rating: String? = null,
    val episode: String? = null,
    @SerialName("episodeNumber") val episodeNumber: Long? = null,
    @SerialName("seasonNumber") val seasonNumber: Long? = null,
) {
    fun getEnglishOrRomajiTitle(): String? {
        return title["en"]?.takeIf { it.isNotBlank() }
            ?: title["x-jat"]?.takeIf { it.isNotBlank() }
            ?: title["ja"]?.takeIf { it.isNotBlank() }
    }
}

@Serializable
data class AniZipImageDto(
    val coverType: String? = null,
    val url: String? = null,
)

@Serializable
data class AniSkipResponseDto(
    val found: Boolean = false,
    val results: List<AniSkipResultDto> = emptyList(),
    val message: String? = null,
    val statusCode: Int? = null,
)

@Serializable
data class AniSkipResultDto(
    val interval: AniSkipIntervalDto,
    val skipType: String,
    val skipId: String? = null,
    val episodeLength: Double? = null,
)

@Serializable
data class AniSkipIntervalDto(
    val startTime: Double,
    val endTime: Double,
)
