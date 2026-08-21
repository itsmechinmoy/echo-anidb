package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    fun getFormattedTitle(offset: Float, showFillerTag: Boolean = true): String {
        val num = number.toFloat()
        val num2 = number2?.toFloat()
        val adjustedNumber = num - offset
        val adjustedNumber2 = num2?.let { it - offset }

        val label = if (adjustedNumber2 != null && adjustedNumber2 != 0f && adjustedNumber2 != adjustedNumber) {
            "${adjustedNumber.toString().removeSuffix(".0")}\u2013${adjustedNumber2.toString().removeSuffix(".0")}"
        } else {
            adjustedNumber.toString().removeSuffix(".0")
        }

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
