# Personal integration fork

This document describes the Android/Google TV customization layer in this fork. It is based on the upstream Free My TV project and keeps the upstream build, launcher switching, TV settings, and rollback model.

## What this fork adds

### Home screen

- A five-tab home screen: **Watch now**, **Movies**, **Shows**, **Videos for you**, and **Listen now**.
- A compact app shelf, horizontal catalog paging, image-backed cards, and an automatic 12-second tab carousel.
- A Continue Watching row fed by Nuvio and SmartTube. It sorts items by the latest engagement timestamp published by Android TV's preview-program provider; ties retain provider order.
- Nuvio episode details where available: season and episode, playback progress, time remaining, and the number of episodes left in the season.
- Separate movie and series catalogs sourced from Cinemeta metadata. Opening a title uses a Nuvio deep link. Poster images are cached and pages are prefetched so later cards do not wait for sequential downloads.
- Live weather and system-performance cards, including FPS, available memory, and free storage. The screensaver keeps its recommendation carousel and displays a solid HH:MM clock.

### SmartTube, Nuvio, and Spotify

`NativeFeeds.java` reads Android TV's public `TvContract` channel and preview-program rows for the installed apps. It does not scrape private app databases or store login credentials.

- **SmartTube** contributes its published recommendation and continue/history channels. Selecting a program opens it through the intent supplied by SmartTube. Feed rows refresh on a 60-second cache interval.
- **Nuvio** contributes continue-watching programs and their published playback metadata. Selecting a program uses the Nuvio-provided intent. Season episode counts can be enriched through TMDB when a local TMDB key is configured.
- **Spotify** contributes its Android TV preview programs. Track and artist text come from those rows or the track metadata lookup; artwork and artist-name caches are local to the TV. Spotify sign-in remains inside Spotify.
- **Movies** and **Shows** use Cinemeta's public Stremio-compatible catalogs for metadata, with poster images from Metahub. Catalog entries open in Nuvio by IMDb ID. These catalogs do not require a user account or API key.

The available rows depend on what each installed app publishes to Android TV. Empty or renamed provider channels can change what appears; this fork does not assume every app version exposes the same feed.

### Remote and TV behavior

- TCL remote-key handling keeps the system's TV-settings quick panel reachable and maps the configured TCL Channel key back to Free My TV.
- LauncherX remains enabled on the supported Google TV profile for Google services and native system surfaces. At boot, a job restores Free My TV after firmware that explicitly starts LauncherX. The service does not read the Google account identity or copy account data into the app.
- Debloat remains reversible through Free My TV's Original mode. Protected packages include Assistant, TV settings, inputs, casting, remote support, installers, and the streaming apps.

Device-specific package handling lives in `HomeMode.java`; review it against the target TV before using the switch on another manufacturer or model.

## Private settings and credentials

The fork contains no device IP address, personal city coordinates, Google email/profile data, TMDB token, Watchmode key, Spotify credentials, or signing key.

- `config.properties` is local and ignored by Git. `build.sh` creates it from `config.example.properties` when needed.
- Set `city`, `latitude`, and `longitude` in the local configuration or in the TV's Home Settings. These values control the weather location and sunset-based theme.
- Put optional `tmdb_key` and `watchmode_key` values only in local `config.properties`. TMDB is used for the upstream recommendations and optional season-count enrichment; Watchmode is optional.
- `freemytv.keystore` is generated locally and ignored. Keep the same key for future APK updates; never publish it.
- Google and Spotify accounts are managed by their own TV services and apps. No account identifier, avatar, access token, or password is stored by this project.
- Supply the TV address only to the local install command; do not add it to source files or documentation.

The repository ignores local configuration, signing files, environment files, and common secret-file extensions. Before publishing new changes, review the staged diff and ensure that only the example configuration is included.

## Build and install

Use the upstream instructions in `README.md`. In short, install JDK 17+, Android SDK platform 34, and build-tools 34+, then run:

```sh
./build.sh
adb connect <TV_IP>:5555
adb install -r FreeMyTV.apk
```

Configure the local `config.properties` first. Keep the generated APK and signing key out of Git. Test launcher switching and rollback on the target device before enabling this fork as HOME.

## Main implementation files

| File | Responsibility |
|---|---|
| `app/src/io/freemytv/HomeView.java` | Tabs, cards, paging, focus, and card layout |
| `app/src/io/freemytv/NativeFeeds.java` | SmartTube, Nuvio, and Spotify Android TV preview feeds |
| `app/src/io/freemytv/CatalogData.java` | Cinemeta movie/series catalogs, poster cache, and Nuvio deep links |
| `app/src/io/freemytv/HomeData.java` | Weather, FPS, memory, and storage data |
| `app/src/io/freemytv/DreamView.java` | Screensaver recommendation carousel and clock |
| `app/src/io/freemytv/HomeApps.java` | Shelf art and TV/game-console shortcuts |
| `app/src/io/freemytv/RemoteKeys.java`, `RemoteKeyService.java` | TCL remote mapping and TV-settings panel access |
| `app/src/io/freemytv/HomeMode.java`, `ModeJob.java` | Reversible HOME/debloat mode and boot recovery |
| `app/assets/home-icons/`, `app/assets/symbols/` | Home and console artwork |
