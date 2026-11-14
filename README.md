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
> Google. It is a clean-room reimplementation written from scratch: no
> proprietary code, resources or assets are used. See `docs/ARCHITECTURE.md` for
> the design and the legal note.

## Features

**Playback**
- Media3/ExoPlayer, H.264/AAC for hardware decode
- **Video quality picker**: leave it on Auto (H.264 up to 1080p, 1440p/2160p when
  nothing smaller exists) or pin any height the video offers, 144p–2160p — from
  the player, with a default in Settings
- **Cast to a device**: find DLNA/UPnP renderers on your own Wi-Fi (a smart TV,
  a speaker, another phone running VLC or Kodi), play there, and use the phone as
  a remote for transport, seek and volume. Quality re-casts at the TV's live
  position; "Play here instead" brings playback back at the same spot
- Separate high-quality video + audio tracks merged automatically
- Live streams via HLS
- Background audio via `MediaSessionService`, media notification, lock screen —
  or switch background playback off so leaving the app pauses the video
- Playback speed 0.25×–4×, audio-only mode, resume where you left off
- **In-player settings panel** behind the gear: quality, playback speed,
  captions, audio-only, background playback, SponsorBlock and dislike counts,
  all without leaving the video
- **Hold the video (or a Short) for 2× playback; release to return to normal**
- **Subtitles/CC** from the video's own tracks, with YouTube auto-translation and
  a fallback to the original track when translation is unavailable
- **Comments** read natively: top/newest sort, paging, likes, reply counts and
  avatars, with the watch page in the browser as a fallback
- Chapters detected from the description, tap to seek
- **Share** the video from the player as its canonical watch link
- **Dislike count** on the player: one honest, read-only figure from Return YouTube
  Dislike, shown only when the "Show dislikes" switch is on
- **End screen**: when a video finishes, its next related video appears over the
  final frame; one tap plays it in the same player
- A playback failure keeps the title, channel, chips and comments usable below,
  with a retry on the player
- **Offline downloads**: tap **More → Download** on a video to keep it on the
  device. A **Downloads** tab in the Library lists what is saved with its live
  progress and lets you retry or remove an item; a saved video plays with no
  connection, and a Downloads section in Settings caps how much is kept

**Discovery**
- Home feed by region, or your own recommendations and subscriptions when signed in
- Dedicated **Shorts** section: swipe up for the next clip, with its own progress
  bar, tap-to-pause, 2× hold gesture, the CC menu, and a rail with like, dislike
  and comment counts whose channel line opens the channel. Signed in, it follows
  your own account's Shorts shelf
- Search with autocomplete and real continuation paging; the keyboard is
  dismissed on submit and the next page loads as the list is scrolled to the end
- Channels, related videos, like/subscriber counts, upload date
- Local **Subscribe** on any channel, independent of any account — also on the
  player's channel row, where the button reads **Subscribed** once you follow it

**Accounts (optional)**
- Google sign-in in an in-app browser session; WeTube never asks for your password
- Multiple accounts, switch or remove them at any time
- Signed in, the home feed follows your YouTube history and the
  **Subscriptions** chip shows the channels you follow on YouTube
- **Sync (optional, off by default).** Reconcile your subscriptions and a
  favourites mirror with the account, pull-first. The first sync pulls the
  account down and then stops to ask before it writes anything — showing only
  counts — so nothing changes on your account unless you tap Merge
- Nothing is uploaded unless you turn sync on and consent; cookies stay in the
  app's private storage

**Your data, wherever you want it**
- History, favourites, resume points, local subscriptions and preferences are
  local by default
- **Backup & restore** writes a single JSON file through the system file picker,
  so it can go to Google Drive, a USB stick or another phone — and be pulled
  back the same way. Cookies and accounts are never in the file.

**Quality of life**
- **Fullscreen** button on the player that hides the system bars; landscape and
  portrait videos both letterbox correctly
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
- No ads, ad SDKs, trackers, or telemetry — the release dependency graph has none
- No account needed; sign-in exists only for people who want their own feed
- Only playback-related permissions
- History and preferences never leave the device unless you export a backup;
  favourites and subscriptions leave only if you turn on account sync and tap
  Merge
- Account cookies live in the app's private storage and are excluded from backups
- Backend hosts are validated — https only, no loopback, private, link-local or
  credential-bearing URLs — and cleartext traffic is refused app-wide
- The optional sign-in WebView is restricted to the Google/YouTube https pages the
  login flow needs, runs without file/content access, and is destroyed when closed

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

Release builds are minified with R8 and will not package without a real signing
key, so a debug-signed artifact can never be mistaken for a release. CI signs
with the repository secrets (`WETUBE_KEYSTORE_BASE64`,
`WETUBE_KEYSTORE_PASSWORD`, `WETUBE_KEY_ALIAS`, `WETUBE_KEY_PASSWORD`).
Locally, put the same values in a git-ignored `keystore.properties`. For a
throwaway test build on a key-less checkout, pass `-PallowDebugSigning=true` to
sign with the debug key explicitly.

## Architecture

```
app/src/main/java/dev/wetube/app/
├── core/          shared text, URL and video-id helpers
├── data/
│   ├── backend/   TubeBackend + InnerTube (default), Invidious, Piped
│   ├── auth/      optional Google session, multiple accounts
│   ├── extras/    SponsorBlock, Return YouTube Dislike
│   ├── local/     history, favourites, resume, subscriptions, collections, backup (DataStore)
│   ├── model/     UI-facing models (no API DTOs leak out)
│   └── prefs/     settings
├── player/        MediaSessionService, stream resolver, controller bridge
└── ui/            Compose screens, one package per feature (incl. Shorts, Credits)
```

The only contract the UI knows is `TubeBackend`. Adding a backend means adding
one class. `docs/ARCHITECTURE.md` explains the transport, playback and subtitle
design, and why the direct backend is the default.

## Status

0.2.6 is a **clarity pass**. Clearing watch history now asks first, so a stray
tap cannot wipe the list and every resume point with it; the liked-videos list and
the control that fills it are both called **Favourite**, so they read as distinct
from **Watch Later**; Settings is regrouped by what each control affects — the
stream-proxy toggle moves to Backend and only appears for a non-direct backend;
and developer vocabulary ("instance", "attestation", "challenge") is gone from
everything on screen.

0.2.5 makes playback recovery **fail over between client profiles**. When a
stream URL is refused mid-playback the player re-resolves the video and resumes
where the viewer was, and it now asks the backend to drop its leading client
profile for that retry — so the next resolve starts from the next client instead
of asking the same one for a URL it has just rejected. Failures a retry cannot
clear are shown with an explanation, and a downloaded video being played is
re-opened from disk before the network is consulted.

0.2.4 is a fix-up for **offline downloads**. A transfer left queued by a process
kill now resumes on the next launch; the downloader verifies the server's byte
range before trusting a resume, so an inconsistent answer can no longer be saved
as a corrupt file; a finished download whose file went missing offers **Download
again** instead of a dead "Downloaded ✓"; and changing the storage limit trims
finished downloads at once. Player errors that a retry cannot clear are now shown
rather than swallowed.

0.2.3 takes videos **offline**. A video's More menu offers **Download**, and a
**Downloads** tab in the Library lists everything saved — live progress, saved
size, retry and remove — with a Settings cap on how much finished media is kept.
A saved video plays from the device with no connection, falling back to the thin
metadata stored alongside the download if the watch page cannot be reached; the
downloader resumes from a `.part` file, so an interrupted transfer picks up where
it stopped rather than starting over.

0.2.2 makes subscriptions work **without an account**. Import an existing list
from a Google Takeout `subscriptions.csv` or an OPML file exported by another
client (NewPipe, FreeTube, …) in Settings, and Home's **Subscriptions** chip
builds its feed from that same device-local list — each channel's recent uploads
fetched with bounded concurrency and merged newest-first, no sign-in required.
A signed-in account still contributes through the list it syncs into.

0.2.1 adds a **device-local library**: save videos to **Watch Later**, line them
up in a **play queue** that auto-advances, and build **local playlists** you can
rename, reorder and play in full. All three live only on the phone — a local
playlist gets a `local:` id so it can never be mistaken for a remote one, and
none of them are ever synced. A bounded metadata cache lets a playlist render
from ids alone without a network call.

0.2.0 adds **account sync**: your subscriptions and a favourites mirror reconcile
with a signed-in YouTube account, pull-first and consent-gated. The first sync
pulls the account down and then stops *before* writing anything, showing only
counts — nothing on the account changes until you tap **Merge**. The merge is
presence-based (InnerTube returns no per-item timestamp), keyed by a
non-reversible per-account key, and the only playlist it will ever delete is one
WeTube itself created; the user's own likes are never written. Removals are
mirrored onto the phone only if you opt in. The account-write endpoints are
hand-rolled against the web client and ship `UNVERIFIED` behind that gate, so a
debug-only capability check — invoked explicitly — is how the write path is
proven. Sync is off by default and unavailable on Invidious/Piped.

0.1.8 added **casting** to a DLNA/UPnP renderer on your own Wi-Fi: find a device,
play there, and use the phone as a remote for transport, seek and volume. Quality
re-casts at the TV's live position and "Play here instead" brings playback back at
the same spot; a short-lived foreground service keeps the cast alive across
screen-off.

0.1.7 — playback, feeds and accounts work on a real device against live
endpoints. The playback path prefers the plain IOS client, whose adaptive links
are not throttled and cover every resolution up to 2160p; the Android and Android
VR clients are fallbacks, with the VR client's signed payload built only when it
is needed. Feeds are anonymous by default. Signed in, the web surface serves the
personal recommendations, the account's subscription feed and the account's own
Shorts shelf, while playback stays anonymous (the native clients refuse a
session). The player has a fullscreen mode that letterboxes landscape and
portrait videos alike, a settings panel behind the control-bar gear (quality,
speed, captions, audio-only, background playback, SponsorBlock, dislike counts),
and a video-quality picker (Auto or any height the video offers, remembered as
the default).

0.1.7 is the quality-and-polish pass:

- The player now carries an honest, read-only **dislike count** (shown when "Show
  dislikes" is on), a **Share** chip, and a channel row whose **Subscribe /
  Subscribed** button is wired to the local library. A finished video shows its
  next related video over the final frame and plays it in the same player, and a
  playback error keeps the title, channel, chips and comments usable below with a
  retry on the player.
- Search dismisses the keyboard when a search is submitted and pages in as the
  list reaches its end. Every paged list (search, home, channel, comments)
  de-duplicates by id, so a repeated item can no longer collide with a Compose key.
- Deep links are host allow-listed, the video id is validated and a link is
  consumed once. Changing quality or audio-only mid-video no longer forces
  playback to start: a paused video stays paused and a playing one keeps playing.
  Preferences are written inside the DataStore transaction, so one update can no
  longer clobber another. Player position and duration are read null-safely.
- Backups are a validated **schema v2** (backend, hosts, proxy flag, sponsor
  categories and the playback preferences) with an explicit version check and
  size/count caps; hosts restored from a file are re-validated. Age-gate ids are
  capped FIFO at 500.

Verified: home feed, subscriptions, search with paging, account-based Shorts
(with like/dislike/comment counts and channel navigation), fullscreen (portrait
and landscape), deep links, hold-to-2×, background playback (on and off), the CC
menu with caption loading, the comment section (top/newest sort and paging), the
dislike count and Share chips on the player, Subscribe/Subscribed from the
player, the end screen, and video playback over `HTTP 206` from the CDN.

Known limits: the comment section is read-only — liking, replying and viewing
replies need a signed-in Google session, which WeTube does not fake, so the sheet
offers the watch page in the browser instead.

Two network details matter on mobile and are handled in one place: DNS is
resolved IPv4-first (many carriers advertise IPv6 they then black-hole), and
ExoPlayer and Coil are given the app's OkHttp client instead of their defaults,
so streams and thumbnails use the same resolver and connection pool.

Known limits: age-restricted videos need YouTube's JavaScript `n` challenge,
which WeTube does not fake, so they can refuse to play even when signed in.
Some networks also get a bot check regardless of client. Neither is bypassed —
the player reports which wall it hit. Downloads, live chat, Google Cast (which
needs a registered receiver app id) and in-app audio output routing are tracked
in `docs/ROADMAP.md`. Sync's write path is implemented but `UNVERIFIED` against a
live account until the capability check is run — see `docs/SYNC_PROTOCOL.md`.

## Licence

MIT — see `LICENSE`.
