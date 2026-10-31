# Net.music

**One player. All your music.**

Net.music is an Android music hub designed to bring supported streaming services and a personal music library into one clean interface.

## Current status

The app now includes a local audio library and a real Media3 playback engine, alongside the Jetpack Compose interface.

- Dark minimal interface inspired by the supplied design references
- Home, Library, Search and Settings tabs
- Mini-player and expanded player demo screens
- Initial service connection placeholders for Spotify, SoundCloud and YouTube Music
- Reads music from the device through Android MediaStore after runtime permission is granted
- Plays local audio with AndroidX Media3 ExoPlayer
- MediaSessionService supports background playback and system media controls
- Track search, queue navigation, seek bar and mini-player controls

**Current limitation:** Spotify, SoundCloud and YouTube Music are still placeholders; no provider login or cloud catalog is connected yet. The current working playback source is local audio stored on the Android device.

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

1. Test local library permissions and playback across Android versions
2. Polish the player, queue, favourites and listening history
3. Add SoundCloud integration using its currently supported API capabilities
4. Add Spotify App Remote authorization and playback control where supported
5. Add unified search and playlist management
6. Verify CI APK builds and publish installable releases

## Streaming-service notes

Each provider has its own API, playback, authentication and commercial-use restrictions. Net.music will use supported official integrations; it will not bypass DRM, subscriptions or provider access controls.

## License

License to be selected before public distribution.
