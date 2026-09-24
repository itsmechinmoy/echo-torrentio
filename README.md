# Echo Torrentio Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) to browse, search, and stream Anime, Movies, and TV Series via [Torrentio](https://torrentio.strem.fun) torrents and Debrid providers.

## Features

- **Unified Anime + Movies + TV Series**:
  - **Anime**: Catalog, search, and rich details via **AniList GraphQL**, episode metadata and TVDB artwork via **Ani.zip**, and episode mappings via **Kitsu**.
  - **Movies & TV Series**: Catalog, search, and full episode listings via **Cinemeta** and **IMDb**.
- **Embedded Torrent Server (Zero App-Side Changes)**:
  - Powered by Dantotsu's high-performance torrent streaming engine using `org.libtorrent4j`.
  - Serves torrent streams over a local HTTP server with HTTP Range requests (`206 Partial Content`), an adaptive 20MB rolling lookahead window, and 1% Head + 1% Tail pre-buffering (moov atom / Matroska Cues) for instant, stutter-free playback directly inside Echo's standard ExoPlayer.
- **Debrid Provider Support**:
  - Stream direct high-speed HTTPS links from **RealDebrid**, **AllDebrid**, **Premiumize**, **DebridLink**, or **TorBox** when configured in extension settings.
- **Home Feed**:
  - Tabs for **Trending Anime**, **Popular Movies**, **Popular Series**, and **Latest Anime**.
- **Search & Quick Search**:
  - Multi-tab search (**All**, **Anime**, **Movies**, **TV Series**) with instant parallel query execution.
  - Direct ID lookup support for IMDb IDs (`tt...`) and AniList IDs (`anime:...` or pure numbers).
- **Settings**:
  - Debrid Provider selection (`None (P2P Torrent Streaming)`, `RealDebrid`, `AllDebrid`, `Premiumize`, `DebridLink`, `TorBox`).
  - Debrid API Key / Token input.
  - Preferred Anime Title Language (`Romaji`, `English`, `Native`).

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
