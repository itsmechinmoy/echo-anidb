package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class EpisodeResponseDto(
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val number: Double,
    val number2: Double? = null,
    val filler: Boolean = false,
) {
    fun getEpisodeLabel(offset: Float): String {
        val num = number.toFloat()
        val num2 = number2?.toFloat()
        val adjustedNumber = num - offset
        val adjustedNumber2 = num2?.let { it - offset }

        return if (adjustedNumber2 != null && adjustedNumber2 != 0f && adjustedNumber2 != adjustedNumber) {
            "${adjustedNumber.toString().removeSuffix(".0")}\u2013${adjustedNumber2.toString().removeSuffix(".0")}"
        } else {
            adjustedNumber.toString().removeSuffix(".0")
        }
    }

    fun getFormattedTitle(offset: Float, showFillerTag: Boolean = true): String {
        val label = getEpisodeLabel(offset)
        var name = "Episode $label"
        if (filler && showFillerTag) name += " (Filler)"
        return name
    }

    fun getAdjustedNumber(offset: Float): Float {
        return number.toFloat() - offset
    }
}

@Serializable
data class LanguageResponseDto(
    val languages: List<LanguageDto> = emptyList(),
)

@Serializable
data class LanguageDto(
    val name: String,
    val code: String? = null,
    @SerialName("embed_url") val embedUrl: String,
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
