# WeTube

[![Build](https://github.com/kainatquaderee/wetube/actions/workflows/android.yml/badge.svg)](https://github.com/kainatquaderee/wetube/actions/workflows/android.yml)

A small, fast, open-source YouTube client for Android.
**No ads. No trackers. No analytics.** Signing in is optional.

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
- Home feed by region, or your own recommendations and subscriptions when signed in
- Dedicated **Shorts** section: full-screen, swipe up for the next clip
- Search with autocomplete and real continuation paging
- Channels, related videos, like/subscriber counts, upload date
- Local **Subscribe** on any channel, independent of any account

**Accounts (optional)**
- Google sign-in in an in-app browser session; WeTube never asks for your password
- Multiple accounts, switch or remove them at any time
- Signed in, the home feed follows your YouTube history and the
  **Subscriptions** chip shows the channels you follow on YouTube
- Nothing is uploaded: cookies stay in the app's private storage

**Your data, wherever you want it**
- History, favourites, resume points, local subscriptions and preferences are
  local by default
- **Backup & restore** writes a single JSON file through the system file picker,
  so it can go to Google Drive, a USB stick or another phone — and be pulled
  back the same way. Cookies and accounts are never in the file.

**Quality of life**
- **SponsorBlock** auto-skip (sponsor / self-promo / interaction)
- **Return YouTube Dislike** counts
- Local history, favourites, resume points, local subscriptions — stored on device only
- Deep links: `youtu.be/ID`, `youtube.com/watch?v=ID`
- Choose backend and instance at runtime
- **Show mature content** switch: age-restricted videos are hidden from feeds and search
  until you turn it on. YouTube does not label them in listings, so WeTube learns an id the
  first time playback hits the age wall and remembers it locally. Turning the switch on
  stops the app from hiding the video, but it does not fake YouTube's JavaScript challenge,
  so an age-gated video can still refuse to play even when signed in — the player says
  plainly which wall it hit.
- **Credits** screen listing the people who build WeTube

**Privacy**
- No ads, ad SDKs, trackers, or telemetry — verified against the built APK
- No account needed; sign-in exists only for people who want their own feed
- Only playback-related permissions
- History, favourites and preferences never leave the device unless you export a backup
- Account cookies live in the app's private storage and are excluded from backups

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
│   ├── auth/      optional Google session, multiple accounts
│   ├── extras/    SponsorBlock, Return YouTube Dislike
│   ├── local/     history, favourites, resume, subscriptions, backup (DataStore)
│   ├── model/     UI-facing models (no API DTOs leak out)
│   └── prefs/     settings
├── player/        MediaSessionService, stream resolver, controller bridge
└── ui/            Compose screens, one package per feature (incl. Shorts, Credits)
```

The only contract the UI knows is `TubeBackend`. Adding a backend means adding
one class. `docs/ARCHITECTURE.md` explains the transport choices, what was
learned from the official app, and why the direct backend is the default.

## Status

0.1.2 — playback, feeds and accounts work on a real device against live
endpoints. The playback path uses the plain Android client, which hands out
progressive URLs that stay readable for the whole video; the Android VR client
is the fallback for clips. Feeds are anonymous by default. Signed in, the web
surface serves the personal recommendations and the account's subscription
feed, while playback stays anonymous (the native clients refuse a session).
Verified: home feed, subscriptions, search with paging, Shorts, deep links, and
video playback over `HTTP 206` from the CDN.

Two network details matter on mobile and are handled in one place: DNS is
resolved IPv4-first (many carriers advertise IPv6 they then black-hole), and
ExoPlayer and Coil are given the app's OkHttp client instead of their defaults,
so streams and thumbnails use the same resolver and connection pool.

Known limits: age-restricted videos need YouTube's JavaScript `n` challenge,
which WeTube does not fake, so they can refuse to play even when signed in.
Some networks also get a bot check regardless of client. Neither is bypassed —
the player reports which wall it hit. Downloads, playlists, live chat and
comments are tracked in `docs/ROADMAP.md`.

## Licence

MIT — see `LICENSE`.
