# What the official YouTube app does (and what WeTube does instead)

This document records the **learning pass**: what static analysis of a shipped
YouTube APK showed, and how each finding shaped an original design.

Nothing in this repository was copied from the APK. Decompiled output was used
only to understand structure and trade-offs; WeTube's implementation was written
from scratch against public, documented APIs.

## Method: clean-room reimplementation

The working model is **Nouveau**, the community NVIDIA driver: an independent
implementation of a proprietary interface, built from observed behaviour and
public documentation, released under an open licence, and explicit about its
provenance.

That analogy sets the expectations honestly:

- **Source discipline.** Study the shipped binary, never lift from it. No
  decompiled code, resources, layouts or string tables end up in this tree.
- **Interface-level understanding, not internals.** Nouveau talks to the GPU
  through documented registers and firmware interfaces; WeTube talks to
  `youtubei/v1/*` through the request/response shapes it actually returns. We do
  not reimplement Google's server internals, DRM, or attestation.
- **It will lag.** Nouveau arrives after new hardware does. A reverse-engineered
  client breaks when the vendor rotates a client profile. That is a maintenance
  property of the approach, not a bug to be argued away — which is why the
  transport is a swappable interface with three implementations instead of one
  hard-coded path.
- **Legal posture.** Interoperability work, no circumvention, no redistribution
  of vendor assets. Trademarks stay with their owners; see the note at the end.
- **Your own risk.** Like running Nouveau, this is for people who prefer
  control and auditability over vendor-supported convenience.

---

## Target examined

| | |
|---|---|
| Package | `com.google.android.youtube` |
| Version | 21.45.82 (versionCode 1561310996) |
| APK SHA-256 | `a18e7f723293704e2069424042a8cb1189d10726ddd6de21413c754a21827ae8` |
| Size | 222 MB, 8 DEX files, 54,468 top-level classes |
| minSdk / targetSdk | 29 / 37 |

Tooling: REA with the `jadx-headless-mcp` 0.7.1 engine
(`artifact_sha256 6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`).
Evidence: `ev_6812c988…` (package/manifest) and `ev_32235901…` (summary view).

> Static decompilation never establishes runtime behaviour. Everything below is
> either an observed declaration (class exists, string exists, manifest says X)
> or an inference clearly marked as such.

---

## 1. The UI is server-driven, not hand-written per screen

**Observed.** `com.google.android.libraries.elements.*` contains 170+ classes.
The app also ships Litho (`com.facebook.litho.ComponentHost`, `ComponentTree`)
and classic `RecyclerView`. Jetpack Compose is present only incidentally
(`androidx.compose.ui.input.pointer.PointerInputEventHandler`) — effectively
unused.

**Inference.** Feed screens are described by protobuf "Elements" trees sent by
the server and rendered by a generic engine. This is why YouTube can change its
layout without an app update, and why the app is enormous.

**What WeTube does.** Hand-written Jetpack Compose. It is the opposite trade-off:
small, predictable, fast, and fully auditable. We lose server-pushed layout
experiments — which is precisely what users of alternative clients want.

---

## 2. Transport: Cronet + gRPC + protobuf InnerTube

**Observed.**

- `org.chromium.net.CronetEngine` / `ExperimentalCronetEngine` are bundled.
- `io.grpc.*` is bundled, including a **binder transport** (`io.grpc.binder.*`).
- Content type string `application/grpc` is present.
- Hosts: `youtubei.googleapis.com`, plus `release-`/`test-` sandbox variants and
  `redirector.googlevideo.com`.
- Proto contracts under `com.google.protos.youtube.api.innertube.*`, e.g.
  `InnertubeContext$ClientInfo`, `InnertubeContext$InnerTubeContext`, and the
  envelope type `type.googleapis.com/youtube.api.pfiinnertube.YoutubeApiInnertube.AnyInnertubeResponse`.
- RPC path keys such as `get_watch`, `player/ad_break`, `reel/reel_item_watch`.

**Inference.** The official client no longer speaks a simple REST API. It speaks
gRPC with protobuf envelopes to `youtubei.googleapis.com`, with the client
identity (`ClientInfo`) embedded in every request.

**What WeTube does.** A `TubeBackend` interface with three implementations.
The default is a **direct InnerTube backend** written from scratch, over plain
JSON HTTP:

- It POSTs the same envelope shape the official client does, but with no
  attestation, no PoToken, and no protobuf binding — responses are walked as a
  generic JSON tree so added fields cannot break parsing.
- It uses **two client profiles**, because they are good at different things:
  `ANDROID_VR` for playback (returns plain, non-ciphered stream URLs) and `WEB`
  for search (returns cards with duration and upload date). This mirrors what
  many alternative clients do and was chosen after probing which profiles still
  answer.
- Search pagination is a real continuation token, not a fake "load more".

`InvidiousBackend` and `PipedBackend` remain selectable for people who prefer
routing metadata through an instance, or when a client profile gets rotated.

That last point is the honest trade-off: a vendored client profile is a moving
target. The interface boundary is what keeps that from being fatal — the UI
depends on `TubeBackend`, never on a transport.

`docs/` keeps the learning; the product does not depend on it.

---

## 3. Playback: Media3/ExoPlayer with a custom pipeline

**Observed.** `androidx.media3.exoplayer.ExoPlayer`,
`DashMediaSource$Factory`, `media3.decoder.*`, plus Google-internal wrappers
under `com.google.android.libraries.youtube.media.player.exo2.*` and bandwidth
interfaces (`SharedBandwidthSampleTracker`, `OnesieBandwidthSampleProvider`).
DRM string `widevine` is present.

**Inference.** Adaptive streaming (DASH) with Widevine for protected content and
a shared bandwidth estimator that also feeds quality selection for thumbnails
and prefetch.

**What WeTube does.** Media3, but split across three paths:

- **Muxed** progressive stream when the backend offers one (simple, robust).
- **Separate video + audio** merged into one `MergingMediaSource` when that gives
  higher quality. The UI emits a private `wetube-merge://` URI; the playback
  service expands it. This keeps Media3 out of the UI and works across the
  `MediaSession` boundary, which is where most clients get stuck.
- **HLS** for live streams, which only publish a manifest.

Track preference is H.264/AAC up to 2160p: hardware decode is near-universal,
whereas 4K VP9/AV1 often falls back to software decode on phones.

Bandwidth adaptation is delegated to ExoPlayer's default `AdaptiveTrackSelection`
— reimplementing YouTube's estimator would be guessing.

Known limitation, stated plainly: on some networks `googlevideo` throttles or
rejects URLs whose `n` parameter has not been transformed. We do not implement
that transform yet, so playback can fail even though resolution succeeded;
switching backend or network is the current workaround (`docs/ROADMAP.md`).

---

## 4. App shell and modularisation

**Observed.** `com.google.android.apps.youtube.app.watchwhile.MainActivity`
(61 methods) is the real home activity, hidden behind many `activity-alias`
entries (`Shell$HomeActivity`, `com.google.android.youtube.HomeActivity`, three
launcher icons `Shell$Icon1..3Activity`). Feature code lives in
`com.google.android.libraries.youtube.*` libraries: `player`, `offline`,
`notification`, `upload`, `account`, `search`.

**Inference.** A thin `:app` shell over dozens of Gradle modules, with aliases so
that launchers, deep links and icon packs can all target the same activity.

**What WeTube does.** One module, packages by feature
(`data/`, `player/`, `ui/home`, `ui/search`, …). Deep links (`youtu.be/ID`,
`youtube.com/watch?v=ID`) are handled in `MainActivity` and routed into the
Compose nav graph.

---

## 5. State, storage and configuration

**Observed.**

- `androidx.room.*` and two Room databases for notifications
  (`ChimePerAccountRoomDatabase`, `GnpPerAccountRoomDatabase`).
- Proto DataStore (`watchstorage.pb` string in DEX).
- `com.google.android.libraries.phenotype.*` — Google's flag/experiment system,
  with a background update receiver and a metadata holder service.
- Glide (`com.bumptech.glide.*`) with an AVIF module.

**Inference.** Watch state is protobuf on disk; server-controlled experiments
arrive through Phenotype; images go through Glide with modern codec support.

**What WeTube does.** Preferences DataStore for settings and a JSON-encoded local
library (history, favourites, resume points, local subscriptions). Signing in is
optional: an in-app browser session is harvested into the app's private storage
and used only for the web surfaces, so feeds and subscriptions can be personal
while playback stays anonymous. No experiments, no remote flags. Coil for
images (OkHttp-backed, Coroutine-friendly).

---

## 6. Offline and background

**Observed.** `com.google.android.libraries.mdi.download.*` (MDD) with a
`ForegroundDownloadService`, `BackgroundPlayerService` with
`foregroundServiceType="mediaPlayback"`, `MainAppMediaBrowserService`,
`OfflineKeepAliveService`, `SmartDownloads*` settings screens.

**What WeTube does.** `PlaybackService : MediaSessionService` owns ExoPlayer, so
audio keeps playing with the screen off and a media notification is generated by
Media3. Downloads and smart-download scheduling are on the roadmap, not faked.

---

## 7. Permissions worth noticing

The manifest asks for far more than playback needs: camera, microphone,
contacts, fine location, phone state, `SYSTEM_ALERT_WINDOW`, AD_ID, NFC,
`DETECT_SCREEN_CAPTURE`, and a `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

**What WeTube does.** Internet, network state, wake lock, foreground media
playback, notifications. Nothing else. There is no analytics SDK and no ad SDK
in the dependency graph.

---

## 8. Feature matrix: what we ported and what we deliberately skipped

| Official behaviour | WeTube |
|---|---|
| Server-driven Litho/Elements UI | Hand-written Compose (intentional) |
| gRPC InnerTube + attestation | Direct InnerTube over JSON (VR + WEB profiles) |
| DASH + Widevine | Media3: progressive, merged A/V, HLS for live; no DRM content |
| Ads, ad breaks, ad personalisation | **None** |
| Telemetry, AD_ID, experiments | **None** |
| Smart downloads / MDD | Roadmap |
| Live chat | Roadmap |
| — | **SponsorBlock auto-skip** (new) |
| — | **Return YouTube Dislike** (new) |
| — | **Instance failover** (new) |
| — | **Local-only library, optional account** (new) |
| — | **Backup file to Drive/anywhere** (new) |
| — | **Shorts section** (new) |

---

## Legal / ethical note

This was a learning exercise on a publicly downloadable package, using static
analysis only. No DRM was bypassed, no service was scraped at scale, and no
decompiled code, resource, or string table was copied into this repository.
WeTube talks only to independent, documented APIs that the user configures.
Review YouTube's Terms of Service and your local law before distributing a
client that talks to YouTube-derived services.
