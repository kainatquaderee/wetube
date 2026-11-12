# WeTube

[![Build](https://github.com/kainatquaderee/wetube/actions/workflows/android.yml/badge.svg)](https://github.com/kainatquaderee/wetube/actions/workflows/android.yml)

A small, fast, open-source YouTube client for Android.
**No ads. No trackers. No account. No analytics.**

WeTube is an independent client. It talks to YouTube's own InnerTube JSON
endpoints directly by default, and can also route metadata through an
[Invidious](https://docs.invidious.io/api/) or [Piped](https://docs.piped.video/)
instance that *you* choose. Playback always uses the CDN links the backend
resolves.

> This project is not affiliated with, endorsed by, or connected to YouTube or
> Google. It is developed clean-room, in the spirit of the Nouveau driver: the
> official app was studied for structure, never copied. See
> `docs/ARCHITECTURE.md` for the method and the legal note.

## Features

**Playback**
- Media3/ExoPlayer, H.264/AAC for hardware decode, 1080p by default with 4K fallback
- Separate high-quality video + audio tracks merged automatically
- Live streams via HLS
- Background audio via `MediaSessionService`, media notification, lock screen
- Playback speed 0.25×–4×, audio-only mode, resume where you left off
- Chapters detected from the description, tap to seek

**Discovery**
- Home feed by region
- Search with autocomplete and real continuation paging
- Channels, related videos, like/subscriber counts, upload date

**Quality of life**
- **SponsorBlock** auto-skip (sponsor / self-promo / interaction)
- **Return YouTube Dislike** counts
- Local history, favourites, resume points — stored on device only
- Deep links: `youtu.be/ID`, `youtube.com/watch?v=ID`
- Choose backend and instance at runtime
- **Show mature content** switch: age-restricted videos are hidden from feeds and search
  until you turn it on. YouTube does not label them in listings, so WeTube learns an id the
  first time playback hits the age wall and remembers it locally. WeTube never signs in, so
  turning the switch on does not bypass YouTube's account requirement — it just stops the
  app from hiding the video, and the player says plainly when an account is needed.

**Privacy**
- No ads, ad SDKs, trackers, or telemetry — verified against the built APK
- No account or sign-in
- Only 6 permissions, all playback-related
- History and preferences never leave the device

## Install

Grab `app-release.apk` from the
[latest release](https://github.com/kainatquaderee/wetube/releases/latest), or
from the artifacts of any successful
[CI run](https://github.com/kainatquaderee/wetube/actions), then:

```bash
adb install -r app-release.apk
```

Release APKs are signed with the project key; `apksigner verify --print-certs`
prints `CN=WeTube`.

The debug build carries a `.debug` application id, so it can sit next to the
release build without replacing it.

## Build

Requirements: JDK 17+, Android SDK 35, Android Studio or the Gradle CLI.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds are minified with R8. Without a signing key they fall back to the
debug key so local builds still work; CI signs with the repository secrets
(`WETUBE_KEYSTORE_BASE64`, `WETUBE_KEYSTORE_PASSWORD`, `WETUBE_KEY_ALIAS`,
`WETUBE_KEY_PASSWORD`). Locally, put the same values in a git-ignored
`keystore.properties`.

## Architecture

```
app/src/main/java/dev/wetube/app/
├── data/
│   ├── backend/   TubeBackend + InnerTube (default), Invidious, Piped
│   ├── extras/    SponsorBlock, Return YouTube Dislike
│   ├── local/     history, favourites, resume points (DataStore)
│   ├── model/     UI-facing models (no API DTOs leak out)
│   └── prefs/     settings
├── player/        MediaSessionService, stream resolver, controller bridge
└── ui/            Compose screens, one package per feature
```

The only contract the UI knows is `TubeBackend`. Adding a backend means adding
one class. `docs/ARCHITECTURE.md` explains the transport choices, what was
learned from the official app, and why the direct backend is the default.

## Status

0.1.1 — playback works on a real device against live endpoints. The InnerTube
transport uses the Android VR client profile with a visitor identity, which is
what YouTube currently accepts without an account; older client versions are
answered with "Sign in to confirm you're not a bot". Verified: home feed with
thumbnails, search with paging, deep links, and video playback over `HTTP 206`
from the CDN.

Two network details matter on mobile and are handled in one place: DNS is
resolved IPv4-first (many carriers advertise IPv6 they then black-hole), and
ExoPlayer and Coil are given the app's OkHttp client instead of their defaults,
so streams and thumbnails use the same resolver and connection pool.

Known limits: some videos are age-restricted, and some networks get a bot check
regardless of client. Neither is bypassed — the player reports which wall it hit.
Downloads, playlists, live chat and comments are tracked in `docs/ROADMAP.md`.

## Licence

MIT — see `LICENSE`.
