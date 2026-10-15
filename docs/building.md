# Building Final(ish)

## Requirements and commands

Requirements: JDK 21, Android SDK (API 37). Gradle 9, AGP 9, Kotlin 2.4.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # minified, ~3.5 MB
./gradlew :app:testDebugUnitTest
./gradlew :app:generateReleaseBaselineProfile   # with a signed-in device connected
```

Release signing reads `finalish.keystore`, `finalish.keystorePassword`, `finalish.keyAlias` and `finalish.keyPassword` from Gradle properties or the matching `FINALISH_*` environment variables. Without a key `assembleRelease` stops with an error; `-Pfinalish.allowDebugSignedRelease=true` builds a debug-signed one for a local test. The CI workflow in `.github/workflows/android.yml` builds both variants and attaches APKs to `v*` tags.

## Signing in

Frame.io V4 uses **Adobe IMS OAuth 2.0**. Because this is an open-source app, you use your own (free) Adobe credential:

1. Open the [Adobe Developer Console](https://developer.adobe.com/console), create a project and add the **Frame.io API**.
2. Choose **User Authentication → OAuth Native App**. Adobe gives you a *Client ID* and a redirect URI like `adobe+abc123://callback`.
3. Either:
   - **Build-time (recommended):** put the values in `local.properties` (or `~/.gradle/gradle.properties`) and build:
     ```properties
     finalish.imsClientId=YOUR_CLIENT_ID
     finalish.redirectUri=adobe+abc123://callback
     ```
   - **Runtime:** in the app, open **Advanced sign-in**, enter the Client ID and a loopback redirect such as `http://127.0.0.1:8765/callback` (register it on the credential), then tap **Continue with Adobe**.

The app only talks to the current Frame.io API (V4); legacy V2 developer tokens aren't supported.

Tokens are stored encrypted with an Android Keystore key.

## Architecture

```
app/src/main/java/dev/finalish
├── core
│   ├── auth        Adobe IMS PKCE flow, loopback receiver, encrypted token store
│   ├── network     Retrofit API (V4), auth/refresh/rate-limit interceptors, demo backend
│   ├── data        Repository with stale-while-revalidate memory + disk cache, settings
│   ├── model       Tolerant kotlinx.serialization models
│   ├── transfer    WorkManager uploads/downloads (resumable), offline comment sync
│   ├── notify      Server-less activity notifications
│   └── feedback    Bug/feature reporter → GitHub issues via the relay (docs/feedback.md)
└── ui              Jetpack Compose (Material 3, custom dark design system)
    ├── home        Projects, floating tab bar, account switcher
    ├── browser     Folder grid/list, selection, move/copy, scrub thumbnails
    ├── viewer      Player, timeline, annotations, comments, info, versions, compare
    ├── search, transfers, shares, settings, login
```

There is no DI framework, just a small `AppContainer`. Playback uses a 768 MB LRU media cache keyed on stable URLs (Frame.io links are signed), so re-opening a clip is instant.
