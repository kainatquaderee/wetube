# WeTube

A small, fast, open-source YouTube client for Android.
**No ads. No trackers. No account. No analytics.**

WeTube is an independent client. It talks to YouTube's own InnerTube JSON
endpoints directly by default, and can also route metadata through an
[Invidious](https://docs.invidious.io/api/) or [Piped](https://docs.piped.video/)
instance that *you* choose. Playback always uses the CDN links the backend
resolves.

> This project is not affiliated with, endorsed by, or connected to YouTube or
> Google. See `docs/ARCHITECTURE.md` for the learning notes and the legal note.

## Features

**Playback**
- Media3/ExoPlayer, H.264/AAC preferred for hardware decode, up to 2160p
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

**Privacy**
- No ads, ad SDKs, trackers, or telemetry — verified against the built APK
- No account or sign-in
- Only 6 permissions, all playback-related
- History and preferences never leave the device

## Build

Requirements: JDK 17+, Android SDK 35, Android Studio or the Gradle CLI.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

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

0.1.0 — core client is implemented, builds, and was verified end to end against
live endpoints: home feed, search paging with non-overlapping pages,
autocomplete, player formats, related videos, and channel pages. Resolved 1080p
video and AAC audio URLs were fetched directly and answered `HTTP 206` with
`ftypdash`, i.e. real fragmented MP4 that ExoPlayer can play. Downloads,
playlists, live chat and comments are tracked in `docs/ROADMAP.md`.

## Licence

MIT — see `LICENSE`.
