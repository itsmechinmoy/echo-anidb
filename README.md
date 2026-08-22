# Echo AniDB Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) to browse, search, and stream anime from [AniDB](https://anidb.app).

## Features

- **Home Feed**: Browse Top Airing, Trending, Latest Updates, Most Popular, and Top Rated anime.
- **Search & Quick Search**: Search anime by title, keywords, or direct AniDB URLs.
- **Rich Anime Details**: Poster art, synopsis, rating scores, airing status, seasons, studio, genres, alternative titles, external links (MAL, AniList, AniDB, Kitsu), and trailers.
- **Rich Episode Metadata**: Powered by [api.ani.zip](https://api.ani.zip) integration for official episode titles, TVDB screencap thumbnails, episode overviews, runtimes, and air dates.
- **Video Chapters & Skip (AniSkip)**: Integrates `TrackChapterClient` with AniSkip to support Opening, Ending, Mixed OP/ED, and Recap timestamps.
- **Filler Detection**: Automatic filler detection with customizable tagging and filtering.
- **Multi-Language & Multi-Quality Streams**: Direct HLS video streaming in Japanese (Sub) / English (Dub) with discrete resolution selection (1080p, 720p, 360p, Auto).
- **Share**: Share direct links to AniDB anime and episode pages.

## Settings

- **Preferred Quality**: Default playback resolution (`1080p`, `720p`, `360p`, `Auto`).
- **Preferred Language**: Preferred audio track (`Japanese`, `English`).
- **Filler Detection**: Appends `(Filler)` tag to detected filler episodes.
- **Hide Filler Episodes**: Option to hide detected filler episodes from the episode list.
- **Auto-Skip OP/ED**: Automatically skips Opening and Ending segments without prompting.

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
