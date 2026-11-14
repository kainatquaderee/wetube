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
│   ├── local/     history, favourites, resume, subscriptions, watch later, queue, playlists, backup (DataStore)
│   ├── model/     UI-facing models (no transport DTOs leak out)
│   └── prefs/     settings
├── player/        MediaSessionService, stream resolver, captions, controller
├── sync/          account sync: reconciler, write planner, engine, capability check
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

**Recovery across client profiles.** A stream URL can be rejected at play time —
it expires, or the CDN refuses it. The player re-resolves the video and resumes
at the position the viewer had, rather than stopping on an error, up to three
times. Because the leading `IOS` client is where most playable URLs come from, a
naive retry would ask it again and get the same rejection; so a recovery first
asks the backend to drop its leading profile, and the next resolve starts at the
`ANDROID` client — whose single progressive file always plays — still falling
through to `ANDROID_VR`. A failure a retry cannot clear (a decode or container
error) is shown to the viewer with an explanation instead of being swallowed, and
a download being played is re-opened from disk before the network is consulted.
The Shorts feed shares this recovery for the clip on screen: a refused or expired
Short URL is re-resolved through the next profile before the "YouTube refused
this clip" message is shown, and the attempt budget resets on each new clip.

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

### Offline downloads

A video the user asks to keep is recorded in a device-local store — its own
DataStore key, one entry per video, kept in enqueue order — and fetched as a
**single muxed progressive file**: one request, one file, with no separate audio
track to merge. The store is never synced and is deliberately left out of
backups: the media bytes live in the downloads directory, which a backup does
not carry, so a restored record could point at a file that is no longer there.

The fetch is resumable. It streams into `<name>.part` and only renames that file
onto the final name once the body is complete, so a partial download is never
presented as a playable file. A resume sends `Range: bytes=<offset>-` from
whatever the part file already holds, and a server that ignores the range simply
restarts the part. A record left RUNNING by a process that stopped mid-download
reads back as queued — in memory only — so the work is picked up again rather
than stranded.

The queue is drained by a foreground `Service` of type `dataSync`
(`DownloadService`), not by a background scheduler. It walks the store front to
back, **one file at a time**, skipping entries it cannot resolve and marking the
rest failed rather than stopping, so one dead video cannot strand the queue
behind it. A single aggregate notification shows the progress (determinate while
the transfer's size is known, indeterminate until then) and offers one Cancel;
cancelling stops the transfer and removes its records, and a download cancelled
this way is never recorded as a failure. Progress itself is in memory only — the
store records state transitions, nothing more, because a process that dies
mid-download has no progress worth keeping. Removing an entry stops its download
and deletes its files, part file included. When the queue is empty the service
takes its notification away and stops.

There is deliberately **no WorkManager**. The work is one user-driven,
cancellable transfer whose progress the user is watching, not a set of
constraints to satisfy later: a foreground service keeps the process alive and
puts a live, cancellable notification in front of the user, with no scheduler
dependency in the graph. The controller the UI talks to (`DownloadManager`) holds
no `Context` and no UI, so it is tested against a real store and downloader with
no device involved; only the directory to write into and the service start are
supplied by the app.

The UI half keeps the same separation. The player asks the manager for a
completed file before it resolves a network source and plays the local one
whenever it exists and output is not being cast; if the watch-details request
then fails — the case a downloaded video is meant to survive — it falls back to
the thin metadata saved with the entry rather than refusing to play. The
Library's **Downloads** tab reads the manager's flows directly, with no
ViewModel of its own, so the download graph is built only when that tab is
opened. A storage cap in Settings is read through a supplier rather than
captured as a value, so a limit changed there takes effect without rebuilding the
manager; when finished media exceeds it, the oldest finished download is removed
first and the newest is always kept, because evicting the file a user just waited
for would be perverse.

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

Preferences live in DataStore. History, favourites, resume points, local
subscriptions, a Watch Later list, a play queue, local playlists and a bounded
cache of video metadata are a JSON-encoded library on disk. Watch Later, the
queue, the playlists and the metadata cache are device-local: they are never part
of account sync, and local playlists carry a locally generated `local:` id so
they can never be mistaken for a remote playlist. Because a local playlist stores
only video ids — so a large one never pins a copy of every item it names — the
cache holds the display fields of up to 1000 of them, newest first, and lets the
rows render without a network call; it travels in a backup but never syncs.
Signing in is optional: an in-app browser session is kept in private
storage and used only for the web surfaces, so feeds and subscriptions can be
personal while playback stays anonymous.

Existing subscriptions can be brought in from a Google Takeout
`subscriptions.csv` or from an OPML file exported by another client (NewPipe,
FreeTube, ...). The parsers are pure and dependency-free — a small CSV field
splitter and the JDK's DOM parser, with no Android API — and an import is a
local write only, so it reaches the account only on an explicit sync.

Home's **Subscriptions** feed is built from that same device-local list, with no
account and no sign-in: each subscribed channel's recent uploads are fetched with
bounded concurrency and merged newest-first, using the relative `publishedText`
("3 hours ago") as the only ordering signal a feed item carries. A channel that
fails is skipped rather than blanking the feed, and the first results are shown
before the slowest channel answers.

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
playback preferences that v1 already carried; **v3** adds the two account-sync
toggles, and **v4** adds the device-local Watch Later, play queue and playlists.
A restore validates the format and accepts only versions `1..4` (a file from a
newer build is rejected rather than guessed at), refuses a file above 5 MB
before parsing it, and caps the restored sponsor-category set and every restored
list. Because a backup can be written on one device and opened on another —
including through Drive — its free-text host fields are re-validated when they
are restored, and anything that fails the check falls back to the built-in
default.

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
| Library | History, favourites, resume points, local subscriptions, Watch Later, play queue, local playlists, video metadata cache |
| Downloads | Save a video's muxed file for offline play, a Library **Downloads** tab with live progress, retry and remove, play-from-disk with an offline metadata fallback, and a storage cap that trims the oldest finished download first |
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
  reopening the video. A YouTube link pasted into Search reuses the same host
  list and id check (`youtubeVideoId`), so a pasted link and a tapped link open
  the same way instead of the pasted text being searched for literally.
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
- URLs from the last-resort `ANDROID_VR` profile carry YouTube's `n` throttling
  parameter, which is designed to be unscrambled by a JavaScript transform the
  player deliberately does not run. Those URLs therefore slow to a crawl after
  the first few tens of megabytes. The `IOS` profile, the normal playback source,
  hands back URLs whose `n` is already applied and downloads at full speed, so
  this only touches the rare video the other profiles refuse; bundling a
  JavaScript engine for that narrow fallback is not worth the weight (see
  `ROADMAP.md`).
- Machine-translated caption tracks are rate-limited by YouTube on some
  networks. The original captions are shown instead.
- Comments are read-only: liking, replying and reading replies need a signed-in
  session. The sheet offers the watch page in the browser instead.
- Live chat is not implemented yet; see `ROADMAP.md`. Account
  sync (§12) pulls subscriptions and a favourites mirror down from the account
  and pushes local changes only after the user consents on the first sync; watch
  history is never synced, and neither are Watch Later, the play queue, local
  playlists or the video-metadata cache — they are device-local and travel only
  in a backup. Casting covers
  DLNA/UPnP renderers on the local network only — Google Cast needs a registered
  receiver app id, and audio routing to Bluetooth/USB is left to the system
  output picker.

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

## 12. Account sync

Sync reconciles the local library with a signed-in YouTube account. It is
two-way but **pull-first**: the account is fetched and applied before anything is
ever written back, and the very first sync stops *before* its first write to ask
the user. The worst a bug can do is then "we showed you the wrong list", never
"we changed your account". Watch history is permanently out of scope — no history
endpoint is called, researched or implemented.

**Shape of the code.** The risky half is pure. `SyncReconciler` is a
side-effect-free three-way merge, `SyncWritePlanner` orders and batches the ops
it produces, and `SyncEngine` is the state machine that reads, applies, gates and
pushes. Everything the engine reaches outside itself goes through two seams —
`SyncDeps` for local reads (the account key, settings, consent) and `SyncBackend`
for the transport — so the whole engine runs in unit tests against fakes, with no
account, socket or `Context`.

**Identity without identity.** Sync state is keyed by `accountKey`, a salted
SHA-256 of the account's own channel id (or, when that cannot be read, of the
SAPISID value), truncated to 128 bits (`core/AccountKeys.kt`). The raw account id
is never stored beside it and the key cannot be reversed into it; every
`SyncEntry` and `Tombstone` carries the key of the account it belongs to, so a
change queued by one account is *quarantined* rather than leaked into another
after a switch.

**Presence, not time.** InnerTube returns no per-subscription or per-membership
timestamp, so the merge reasons about presence only. For each id the reconciler
reads three booleans — was it in the last baseline (`SyncEntry.basePresent`), is
it local (and not tombstoned), is it remote — and applies one decision table
(`SyncReconciler.diff`). `SyncEntry.localUpdatedAt` is recorded but never used to
resolve a conflict, because there is nothing authoritative to compare it to; a
locally edited playlist title instead wins outright. After a successful apply the
baseline is restamped (`LibraryStore.markSynced` and the `applyPull` helpers) so
the next merge knows what the account had.

**Three streams, one namespace rule.** Subscriptions, favourites and playlists
reconcile independently, because a channel, a video and a playlist can share a
string id. Each record is namespaced by kind through `subscriptionKey` /
`favouriteKey` / `playlistKey` (`sub:`/`fav:`/`pl:`) — the same helpers the store
uses — so the merge namespace and the on-disk namespace cannot drift apart.

**The gate.** `SyncEngine.runSync` pulls, merges and applies the pull locally,
then, if the account has never consented (`AccountSyncState.firstSyncDone`),
parks in `SyncStatus.AwaitingConsent` with a `SyncPreview` of counts only —
nothing about the account beyond "N subscriptions, M favourites". Both the
Library and Settings render the same `FirstSyncConsentDialog`; `confirmFirstSync`
runs exactly the plan the preview described and `dismissFirstSync` writes
nothing. A change made before consent queues as a `pendingOp`, and `onLocalChange`
refuses to push it, because `firstSyncDone` gates the incremental path too.
`onLocalChange` is called from every surface that mutates a synced entity — a
favourite or a subscription from the player, a Short, a channel page, or an
unsubscribe from the Library — and debounces a burst of them into one push, so a
change made anywhere reaches the account rather than only one made in the Library
tab.

**The push.** `SyncWritePlanner` orders and batches the ops (creates, renames,
adds, subscribes, removes, unsubscribes) under a per-sync cap, so a flurry of
local edits cannot become an unbounded burst. Each op is retried only while its
result is `OpResult.Retryable` (0.5s / 2s / 8s); a `Terminal` or `AuthRequired`
result pauses the whole push and keeps the op's `pendingOp` rather than losing the
change. `OpResult.classify` maps a status code to that policy and never guesses
`Terminal` for an unknown code, so a queued change is not stranded.

**Never deleting what we did not make.** The only playlist sync will delete is
the private "Favourites" mirror WeTube itself created, identified solely by the
id remembered in `AccountSyncState.favouritesPlaylistId` — never by a title — so
a playlist the user made is never adopted or removed. Favourites have no native
YouTube playlist, so the first consented push creates one, seeded with the current
favourites in the create call itself; every later push reuses that same remembered
id, and re-creates only if the user deleted the mirror. The transport never
offers a candidate mirror to adopt — `SyncDeps.favouriteTarget` always answers
"create on first push", and the pull side consults the persisted id rather than
scanning the account's playlists for a matching title.
Liked videos (`LL`) are never written, because adding to `LL` is a *like*: the
mirror is a separate private playlist and the user's real likes are untouched. A
removal needs the membership's `setVideoId`; without it the item is pull-only
rather than guessed at. Whether a video or channel that vanished from the account
is removed locally is opt-in (`mirrorRemovals`, off by default), so a sync can
never silently drop something from the phone.

**Transport and honesty.** `InnerTubeSync` owns the request shapes (recorded in
`SYNC_PROTOCOL.md`) and all session plumbing — context envelope, auth headers,
visitor id, endpoint URL — lives behind the injectable `SyncTransport`, so the
interpreter is testable against no account at all. The write endpoints are the
least verifiable part: they are hand-rolled against the web client and can change
without notice, so they ship `UNVERIFIED`, behind the consent gate. In debug
builds only, `SyncCapabilityCheck` answers "are they live for this account?" with
non-mutating read probes and an explicitly-tapped write probe that creates and
immediately deletes one private playlist of its own. Sync is off by default
(`syncEnabled=false`) and unavailable on the Invidious/Piped backends, which have
no account API; the two toggles travel in the versioned backup.

## Legal note

WeTube is an interoperability project. It does not bypass DRM, does not scrape
at scale, and does not redistribute proprietary code or assets. It talks only to
the endpoints that a normal browser session uses. Review YouTube's Terms of
Service and your local law before distributing or using a third-party client.
