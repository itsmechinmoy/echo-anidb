package dev.brahmkshatriya.echo.extension

import java.util.Calendar

object Filters {

    val TYPES = arrayOf(
        Pair("All", ""),
        Pair("Movie", "Movie"),
        Pair("Music", "Music"),
        Pair("ONA", "ONA"),
        Pair("OVA", "OVA"),
        Pair("Special", "Special"),
        Pair("TV", "TV"),
    )

    val STATUSES = arrayOf(
        Pair("All", ""),
        Pair("Currently Airing", "Currently Airing"),
        Pair("Finished Airing", "Finished Airing"),
    )

    val SEASONS = arrayOf(
        Pair("All", ""),
        Pair("Spring", "spring"),
        Pair("Summer", "summer"),
        Pair("Fall", "fall"),
        Pair("Winter", "winter"),
    )

    val YEARS by lazy {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        buildList {
            add(Pair("All", ""))
            addAll((currentYear downTo 1968).map { Pair(it.toString(), it.toString()) })
            add(Pair("1925", "1925"))
        }.toTypedArray()
    }

    val DEMOGRAPHICS = arrayOf(
        Pair("All", ""),
        Pair("Shounen", "1"),
        Pair("Seinen", "2"),
        Pair("Shoujo", "5"),
        Pair("Kids", "4"),
        Pair("Josei", "3"),
    )

    val GENRES = arrayOf(
        Pair("All", ""),
        Pair("Action", "1"),
        Pair("Adventure", "3"),
        Pair("Avant Garde", "19"),
        Pair("Award Winning", "12"),
        Pair("Boys Love", "16"),
        Pair("Comedy", "5"),
        Pair("Drama", "2"),
        Pair("Ecchi", "13"),
        Pair("Erotica", "17"),
        Pair("Fantasy", "4"),
        Pair("Girls Love", "20"),
        Pair("Gourmet", "8"),
        Pair("Hentai", "15"),
        Pair("Horror", "21"),
        Pair("Mystery", "7"),
        Pair("Romance", "14"),
        Pair("Sci-Fi", "6"),
        Pair("Slice of Life", "9"),
        Pair("Sports", "11"),
        Pair("Supernatural", "10"),
        Pair("Suspense", "18"),
    )

    val SORTS = arrayOf(
        Pair("Trending", "order_trending"),
        Pair("Top Rated", "order_top"),
        Pair("Latest Updated", "order_updated"),
        Pair("Most Popular", "order_popular"),
        Pair("Most Favorited", "order_favorite"),
        Pair("Top Airing", "order_top_airing"),
        Pair("Title A-Z", "title"),
        Pair("Newest First", "aired_start"),
    )
}
