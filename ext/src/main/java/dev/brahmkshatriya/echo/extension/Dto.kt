package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable
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
)

@Serializable
data class PostDto(
    val id: Long,
    val title: String = "Unknown",
    val type: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val premiered: String? = null,
    val age: String? = null,
    val score: String? = null,
    val genres: String? = null,
) {
    fun buildDescription(): String = buildString {
        score?.toFloatOrNull()?.let {
            val filled = (it / 2.0).roundToInt().coerceIn(0, 5)
            append("★".repeat(filled) + "☆".repeat(5 - filled) + " $it\n\n")
        }
        overview?.let { append(it) }

        val meta = listOfNotNull(
            type?.let { "**Type:** $it" },
            status?.let { "**Status:** $it" },
            premiered?.let { "**Season:** $it" },
            runtime?.let { "**Duration:** $it" },
            age?.let { "**Rating:** $it" },
        ).joinToString(" | ")
        if (meta.isNotEmpty()) append("\n\n$meta")
        genres?.takeIf { it.isNotBlank() }?.let { append("\n\n**Genres:** $it") }
    }.trim()
}

@Serializable
data class EpisodeListDto(
    val list: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: String,
    val number: String,
    val filler: Boolean = false,
) {
    fun adjustedNumber(offset: Float): Float = (number.toFloatOrNull() ?: 0f) - offset
}

@Serializable
data class ServerListDto(
    val list: List<ServerDto> = emptyList(),
)

@Serializable
data class ServerDto(
    // "<episodeId>/<language code>"
    val id: String,
)

@Serializable
data class IframeDto(
    val link: String,
)
