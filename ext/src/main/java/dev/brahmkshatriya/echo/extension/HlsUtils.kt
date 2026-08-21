package dev.brahmkshatriya.echo.extension

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsStream(
    val quality: Int,
    val resolutionLabel: String,
    val url: String,
    val bandwidth: Long = 0,
)

object HlsUtils {
    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")

    fun parseMasterPlaylist(masterUrl: String, playlistContent: String): List<HlsStream> {
        val masterHttpUrl = masterUrl.toHttpUrlOrNull()
        val streams = mutableListOf<HlsStream>()

        val lines = playlistContent.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val resMatch = RESOLUTION_REGEX.find(line)
                val height = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1080
                val bwMatch = BANDWIDTH_REGEX.find(line)
                val bandwidth = bwMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

                // Next non-empty, non-comment line is the target URL
                var streamUrl: String? = null
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        streamUrl = nextLine
                        i = j
                        break
                    }
                    j++
                }

                if (!streamUrl.isNullOrEmpty()) {
                    val absoluteUrl = if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                        streamUrl
                    } else if (masterHttpUrl != null) {
                        masterHttpUrl.resolve(streamUrl)?.toString() ?: streamUrl
                    } else {
                        streamUrl
                    }

                    streams.add(
                        HlsStream(
                            quality = height,
                            resolutionLabel = "${height}p",
                            url = absoluteUrl,
                            bandwidth = bandwidth,
                        )
                    )
                }
            }
            i++
        }

        // If no sub-streams were found, fall back to master URL
        if (streams.isEmpty()) {
            streams.add(
                HlsStream(
                    quality = 1080,
                    resolutionLabel = "1080p",
                    url = masterUrl,
                )
            )
        }

        return streams.distinctBy { it.resolutionLabel }
    }
}
