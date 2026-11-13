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
  `sectionListContinuation` and `itemSectionContinuation` shapes work.
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

Backup & restore writes one JSON file through the system file picker. The file
contains the library and preferences, never cookies or accounts, and can be
stored in Google Drive, on a USB stick, or on another phone.

## 6. Background playback

`PlaybackService : MediaSessionService` owns the player, so audio keeps playing
with the screen off by default. The setting **Keep playing in the background**
can turn this off: the root composable observes the activity lifecycle and
pauses playback on `ON_STOP`. Both behaviours use the same media session, so the
notification and lock-screen controls stay correct either way.

## 7. Permissions

Internet, network state, wake lock, foreground media playback and notifications.
Nothing else. There is no analytics SDK and no ad SDK in the dependency graph.

## 8. Feature set

| Area | WeTube |
|---|---|
| UI | Hand-written Jetpack Compose, dark and light |
| Transport | Direct InnerTube over JSON, plus Invidious and Piped |
| Playback | Progressive, merged A/V, HLS live |
| Player panel | In-player gear: quality, speed, captions, audio-only, background, SponsorBlock, dislikes |
| Subtitles | Tracks from the video itself, CC menu, YouTube auto-translation with fallback |
| Gestures | Hold for 2×, tap to pause in Shorts, vertical pager |
| Comments | Native top/newest feed with paging, likes, reply counts and avatars; browser fallback |
| Shorts | Dedicated swipe feed, own progress bar, captions, like/dislike/comment counts, tappable channel, pause indicator |
| Library | History, favourites, resume points, local subscriptions |
| Accounts | Optional, multiple, removable; cookies excluded from backups |
| Data portability | JSON backup through the system file picker |
| Extras | SponsorBlock auto-skip, Return YouTube Dislike |
| Ads / telemetry | None |

## 9. Known limits

- Age-restricted videos may require a signed-in browser session. WeTube does not
  fake YouTube's JavaScript challenge; the player says which wall it hit.
- Some networks present a bot check to any third-party client. Nothing is
  bypassed.
- Machine-translated caption tracks are rate-limited by YouTube on some
  networks. The original captions are shown instead.
- Comments are read-only: liking, replying and reading replies need a signed-in
  session. The sheet offers the watch page in the browser instead.
- Downloads, live chat, casting to an external device, and two-way YouTube
  playlist/subscription sync are not implemented yet; see `ROADMAP.md`.

## Legal note

WeTube is an interoperability project. It does not bypass DRM, does not scrape
at scale, and does not redistribute proprietary code or assets. It talks only to
the endpoints that a normal browser session uses. Review YouTube's Terms of
Service and your local law before distributing or using a third-party client.
