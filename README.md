# Echo AniDB Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) to browse, search, and stream anime from [AniDB](https://anidb.app).

## Features

- **Home Feed**: Browse Top Airing, Trending, Latest Updates, Most Popular, and Top Rated anime.
- **Search**: Search anime by title, keywords, or direct AniDB URLs.
- **Anime Details**: View poster art, synopsis, scores, airing status, seasons, studio, genres, alternative titles, links, and trailers.
- **Episode List**: Episode listings with automatic filler detection and customizable filler display.
- **Multi-Language Streams**: HLS video streaming in Japanese / English with quality selection (1080p, 720p, 480p, 360p, Auto).
- **Share**: Share links to AniDB anime and episode pages.

## Settings

- **Preferred Quality**: Default playback resolution (1080p, 720p, 360p).
- **Preferred Language**: Preferred audio language (Japanese, English).
- **Filler Detection**: Appends `(Filler)` tag to detected filler episodes.
- **Hide Filler Episodes**: Option to hide filler episodes from the episode list.

## Development & Testing

### Local Testing
Run unit tests:
```bash
./gradlew ext:test
```

### Build Extension JAR & Android APK
```bash
./gradlew ext:shadowJar app:assembleDebug
```

## Author

- **itsmechinmoy** ([GitHub](https://github.com/itsmechinmoy))
