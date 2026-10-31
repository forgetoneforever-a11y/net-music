# Net.music

**One player. All your music.**

Net.music is an Android music hub designed to bring supported streaming services and a personal music library into one clean interface.

## Current status

This repository contains the first Android app foundation and a clickable Jetpack Compose UI prototype.

- Dark minimal interface inspired by the supplied design references
- Home, Library, Search and Settings tabs
- Mini-player and expanded player demo screens
- Initial service connection placeholders for Spotify, SoundCloud and YouTube Music
- Android Media3 dependencies included for the playback layer

**Important:** playback and streaming-service authorization are not connected yet. The player controls in this first milestone are UI demonstrations, not real audio playback.

## Tech stack

- Kotlin
- Jetpack Compose + Material 3
- AndroidX Media3 / ExoPlayer
- Gradle Kotlin DSL
- Minimum Android 8.0 (API 26)

## Build

Open this repository in Android Studio (recent stable version), allow Gradle sync, then run the `:app` configuration on an Android device or emulator.

To build a debug APK:

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Planned milestones

1. Stabilize the Android UI and navigation
2. Add local device library access and real Media3 playback
3. Add SoundCloud integration using its currently supported API capabilities
4. Add Spotify App Remote authorization and playback control where supported
5. Add unified search, playlists, favourites and listening history
6. Add CI APK builds with GitHub Actions

## Streaming-service notes

Each provider has its own API, playback, authentication and commercial-use restrictions. Net.music will use supported official integrations; it will not bypass DRM, subscriptions or provider access controls.

## License

License to be selected before public distribution.
