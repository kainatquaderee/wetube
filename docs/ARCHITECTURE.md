# Architecture

WeTube is an independent, clean-room implementation written from scratch. It is
not affiliated with, endorsed by, or connected to YouTube or Google. The UI, the
networking layer and the player were written for this project; no code,
resources or assets from a proprietary application are included.

YouTube does not publish an API for third-party clients. WeTube talks to the
same public InnerTube JSON endpoints that a signed-out or signed-in browser
session uses. Those endpoints are not contractual, so their behaviour can change
without notice. The interface boundary described below exists to keep that risk
away from the UI.

## Modules

```
app/src/main/java/dev/wetube/app/
├── core/          text, URL and video-id helpers shared by every layer
├── data/
│   ├── backend/   TubeBackend + InnerTube (default), Invidious, Piped
│   ├── auth/      optional Google session, multiple accounts
│   ├── extras/    SponsorBlock, Return YouTube Dislike
│   ├── local/     history, favourites, resume, subscriptions, backup (DataStore)
│   ├── model/     UI-facing models (no transport DTOs leak out)
│   └── prefs/     settings
├── player/        MediaSessionService, stream resolver, captions, controller
└── ui/            Compose screens, one package per feature
```

One module, packages by feature. `MainActivity` handles deep links
(`youtu.be/ID`, `youtube.com/watch?v=ID`) and hands them to the Compose nav
graph; the same route is used from every screen.

## Shared helpers (`core/`)

A handful of rules are used from more than one layer, so they live in `core/`
rather than being re-derived at each call site:

- `core/Text.kt` — `decodeEntities` turns the HTML entities YouTube leaves in
  JSON titles and descriptions into plain text; `compactCount` and `parseCount`
  are the one way a count is printed and read back (`222.0K` becomes `222K`, and
  a localised label like `1.2M` parses to `1200000`); `formatSpeed` is the one
  source of the `1×` / `1.25×` / `1.75×` labels the player and Settings share.
- `core/Urls.kt` — `sanitizeBackendHost` canonicalises a free-text backend host,
  `isAllowedSignInUrl` is the sign-in navigation allow-list, and `encodeUrlValue`
  percent-encodes a dynamic value before it is placed in a path or query.
- `core/VideoIds.kt` — `isValidVideoId` checks an 11-character YouTube id with an
  anchored pattern, so a value that will be concatenated into a route or a URL
  cannot smuggle a path (`..`, `/`) through it.

## 1. Transport

`TubeBackend` is the only contract the UI knows. Adding a backend means adding
one class and selecting it in Settings.

The default `InnerTubeBackend` speaks plain JSON over HTTP:

- **Client profiles by surface.** Text surfaces (search and feeds) use `WEB`
  because it returns the cards with duration and upload date. Playback prefers
  the `IOS` client, which hands back adaptive video and audio URLs for every
  resolution without a throttling parameter; `ANDROID` and `ANDROID_VR` are
  fallbacks, and the signed `ANDROID_VR` payload is built lazily only when the
  others come back without usable URLs. Profiles are values in one place, so they
  are easy to adjust when a surface changes.
- **Generic JSON walking.** Responses are read as a JSON tree rather than being
  bound to fixed DTOs. A field YouTube adds or removes does not break the app;
  the parser simply finds what it needs.
- **Continuation tokens.** Search and feeds use the token the server returns,
  not a fabricated "load more".

`InvidiousBackend` and `PipedBackend` are selectable for people who prefer to
route metadata through an instance they choose. Playback always uses whatever
stream URLs the active backend resolves.

No attestation, PoToken or JavaScript challenge is generated or faked. When
YouTube insists on one, the app reports the wall instead of pretending to pass
it.

## 2. Playback

Media3/ExoPlayer runs inside a `MediaSessionService`, so playback survives
activity recreation and the media notification is a real media session.

The resolver picks one of three paths:

- **Muxed progressive stream** when the backend offers one. One connection, no
  independent URL expiry.
- **Separate video + audio** merged by `MergingMediaSource` when that gives
  better quality. The UI emits a private `wetube-merge://` URI and the service
  expands it, so Media3 types never cross into Compose.
- **HLS** for live streams. A manifest is adaptive by definition, so there is
  nothing to pin inside it at this layer.

**Quality.** `StreamResolver` takes a preferred height. *Auto* prefers H.264/AAC
up to 1080p — hardware decode is near-universal on phones, while 4K VP9/AV1 often
falls back to software decode — and still allows 1440p/2160p when nothing smaller
exists. A pinned height returns the closest match (the exact height, else the
largest below, else the smallest above), and a muxed track wins a tie because its
audio cannot expire out from under its video. Switching quality rebuilds the
`MediaItem` from the streams already in hand and resumes at the same position, so
it costs no network call; the choice is stored as the default and can also be set
in Settings. Bandwidth adaptation inside a single stream stays with ExoPlayer's
`AdaptiveTrackSelection`.

The picker can offer every height because playback URLs are resolved through the
`IOS` client, whose adaptive links carry no throttling parameter and download at
full speed; the `ANDROID` and `ANDROID_VR` fallbacks expose fewer, sometimes
muxed-only, options. Live streams keep the HLS path, since a manifest is what the
live surface returns.

**In-place reloads.** Re-resolving the same video — after a quality or audio-only
change, or on retry — replaces the `MediaItem` without forcing playback to start
(`PlaybackController.load()` rather than `play()`). A paused video stays paused
and a playing one keeps playing, resuming at the same position.

**Player extras.** The info area carries an honest, read-only dislike count (the
independent Return YouTube Dislike figure, shown only while "Show dislikes" is
on) and a Share chip that sends the canonical watch URL. The channel row's
Subscribe/Subscribed button writes to the same local library the Library screen
reads. When a video finishes, its next related video appears over the final frame
and one tap swaps the media in the same player and back-stack entry. A failed
load or re-resolve is shown over the player rather than instead of the page, so
the title, channel, chips and comments stay usable below with a single retry.

**The player settings panel.** The control bar's gear opens a sheet modelled on
the official player: Quality and Playback speed open second-level lists, Captions
hands off to the CC sheet, and the remaining rows are switches (audio-only,
background playback, SponsorBlock, dislike counts) with a door into the full
Settings screen. It reads and writes the same preferences the player and Shorts
already use, so a change made mid-video takes effect immediately.

The shared player is also used by Shorts. Every `MediaItem` carries the video id
as its media id, so a screen can tell whether the player still holds the clip it
expects — this is what stops the normal player's video from appearing behind
Shorts after navigating back.

**Hold to 2×.** Pressing and holding the video surface (or a Short) switches to
2× playback; releasing restores the previous speed. In the normal player this
is an `OnTouchListener` that does not consume the event, so the native controls
keep working. In Shorts it is a Compose pointer-input modifier that also leaves
the tap-to-pause and vertical pager untouched.

## 3. Subtitles and auto-translation

Caption tracks come from the player response of the video that is already being
resolved, so opening the CC menu costs no extra metadata request. Each track
carries a signed timed-text URL.

- `fmt=json3` is appended (replacing the signed `fmt` value) to fetch the same
  track as compact JSON. An XML fallback covers tracks that ignore the format
  parameter.
- Lines are flattened to plain text and stored as `(start, end, text)` cues; the
  overlay finds the current line with a binary search, so it stays cheap at
  60 fps.
- **Auto-translate** appends `tlang=<code>` to the same signed URL. That is
  YouTube's own machine translation, so no audio or text is sent to a third
  party.
- If YouTube refuses the translated request (it is rate-limited on some
  networks), the original track is loaded instead and the sheet explains why.
  Captions never disappear silently.

Preferences for subtitles (on/off, language, translation) are persisted and
shared by the player and Shorts, which is why the CC button behaves the same in
both.

## 4. Comments

Comments ride along with the watch response. The `next` call that already fills
in likes, subscribers and related videos also carries the comment entry point:
its header holds the comment count and the sort menu, and its content holds the
continuation token for the first page of the default sort. Opening the sheet
therefore costs no extra request.

- Pages are fetched from the same `next` endpoint with a `continuation` token.
  The next token is taken from wherever the tree put it, so both
  `sectionListContinuation` and `itemSectionContinuation` shapes work. The next
  page is requested only once the list's footer is actually on screen, so a
  continuation is not fetched until the viewer reaches it.
- Comments are read from `commentThreadRenderer` nodes: author, avatar, body,
  age, like count and reply count. Requests are anonymous, which matches what
  the rest of the app does.
- **Top/Newest** both come from tokens in the same entry point, so switching
  sort is one call.
- Replies, likes and reply posting need a signed-in session on a Google surface
  the app does not impersonate. The sheet says so and offers the watch page in
  the browser through a chooser instead of pretending to support them.

## 5. State and storage

Preferences live in DataStore. History, favourites, resume points and local
subscriptions are a JSON-encoded library on disk. Signing in is optional: an
in-app browser session is kept in private storage and used only for the web
surfaces, so feeds and subscriptions can be personal while playback stays
anonymous.

**One writer, one transaction.** A preference change goes through
`SettingsStore.update { … }`, which runs the read-modify-write *inside* the
DataStore `edit` transaction rather than snapshotting a value first and writing
it back. Two overlapping updates therefore serialise instead of each resnapshotting
the same state and clobbering the other, so every distinct change survives.

**Dedupe at the edge.** Each paged list (search, home, channel, comments) reduces
to one entry per id before it reaches Compose. YouTube can repeat an item across
continuations, and a duplicate id would give two list items the same key.

Backup & restore writes one JSON file through the system file picker. The file
contains the library and preferences, never cookies or accounts, and can be
stored in Google Drive, on a USB stick, or on another phone.

The file is a versioned schema — **v2** adds the backend, the Invidious/Piped
hosts, the proxy-streams flag and the SponsorBlock categories, alongside the
playback preferences that v1 already carried. A restore validates the format and
accepts only versions `1..2` (a file from a newer build is rejected rather than
guessed at), refuses a file above 5 MB before parsing it, and caps the restored
sponsor-category set. Because a backup can be written on one device and opened on
another — including through Drive — its free-text host fields are re-validated
when they are restored, and anything that fails the check falls back to the
built-in default.

## 6. Background playback

`PlaybackService : MediaSessionService` owns the player, so audio keeps playing
with the screen off by default. The setting **Keep playing in the background**
can turn this off: the root composable observes the activity lifecycle and
pauses playback on `ON_STOP`. Both behaviours use the same media session, so the
notification and lock-screen controls stay correct either way.

While casting, the phone's ExoPlayer is paused and silent, but the media session
stays the system's control surface. `PlaybackService` forwards transport commands
to the renderer instead of the local player (section 11), and the same `ON_STOP`
handler pauses the TV through `OutputRouter` rather than the silent local
player.

## 7. Permissions

Internet, network state, wake lock, foreground media playback, notifications,
and `CHANGE_WIFI_MULTICAST_STATE`. The last is normal-level and held only while
the cast sheet is open: an SSDP `M-SEARCH` is sent from an ordinary socket, and
Android drops its unicast answers without it. It is acquired and released around
each sweep, never at app start and never in the background.

Nothing else. There is no analytics SDK and no ad SDK in the dependency graph.

## 8. Feature set

| Area | WeTube |
|---|---|
| UI | Hand-written Jetpack Compose, dark and light |
| Transport | Direct InnerTube over JSON, plus Invidious and Piped |
| Playback | Progressive, merged A/V, HLS live |
| Player panel | In-player gear: quality, speed, captions, audio-only, background, SponsorBlock, dislikes |
| Player extras | Read-only dislike count, Share, Subscribe/Subscribed on the channel row, end screen with the next related video |
| Casting | DLNA/UPnP `MediaRenderer` discovery and playback on the local network, with a remote-control page |
| Search | Autocomplete and continuation paging; keyboard dismissed on submit, next page loaded at the end of the list |
| Subtitles | Tracks from the video itself, CC menu, YouTube auto-translation with fallback |
| Gestures | Hold for 2×, tap to pause in Shorts, vertical pager |
| Comments | Native top/newest feed with paging, likes, reply counts and avatars; browser fallback |
| Shorts | Dedicated swipe feed, own progress bar, captions, like/dislike/comment counts, tappable channel, pause indicator |
| Library | History, favourites, resume points, local subscriptions |
| Accounts | Optional, multiple, removable; cookies excluded from backups |
| Data portability | Versioned JSON backup through the system file picker |
| Extras | SponsorBlock auto-skip, Return YouTube Dislike |
| Ads / telemetry | None |

## 9. Input validation and hardening

- **Backend hosts.** A host typed in Settings, or restored from a backup, is run
  through `sanitizeBackendHost` before it is ever concatenated into a request URL.
  It must be `https`, must not carry userinfo, must not be `localhost`/`.localhost`,
  and must not be a loopback, private, link-local, carrier-grade-NAT, benchmarking
  or otherwise reserved IP literal (a hostname is not resolved, so nothing here
  triggers a lookup). Anything else is dropped and the caller falls back to the
  built-in default. The function also returns the bare origin, so a caller cannot
  smuggle a path, query or fragment in front of the `/api/v1` suffix the backends
  append.
- **Cleartext off.** `network_security_config.xml` sets
  `cleartextTrafficPermitted="false"` with no per-domain exemptions, so a mistyped
  or tampered host cannot silently downgrade a request to `http://`; `sanitizeBackendHost`
  enforces the same rule at the URL layer. The one raw-HTTP path is DLNA control,
  which necessarily targets a private `http://` control URL on the LAN. It is not
  a policy-consulting stack: `RawSocketLanHttp` writes the request over a plain
  socket, so no exemption is added to the config, and the destination host has
  already been forced to a private or link-local literal by `LanAddress`. A
  renderer cannot redirect that traffic at an internet host.
- **Dynamic URL values.** Video ids, search queries and region codes are
  percent-encoded with `encodeUrlValue` before they are placed in a path or query.
- **Deep links.** `MainActivity` accepts only the declared hosts (`youtu.be`,
  `www.youtube.com`, `m.youtube.com`) and validates the id with `isValidVideoId`
  (`^[A-Za-z0-9_-]{11}$`) before it becomes a navigation route. A cold launch
  consumes the link once, and a recreation replays the original intent without
  reopening the video.
- **The sign-in WebView.** Navigation is allow-listed to the Google/YouTube https
  apexes and their subdomains (`isAllowedSignInUrl`); every other host and every
  non-https scheme is refused. The view runs with file and content access off, no
  file-URL universal access and mixed content never allowed, and it is destroyed
  explicitly when the screen is popped so it cannot outlive its Activity.
- **Age-gate hint.** The locally remembered set of age-restricted ids is capped
  FIFO at 500, so the best-effort hint cannot grow without bound.

## 10. Known limits

- Age-restricted videos may require a signed-in browser session. WeTube does not
  fake YouTube's JavaScript challenge; the player says which wall it hit.
- Some networks present a bot check to any third-party client. Nothing is
  bypassed.
- Machine-translated caption tracks are rate-limited by YouTube on some
  networks. The original captions are shown instead.
- Comments are read-only: liking, replying and reading replies need a signed-in
  session. The sheet offers the watch page in the browser instead.
- Downloads, live chat, and two-way YouTube playlist/subscription sync are not
  implemented yet; see `ROADMAP.md`. Casting covers DLNA/UPnP renderers on the
  local network only — Google Cast needs a registered receiver app id, and audio
  routing to Bluetooth/USB is left to the system output picker.

## 11. Casting / external devices

`OutputRouter` is the single source of truth for where playback happens, exposed
as `PlaybackOutput.Local` or `PlaybackOutput.Remote(target)`. Both the player UI
and `PlaybackService` read that flow, so there is no window in which the phone
believes it is playing while a TV also does.

**Discovery.** `NetworkRemoteDiscovery` sends SSDP `M-SEARCH` datagrams and reads
the answers, keeping only devices whose description and whose every resolved
service URL (AVTransport, RenderingControl, ConnectionManager) is a private or
link-local literal (`LanAddress.isCastingTarget`). A description is fetched only
from a literal on the user's own network, and the cloud-metadata address
(`169.254.169.254`) is refused even though it is technically link-local, so a
hostile responder cannot aim a control request off-LAN. It runs only while the
cast sheet is open: `start()` on open, `stop()` on dispose. Nothing scans at app
start. The `CHANGE_WIFI_MULTICAST_STATE` lock is taken per sweep, never held idle.

**Hardening.** Every document a device sends is untrusted: both the description
parser and the SOAP parser refuse any document that declares a DTD (blocking
entity expansion), the JAXP factory additionally disables external entities and
DTD/schema access, the element walks are iterative so a deeply nested body cannot
overflow the stack, and responses are capped in size, trailer count and total
read time. DIDL and SOAP values are XML-escaped. Control traffic is plain HTTP on
the LAN, which is inherent to UPnP; it never leaves the local network.

**Playing.** A cast needs one muxed URL, so `StreamResolver.pickProgressive`
supplies it and `VideoDetail.toRemoteMedia` packages the metadata a TV shows
(title, channel, artwork). `RemotePlaybackManager` sends `SetAVTransportURI` +
`Play` over SOAP and then polls `GetPositionInfo`/`GetTransportInfo`, which is
what feeds the remote surface's live position and play state. The local player is
paused only after the renderer accepted the handoff; if connecting fails, local
playback is untouched and the UI shows the failure with its recovery action.

**The remote surface.** While `OutputRouter` reports remote, `PlayerScreen` does
not attach a controller to its `PlayerView` and does not derive buffering,
position or duration from ExoPlayer — those would all be stale. It draws
`RemoteSurface` (artwork, "Playing on <name>", transport, a scrubber committed on
release) and routes seek/volume/play-pause to the router. Speed and captions are
disabled while casting because the renderer owns them; a quality or audio-only
change instead re-casts the same video at the renderer's live position.
SponsorBlock skips go through `OutputRouter.seekTo`. "Play here instead" calls
`returnLocal`, which disconnects and loads on the phone at the position the TV
had reached.

**Media session.** The blueprint's `onPlay`/`onPause`/`onSeekTo` callbacks do not
exist in Media3 1.5.1. The real hook is
`MediaSession.Callback.onPlayerCommandRequest(session, controller, playerCommand)`.
It is marked deprecated in favour of limiting `getAvailableCommands()` or
`setAvailableCommands`, but that only hides a command — it cannot route it to a
renderer — so the callback is still the correct (and only) interception point.
Returning a non-zero `SessionResult` code vetoes the default local handling and
returns that code to the controller; `RESULT_SUCCESS` (0) lets the command run
locally. So while remote: `PLAY_PAUSE` toggles the renderer and is vetoed
(`RESULT_INFO_SKIPPED`), `STOP` pauses it and is vetoed, `PREPARE` is vetoed, and
`SEEK_BACK`/`SEEK_FORWARD` are computed from the renderer's live position and
vetoed rather than trusting the paused local player's mirror. Absolute seeks fall
through to the local player and are then mirrored to the renderer from
`onPositionDiscontinuity` (`DISCONTINUITY_REASON_SEEK`) while the session is
active. The notification therefore shows the local player's stale transport
state; play/pause and the scrubber in the app are the authoritative controls.

**Forward compatibility.** `LocalNetworkAccess.canScan` is the one place that
knows about a future runtime gate on local-network scanning. It returns `true`
today; when `Build.VERSION.SDK_INT >= 37` the gate can be enforced there without
touching discovery.

## Legal note

WeTube is an interoperability project. It does not bypass DRM, does not scrape
at scale, and does not redistribute proprietary code or assets. It talks only to
the endpoints that a normal browser session uses. Review YouTube's Terms of
Service and your local law before distributing or using a third-party client.
