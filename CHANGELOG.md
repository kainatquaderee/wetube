# Changelog

Notable changes to WeTube, newest first. Grouped as Added, Fixed, Changed and
Security. This release is 0.3.12 (versionCode 32).


## 0.3.12 — the engine stops taking the app down with it (2026-11-18)

Some videos opened the embedded page and the app disappeared: a white pane, then
the home screen. Nothing landed in the crash log, which is why it took a device
to explain it.

### Fixed

**The engine is built when the process starts, not when a player opens.** Built
from inside the player, the runtime was created while the app was already
foreground, and Gecko replayed that resume into itself before its own thread
existed: a null handler, an uncaught exception, and Gecko's crash handler turning
it into a silent process kill. Building it in `Application.onCreate` happens
before the first resume, so nothing is replayed.

**Only the app process builds it.** `onCreate` runs once per process, and Gecko
spawns a content process per tab, a GPU process and a crash helper. A runtime
built inside those is fatal, and the crash helper refuses outright, so the
warm-up is scoped to the default process.

**A failed warm-up is visible instead of a white pane.** The player says the
engine is unavailable and leaves the rest of the app working, instead of opening
a blank frame that outlives the engine.

## 0.3.11 — a browser engine for the one wall our own player can't pass (2026-11-17)

Some videos stream only to a session a browser can vouch for, so we now carry a browser: Firefox's engine, inside the APK. The rest of the release is the account's own surfaces — its home page, its playlists, its comments — read and written as the account picked in Settings.

### Added

**A browser engine for the one refusal we can't answer.** A video can refuse to play for any client the app asks as, and the refusal arrives as a sentence in `playabilityStatus.reason`. One such refusal has a way past it — an age wall — and only that one opens YouTube's own watch page in an engine we ship. We ship our own engine instead of using Android's WebView because WebView borrows the system's provider and we cannot answer for how that provider behaves; GeckoView brings its own network stack, cookie store and media pipeline, which is exactly why it plays what WebView did not. It is reachable only through that one refusal, only with an account signed in and `Show mature content` on, and every other video keeps the surface it had.

**The embedded page plays as the account selected in Settings.** The engine keeps its own cookie store and its Java API offers no way in — the storage controller only clears and the request loader only adds a header — so the session travels over the one route Mozilla supports: a bundled extension that writes the cookies through `browser.cookies` before the page loads. The app's session only ever travels into that profile and never back out: the round clears the profile's YouTube cookies before storing the account's, what it stores is session-scoped, and a reply carries a name and a count rather than a value. A video opened there therefore plays with that account's likes, subscriptions and history.

**The account's own home page.** Home now asks the surface the web front page asks, so the chip bar is the server's, each chip has its own continuation when it is picked, and the answer arrives as ordered blocks: a titled shelf keeps its place instead of being flattened into one recommendation list. Transports that send no shelves keep the flat list.

**Shorts where a search puts them.** A search result page is read as blocks now, so Shorts rows and a channel's "Latest …" shelf keep their place between the video rows instead of being dropped; tapping a clip opens it in Shorts, the way a Home shelf already did.

**The account's playlists in Library.** Read-only mirrors of the account's own playlists, pulled by sync and scoped to the account that owns them. History and playlists no longer share one bucket across accounts, and a playlist whose pull fails never fails the sync.

**Comments as the account.** Signed in, we post, like and reply from the sheet, over the signed web envelope's `comment/create_comment`, `comment/perform_comment_action` and `comment/create_comment_reply`. The parameters come from the page's own tokens, which is why the signed-out sheet offers no like or reply affordance. Replies are read here too, in a pane of their own that pages in like the thread list, and the sheet still offers the watch page when YouTube refuses the thread.

### Fixed

**A refusal says which wall it is.** The player used to answer every failed playback with the same sentence. It now names the wall in its own words for the refusals it recognises — an age wall, a bot check, a missing video, a dead server — and an age wall reaches the screen as its own type rather than as a sentence the screen matches, so the way past it is offered only when it is really there.

**The way past the wall is named.** The copy no longer claims playback is impossible: it says which wall was hit, offers the two routes we have, and quotes only the app's own words rather than borrowing YouTube's.

**The release build minifies again with the engine in it.** The engine ships SnakeYAML, whose JavaBean support reaches `java.beans`, which Android does not have, so R8 failed the release build on five missing classes. The app never calls the engine code that reaches them, so generated `-dontwarn` rules put the release packages back: 6.3 MB of dex after shrinking.

**Sync reads the account's playlists, counts and edits from the real responses.** Three assumptions about what the web client answers were wrong: the item count is a thumbnail badge rather than metadata text, a large playlist's rows arrive in a shape the parser did not know, and an edit goes to `browse/edit_playlist`, not `playlist/edit`. So membership and counts came back empty and edits were answered with a 404. All three now come from the shapes the responses carry, and a failed item pull keeps the stored ids so the next sync retries.

### Changed

**The embedded player went fullscreen.** The watch page's own fullscreen is the only way to see the picture without its chrome, and the host had no way to ask for it. The app now grants the request and stops drawing its bar while it is up, and the page's own control brings it back.

**The debug log names the sync call and its answer.** Every InnerTube call logs its path and status, so a failed sync names the endpoint instead of ending in one unexplained failure.

## 0.3.10 — a rotate button, and a top bar clear of the status bar (2026-11-16)

The player's top bar gained a manual rotate control, and the bar no longer sits
under the status bar when the phone is held sideways.

### Added
- **Rotate button.** A rotation control in the player's top bar flips the picture
  between portrait and landscape on a tap, so a viewer whose device is locked to
  one orientation — or lying flat with auto-rotate off — can turn the video
  without leaving the app or reaching for the system settings. The choice
  follows whatever is on screen: a landscape viewer gets portrait, and portrait
  gets landscape. Leaving the player returns the window to the system's own
  auto-rotate behaviour, so the phone does not stay pinned afterwards.

### Fixed
- **The player's top bar no longer overlaps the status bar in landscape.** The
  video area is a 16:9 slice of the page width, which on a sideways phone is
  taller than the page itself. An oversized block is centred, so its top edge —
  and every button pinned to it — was pushed up under the status bar, where the
  clock and signal icons sat on top of Back, Settings, Rotate and Full screen and
  the taps never reached them. The video area is now clamped to the page height,
  which keeps the top bar anchored below the status bar and its buttons fully
  tappable in both orientations. The buttons keep their full touch-target size;
  only their position changed.

## 0.3.9 — audio-only playback is truly audio-only (2026-11-16)

Turning on "Audio only" no longer leaves a picture on screen: the video area
becomes a black card labelled "Audio only mode", and that card follows the video
into the floating window.

### Fixed
- **"Audio only" now actually drops the picture.** A muxed fallback track could
  still carry a video frame, so the setting looked like it did nothing. The
  player now hides the video surface whenever audio-only is on and stands a
  black "Audio only mode" card in its place; the sound, the native controls and
  the progress bar are untouched.

### Changed
- **Audio-only playback can float.** Back now docks an audio-only video into the
  mini-player — and swiping home hands it to the system picture-in-picture
  window — instead of leaving the player, and the floating window shows the same
  black "Audio only mode" card, so a listener can keep the sound going while
  browsing.

## 0.3.8 — the on-screen back arrow docks like the back gesture (2026-11-16)

The arrow in the player's top bar did something different from the system back
gesture; now the two are one and the same.

### Changed
- **The back arrow shrinks the video into the mini-player, just like the back
  gesture.** Previously the top-bar arrow always left the player screen, so a
  viewer who tapped it lost the floating window they would have got from
  swiping or pressing back. Both entries now share a single path: leave
  fullscreen first, then dock the playing video into the window, and only leave
  the screen when there is nothing to float.

Pressing back while a video plays now shrinks it into a small floating window
over the app instead of leaving it.

### Added
- **In-app mini-player.** Back from the player docks the running video into a
  small window anchored to a corner, and the screen it came from — feed, search,
  a channel, the library — stays fully usable underneath. The window keeps
  playing, can be dragged out of the way of whatever is behind it, opens the
  full player again on a tap, and has a close button that stops playback and
  dismisses it.
- **Opening the video again is the same video.** Tapping the window reopens the
  full player at whatever the session is actually playing, so a queue that
  advanced while the window was up reopens the right clip rather than the one
  the window was created with.
- **Home-swipe still floats the system window.** Leaving the app to the home
  screen while a video plays keeps the system picture-in-picture behaviour, so
  a viewer can watch over other apps; only the in-app back gesture changed.

### Changed
- **Back no longer leaves the video behind.** The mini-player is offered only
  when there is a picture on this phone that is actually playing: a cast is
  drawn by the TV, audio-only has no picture, and a paused, finished or
  still-loading video has nothing worth docking. In those cases back leaves the
  player as before. The system picture-in-picture window and the in-app window
  are never shown at the same time.

## 0.3.6 — infinite scroll (2026-11-14)

The feed now keeps going instead of stopping at one screenful.

### Added
- **The Trending feed loads more as you reach the bottom.** Scrolling near the
  end appends the next page automatically, with a small spinner at the foot of
  the list while it loads — the way the real front page behaves. It keeps the
  viewer's place, skips anything already shown, and stops cleanly when the feed
  runs out.
- **Paging follows the client that produced the page.** Continuation tokens are
  handed back to the same client profile that minted them, so the signed-in
  personal feed stays personal and the anonymous feed stays anonymous, without
  a second request to work out which surface to use.

### Changed
- The Subscriptions feed is assembled from the device-local channel list, so it
  does not paginate; the infinite scroll applies to Trending, where the server
  actually offers a next page.

## 0.3.5 — pull-to-refresh and always-on screen (2026-11-14)

Refreshing should not require finding a button, and the phone should not sleep
while the app is open.

### Added
- **Pull down to refresh.** The feed is now a pull-to-refresh surface: drag down
  from the top and it reloads, with the standard spinner. This works on the
  Trending and Subscriptions feeds, and on the empty and error states too.
- **The screen stays on while the app is open.** The window now holds the display
  awake from launch, so browsing, reading a channel or waiting on a feed no longer
  lets the phone sleep. The flag is dropped automatically when the app is not in
  the foreground, so it never keeps the screen on from behind another app.

## 0.3.4 — 2026-11-14

Two things that made the app feel like it was not paying attention: the front
page could sit on old uploads until you refreshed by hand, and the screen could
switch itself off mid-clip.

### Fixed
- **The feed updates itself.** The front page only loaded once, when the app
  first opened, so coming back from a video, another tab, or the background
  showed stale uploads until you reached for Refresh. Home now refreshes when it
  becomes visible again. A one-minute floor keeps a quick tab switch from
  re-fetching a feed that was just loaded — important for Subscriptions, which
  costs one request per channel.
- **Watching a Short keeps the screen awake.** The full player already held the
  display on, but the Shorts reel never did, so the phone could fall asleep
  while a clip was playing on its own. The reel now holds the screen exactly as
  the player does, and releases it the moment you leave.
- **A refresh is now visible.** A resume refresh happens behind the list you are
  already reading; a slim progress bar under the filters shows that it is
  updating, instead of the change arriving silently.

## 0.3.3 — 2026-11-14

A pass over the rough edges that showed up while watching: the queue advances,
the Shorts reel recovers, and several labels stop saying more than they can
back up.

### Fixed
- **A finished video now advances the queue.** When a video played from the
  queue ended, "Up next" could sit on the ended frame instead of playing the
  next item: the finished video was still sitting at the head of the queue. The
  player now skips the video it just finished, and a queued video started from
  "Play all" is dropped from the queue as it plays so it cannot come round a
  second time.
- **The Shorts reel no longer goes blank.** A reel whose clip frames had not
  loaded yet was left as an empty surface because the visible page bailed out
  instead of resolving again; the page is now always re-resolved.
- **Refreshing Shorts keeps your place.** The refresh button used to jump back
  to the first clip; it now re-resolves the clip you are actually watching.
- **Resuming a second video saves its progress.** The resume-point budget was
  carried over from the previous video, so a video played right after another
  could have its resume point suppressed.
- **A `youtube.com` link without `www.` opens.** Tapped links were only matched
  against `www.youtube.com` and `m.youtube.com`, while the paste path accepted
  the bare host — the two now agree.
- **The quality chip stops overstating the stream.** Picking 2160p on a video
  that only serves 1080p used to label the chip "2160p"; it now shows the height
  that is actually playing. The quality sheet also lists only the heights the
  video really offers.

### Changed
- **Unsubscribing asks first.** A tap on "Subscribed" now opens a confirmation,
  since unsubscribing reaches your account; subscribing is still one tap.
- **"Show dislike count"** names the switch after what it turns on, instead of
  "Show likes and dislikes".
- **The search box invites a link.** Its placeholder now reads "Search or paste
  a link", matching the fact that a pasted YouTube URL plays straight away.


Playback stops burning data and battery on frames you cannot see.

### Added
- **Audio only in the background.** When you leave the app while a video is
  playing, WeTube now steps the stream down to its audio track and brings the
  picture back the moment you return, so no video is fetched or decoded for a
  screen nobody is looking at. It is on by default and can be switched off in
  Settings or from the player's gear. It never changes your own **Audio only**
  choice, and it leaves a video alone when dropping the picture cannot help: a
  saved download, a live stream, something being cast to a TV, or a video with no
  separate audio track.

### Fixed
- **The "Audio only" setting now says what it does.** Its subtitle claimed it
  started videos in the background as audio, when it actually plays every video
  without the picture.

## 0.3.1 — 2026-11-14

The Shorts tab now follows the account, like the real thing does.

### Fixed
- **The Shorts feed follows your account's algorithm, not a hashtag search.**
  The feed was built from a Shorts shelf on the home page, which YouTube only
  sends some of the time; when it was missing, the tab fell back to searching
  `#shorts` and showed a near-random mix that ignored who was signed in. The tab
  now loads the same reel sequence the real Shorts surface uses — a vertical,
  recommendation-driven feed that answers for the signed-in account and grows
  through its own continuation token — so the clips match your interests. The
  home-page shelf and the `#shorts` search stay only as fallbacks if that
  surface is unavailable.

## 0.3.0 — 2026-11-14

A polish pass from a full read-through of every screen: wording that promised
what the app did not do, controls that looked tappable but were not, and states
that left the viewer stuck.

### Fixed
- **A pasted YouTube link now opens, instead of being searched as text.** Search
  told the user to "paste a link to watch something specific", but the text was
  sent to YouTube as a search query. A `youtu.be/...` or `youtube.com/watch?v=...`
  link pasted into the field now opens that video directly, reusing the same host
  list and id check as a tapped deep link.
- **The Shorts error state offers Retry.** A failed or empty Shorts fetch was a
  dead end; it now carries the same Retry action as Home and Search.
- **An unclassified error no longer leaks internal text.** The failure fallback
  printed up to 220 characters of a raw exception message; it now shows a plain
  sentence.

### Changed
- **The first Home tab is labelled "Trending", which is what it shows.** It was
  called "For you" while loading the region's trending feed, and the empty-state
  copy already spoke of "trending videos".
- **Like and dislike counts no longer look tappable.** An anonymous client cannot
  rate a video, so the counts are read-only; the chips are now drawn without a
  press affordance instead of swallowing taps.
- **Search's field says "Search videos",** naming the corpus rather than implying
  a search of the local library.
- **Empty and error messages centre their body text** under the centred title.

## 0.2.9 — 2026-11-14

Two fixes found on a real device: a refused Short never tried to recover, and a
local change made outside the Library tab never reached the account.

### Fixed
- **A Short that YouTube refuses now recovers, instead of giving up.** The Shorts
  feed showed "YouTube refused this clip (403). Swipe to another one." and stopped,
  even though the same clip usually plays when the stream is re-resolved. A played
  Short that fails on a refused or expired URL now re-resolves through the next
  playback profile, exactly as the full player already did (0.2.5), and only shows
  the message after three attempts — so a one-off 403 heals itself.
- **Favourites and subscriptions made outside the Library are pushed to the
  account.** Only unsubscribing from the Library tab scheduled a sync, so
  favouriting a video or subscribing to a channel from the player, a Short, or a
  channel page updated this device and never reached YouTube — the account's
  Favourites mirror stayed behind. Every local change to a synced entity now
  schedules the debounced push the engine was built to receive. Nothing is sent
  until the first sync has been consented and the sync switch is on.

### Changed
- **The sync note says what does not sync.** Settings now states plainly that
  playlists you create in WeTube stay on the device, so the account's Favourites
  mirror is not mistaken for a two-way playlist sync.

## 0.2.8 — 2026-11-14

A short copy release: three last rough edges in on-screen wording, all the same
kind of fix as 0.2.6 and 0.2.7.

### Changed
- **The SponsorBlock notice is a sentence.** The player said "3 segment(s) will be
  skipped"; it now says "3 segments" through the same shared helper the sync
  prompt uses, so a count never shows a bracketed placeholder.
- **A rejected backend address says so plainly.** Saving a host that is not a
  usable `https://` address now answers "That Invidious address doesn't look
  valid; using the default instead." rather than the more technical wording.
- **Sharing a Short uses the same link as the player.** A Short now shares the
  canonical `youtube.com/watch?v=` URL the player has always used, instead of the
  short `youtu.be/` form, so both share paths produce one consistent link.

## 0.2.7 — 2026-11-14

A second consistency pass: the remaining bulk actions that delete something now
ask first, the counts in the sync prompt read as proper sentences, and the last
strings that leaked an implementation name are plain again.

### Changed
- **Bulk actions that delete ask first.** Clearing the play queue, deleting every
  finished download and removing the active account now confirm before they act —
  the same guard clearing watch history gained in 0.2.6. Removing a single item is
  still one tap, because a single row is easy to put back.
- **The sync prompt reads as a sentence.** "2 subscription(s)" and "1 removal(s)"
  are now "2 subscriptions" and "1 item", through a shared helper, so the
  safety-boundary text is not mistaken for a placeholder.
- **The last implementation names are gone from the screen.** The backend choice
  reads **Direct (built-in)** rather than "Direct (InnerTube)"; an empty Shorts
  feed and a channel that fails to load now say something a person can act on
  instead of "Unknown error".

## 0.2.6 — 2026-11-14

A clarity pass, driven by reading the app as a first-time user: destructive
actions ask before they act, two lists that looked alike are named apart, and the
last of the developer vocabulary is gone from anything on screen.

### Changed
- **Clearing watch history asks first.** The button in the History tab now opens a
  confirmation that spells out what is lost — every video in the list and its
  resume point — instead of wiping it on a single tap. It matches the guard the
  playlist delete already had.
- **Favourites and Watch Later read as different things.** The Library tab that
  holds liked videos is now called **Favourites**, and the control that adds one
  says **Favourite** — on the player and on the Shorts rail — rather than "Save",
  which was too easily read as "Save to Watch Later". The favourites empty state
  points out that Watch Later is a separate list.
- **Settings is grouped by what each control affects.** The grab-bag **Clean-up**
  section is gone: **SponsorBlock** and **Show likes and dislikes** now sit under
  **Content** next to the mature-content switch, and the stream-proxy toggle moved
  to **Backend**, where it appears only when an Invidious or Piped backend is
  selected and can actually apply.
- **Messages are written for people, not developers.** Errors no longer say
  "instance", "attestation" or "JavaScript challenge"; an empty trending feed
  explains itself and points at the region setting; the Backend and sign-in notes
  drop the same jargon; and the first-sync prompt now says what a "removal" is —
  an item you deleted here that would also be deleted from the account.
- **The trending-region field explains itself**, with a hint under it: "Two-letter
  region code, e.g. US."

## 0.2.5 — 2026-11-14

Playback recovery now retries through a different client profile, and the one
remaining gap — the throttling `n` parameter on the last-resort client's URLs —
is documented as a deliberate deferral rather than left as an open bug.

### Added
- **Playback failover across client profiles.** When a stream URL is refused
  mid-playback, the player already re-resolved the video and resumed where the
  viewer was; it now also asks the backend to drop its leading client profile for
  that retry, so the next resolve starts from the next client instead of asking
  the same one for a URL it has just rejected. A failure a retry cannot clear (a
  decode or container error) is shown with an explanation rather than swallowed,
  and a downloaded video being played is re-opened from disk before the network
  is consulted.

## 0.2.4 — 2026-11-14

A fix-up for **offline downloads**, found by reviewing the feature end to end: a
queued transfer now survives a restart, a resume that disagrees with the server is
no longer trusted, a finished download that lost its file can be saved again, and
the rough edges around the queue and the storage cap are closed.

### Fixed
- **A queued download resumes after a restart.** A transfer left waiting when the
  app's process was killed now picks up on the next launch; before, it sat at
  "Waiting to download" until the user happened to start another download.
- **A resumed download is verified, not trusted.** The downloader now checks the
  server's `Content-Range` start against the byte offset it asked for, accepts a
  `416` completion only when the part matches the whole-file size the server
  states, and refuses to move a file into place unless its length matches. A
  proxy or server that answers from the wrong offset can no longer be spliced
  into a corrupt file that is then shown as a finished download.
- **"Downloaded ✓" is no longer a dead end when the file is gone.** If a finished
  download's media is removed outside the app — a storage clean-up, a manual
  delete — the player offers **Download again** and re-queues it in place,
  instead of a disabled label while playback silently streams from the network.
- **Changing the storage limit takes effect immediately.** Lowering the cap now
  trims the oldest finished downloads at once, matching the Settings subtitle,
  rather than waiting for the next download to finish.
- **A finished download is no longer dropped, and its file leaked, at the queue
  cap.** When the 200-record queue filled, the oldest entry was dropped whatever
  its state, stranding a finished download's file on disk where nothing could
  reclaim it. Eviction is now file-aware, deletes what it removes, and prefers a
  record nobody is waiting for over one that is finished or in flight.
- **Player errors are surfaced instead of swallowed.** A corrupt or undecodable
  file used to leave a stuck player with no message. A failure a retry cannot
  clear now says so, and playback recovery re-opens a downloaded file from disk
  before reaching for the network.
- **Retrying a failed download behaves consistently.** A re-queue keeps the
  entry's original file name, timestamps and container, so a retry cannot orphan
  a half-fetched part file.

### Changed
- **The storage limit is measured in the units it is labelled with.** "1 GB" now
  means 1,000,000,000 bytes, matching the Library's size readout, instead of a
  slightly larger 1024-based ceiling.

## 0.2.3 — 2026-11-14

### Added
- **Offline downloads in the app.** A video's More menu now offers **Download**,
  and a **Downloads** tab in the Library lists everything saved — each row's live
  progress or saved size, with retry and remove, and the total space it uses. Once
  a video is saved, the player prefers the local file, so it plays with no
  connection instead of streaming.
- **A storage cap for downloads.** A Downloads section in Settings limits how
  much finished media is kept. When the limit is exceeded, the oldest finished
  download is removed first; the newest is never removed, so a single download
  larger than the cap still completes and stays.
- **Downloaded videos play from disk, well or poorly connected.** The local file
  is used whenever one exists and playback is not being cast; if fetching a
  video's details then fails, the player falls back to the thin metadata saved
  with the download rather than refusing to play.
- **Offline download core.** A device-local record per video the user asks to
  keep, and a resumable downloader that fetches one muxed progressive file. It
  streams into a `.part` file and renames it into place only once complete, so a
  half-finished download is never presented as a playable file. The records are
  never synced and are not part of a backup.
- **The download service and controller.** A foreground service of type
  `dataSync` drains the queue one file at a time, front to back, resuming each
  `.part` file and showing one aggregate progress notification with a Cancel; the
  process-scoped `DownloadManager` is the UI's handle on the queue and the
  service's progress sink. Removing an entry stops its download and deletes its
  files, and the service stops once the queue is empty. No WorkManager — the work
  is a single visible, cancellable transfer, not a scheduled job.

## 0.2.2 — 2026-11-14

Bring your subscriptions with you, and see what they uploaded without signing in.

### Added
- **Subscription import.** Bring existing subscriptions to this device from a
  Google Takeout `subscriptions.csv` or an OPML file exported by another YouTube
  client (NewPipe, FreeTube, ...), from a new "Import subscriptions" section in
  Settings. The parsers are pure and dependency-free, and an import writes only
  the local subscription list — it reaches the account only on an explicit sync.
- **A local Subscriptions feed.** Home's **Subscriptions** chip now builds its
  feed from the device-local subscription list — no account and no sign-in — by
  fetching each subscribed channel's recent uploads with bounded concurrency and
  merging them newest-first. A dead or renamed channel is skipped rather than
  blanking the whole feed, and the first results appear before the slowest
  channel answers.

### Changed
- **Home's feed chips no longer wait for a sign-in.** The Subscriptions feed is
  device-local, so the chips are always shown and a signed-out user sees their
  own subscriptions; a signed-in account still contributes through the same list
  it syncs into.

## 0.2.1 — 2026-11-14

Keep videos for later, line them up to play, and group them into playlists — all
on this device, with no account and no server.

### Added
- **Watch Later.** Save from a video's More menu and find it in a new Library
  tab. Rows can be removed in place, and the list is capped so it cannot grow
  without bound.
- **A play queue.** Add any video to the queue from the More menu, open the queue
  from the player's top bar, and drag the handle to reorder. When a video ends,
  the next queued item plays automatically; the Up-next overlay only appears once
  the queue is empty.
- **Local playlists.** Create a playlist from the Library, or from a video's
  "Add to playlist" sheet without leaving playback. A playlist opens on its own
  screen where you can rename it, delete it with a confirmation, reorder its
  rows by dragging, remove items, and **Play all**. Like Watch Later and the
  queue these are device-local: a local playlist gets a `local:` id so it can
  never be mistaken for a remote one, and none of the three are ever synced —
  only subscriptions and favourites are.
- **A local video-metadata cache.** A bounded cache (newest first, 1000 entries)
  remembers the display fields of the ids a local playlist names, so an id-only
  playlist renders without a network call. Like the other device-local
  collections it travels in a backup and never syncs.

### Changed
- **Backups are schema v4.** The file now also carries Watch Later, the play
  queue, local playlists and the video-metadata cache; a v1–v3 file is still
  accepted and restores with those collections empty.

## 0.2.0 — 2026-11-14

Sync your YouTube subscriptions and a favourites mirror with a signed-in
account — pull-first, consent-gated and off until you turn it on.

### Added
- **Account sync.** The Library gains a sync bar and Settings an "Account sync"
  section. Turning it on lets WeTube reconcile your local subscriptions and
  favourites with a signed-in Google account. Sync is off by default and
  unavailable on the Invidious/Piped backends, which have no account API.
- **Pull-first, consent-gated first sync.** The very first sync pulls the account
  down and then stops *before* writing anything, showing only counts — "your
  account has N subscriptions and M favourites; WeTube has X to add and Y
  removals". Nothing is sent until you tap **Merge**; **Not now** writes nothing
  and keeps the pull. No change made before you consent can be pushed.
- **A favourites mirror you own.** Favourites have no native YouTube playlist, so
  the first consented push creates a private "Favourites" playlist, seeded in the
  create call, and later pushes reuse that remembered id — never a playlist
  adopted by its title. Liked videos are never touched — the mirror is a separate
  private playlist, so your real likes are not rewritten.
- **Honest sync status.** The bar shows pulling, merging and per-op push progress,
  the last result, and a specific reason with a **Retry** when a sync fails.
- **Capability check (debug builds).** A confirmation-gated tool that probes the
  account reads without changing anything and can create-and-immediately-delete
  one private "WeTube sync probe" playlist of its own to prove the write path.

### Changed
- **Sync state is keyed by a non-reversible account key.** `accountKey` is a
  salted SHA-256 of the account's own channel id (or the SAPISID value), truncated
  to 128 bits; the raw account id is never stored with sync state, and a change
  queued by one account is quarantined rather than leaked into another after a
  switch.
- **Removals are opt-in.** A video or channel removed on the account is reported
  but kept on the phone unless "mirror removals" is on, so a sync cannot silently
  drop something locally.
- **Backups are schema v3.** The file now also carries the two sync toggles;
  v1/v2 files are still accepted.
- The merge is presence-based, because InnerTube returns no per-subscription or
  per-membership timestamp; a locally edited playlist title wins outright.
- At most 200 remote mutations are sent per sync, batched and ordered
  (creates, renames, adds, subscribes, removes, unsubscribes); transient failures
  retry with 0.5s/2s/8s backoff, and anything else pauses the push and keeps the
  queued change rather than losing it.

### Security
- **Write endpoints ship unverified, behind the gate.** The account-write calls
  are hand-rolled against the web client and can change without notice, so they
  are marked `UNVERIFIED` and never run until a user has consented on the first
  sync; the capability check is debug-only and never mutates a playlist it did
  not itself create.
- **Presence-only, id-scoped merge.** Every entity, tombstone and diff is
  namespaced by kind and owned by an account key, so one account's queued change
  can neither be pushed to nor deleted from another.
- **Watch history is never synced.** No history endpoint is used, researched or
  implemented.

## 0.1.8 — 2026-11-14

Cast the current video to a DLNA/UPnP renderer on your own Wi-Fi and use the
phone as the remote.

### Added
- **Cast to a device.** The player gains a cast icon that opens a sheet listing
  DLNA/UPnP `MediaRenderer` devices found on the local network — a smart TV, a
  speaker, another phone running VLC or Kodi. Discovery runs only while the sheet
  is open; nothing scans the network in the background.
- **Remote surface.** While casting, the player shows the video's artwork,
  "Playing on <name>", and transport, a scrubber and a volume slider, all routed
  to the renderer. Position and play state come from the renderer's own
  `GetPositionInfo`/`GetTransportInfo` poll, not from the paused phone.
- **Handoff that resumes where you are.** A cast starts at the phone's current
  position; a quality or audio-only change re-casts the same video at the
  renderer's live position; "Play on this phone" brings playback back at the same
  spot.
- **Lock-screen and notification controls follow the cast.** Play, pause, stop
  and seek from the media session are routed to the renderer instead of resuming
  silent audio on the phone.
- **Screen-on only while a video is actually watched on the phone.** The screen
  stays awake during local video playback and is left to sleep on audio-only,
  when paused, and while casting (the other device is the display).

### Fixed
- **Casting survives the screen turning off.** A short-lived foreground service
  and a scoped partial wake lock keep the session alive across screen-off, and
  both are released as soon as the cast ends or fails.
- **Honest cast failures.** A refused or unreachable renderer reports a specific
  reason (UPnP error codes included), names the retry action, and never leaves
  the player stuck.
- **Paused playback no longer resumes silently** behind an active cast.

### Changed
- The built-in Invidious default moved to an instance that currently answers the
  API; the previous default had begun returning HTTP 500 for video lookups.

### Security
- A hostile device can no longer aim the app's volume or seek requests at an
  arbitrary host: every resolved control URL must be a private or link-local
  literal, and the cloud-metadata address is refused even though it is link-local.
- Descriptions and SOAP responses that declare a DTD are rejected, and the XML
  walks are iterative, so a crafted document can neither expand entities nor
  overflow the stack.
- Response size, chunked-trailer count and total read time are all capped, so a
  slow-drip responder cannot pin a connection open.

## 0.1.7 — 2026-11-13

A quality-and-polish release: no new playback surfaces, but the player, search,
storage and the security boundary all got tightened.

### Added
- **Player dislike count.** One honest, read-only figure from Return YouTube
  Dislike, shown only while "Show dislikes" is on.
- **Share from the player.** The Share chip sends the canonical
  `youtube.com/watch?v=…` link through the system chooser.
- **Subscribe from the player.** The channel row's Subscribe/Subscribed button is
  wired to the same local library the Library screen reads.
- **End screen.** When a video finishes, its next related video appears over the
  final frame; one tap plays it in the same player and back-stack entry.
- **Search improvements.** Submitting a search dismisses the keyboard, and the
  next page loads once the end of the list is reached.
- **Shared helpers.** `core/Text.kt` (`decodeEntities`, `compactCount`,
  `parseCount`, `formatSpeed`), `core/Urls.kt` (`sanitizeBackendHost`,
  `isAllowedSignInUrl`, `encodeUrlValue`) and `core/VideoIds.kt`
  (`isValidVideoId`), so each rule has one implementation.
- **Backup schema v2**, additionally carrying the backend, the Invidious/Piped
  hosts, the proxy-streams flag and the SponsorBlock categories.
- Unit tests for the text, URL and video-id helpers, the age-gate cap, the backup
  schema, the settings store and the stream resolver.

### Fixed
- **No more lost preference writes.** `SettingsStore.update` performs its
  read-modify-write inside the DataStore transaction, so two overlapping updates
  can no longer snapshot the same state and clobber each other.
- **No more duplicate list keys.** Every paged list (search, home, channel,
  comments) de-duplicates by id, so a repeated item cannot crash a Compose list.
- **Quality/audio-only changes keep play state.** Re-resolving rebuilds the
  `MediaItem` without forcing playback to start; a paused video stays paused.
- **Deep links are safer and one-shot.** The host is allow-listed, the id is
  validated, and an activity recreation no longer reopens the same video.
- **Null-safe player position and duration.** No stray zeros are written before
  the player exists.
- **Comments paginate on demand.** The next page is fetched only once the list's
  footer is visible, not from every offscreen sentinel.
- **Age-gate ids are capped.** The locally remembered set is capped FIFO at 500.
- **Playback errors keep the page.** A failed load or re-resolve is shown over
  the player, with the title, channel, chips and comments still usable below.

### Changed
- **Consistent text.** Entities are decoded at the backend boundary, and reused
  values decoding repeatedly so a double-escaped `&amp;#128293;` still reaches
  🔥; counts print as `222K` rather than `222.0K`; speed labels read `1×`,
  `1.25×`, `1.75×` and `2×` everywhere.
- **Subscribed styling.** An already-subscribed button uses the muted container
  instead of the brand accent.
- **Settings inset.** The settings content is inset for the navigation bar and the
  on-screen keyboard.
- **Accessibility.** The in-player switch rows expose `Role.Switch` semantics, so
  a screen reader announces one labelled control per row.
- **Backups.** The schema version is validated on restore; v1 files are still
  accepted, and a file from a newer build is rejected rather than guessed at.

### Security
- **Backend host validation.** A host from Settings or a restored backup must be
  `https`, carry no userinfo, and not be `localhost` or a loopback, private,
  link-local, carrier-grade-NAT or reserved literal; anything else falls back to
  the built-in default.
- **Cleartext disabled.** `network_security_config.xml` refuses cleartext traffic
  app-wide, with no per-domain exemptions.
- **Sign-in WebView locked down.** Navigation is allow-listed to the
  Google/YouTube https hosts; file and content access are off, mixed content is
  never allowed, and the view is destroyed when the screen is popped.
- **Dynamic URL values are percent-encoded** before they enter a path or query.
- **Release signing fails closed.** A release build will not package without a
  real signing key; `-PallowDebugSigning=true` is the explicit opt-in for a local
  test build.

## 0.1.6

### Added
- Video quality picker: leave it on Auto or pin any height the video offers, with
  a default in Settings.
- In-player settings panel behind the control-bar gear: quality, playback speed,
  captions, audio-only, background playback, SponsorBlock and dislike counts.

### Changed
- Playback is resolved through the `IOS` client so every resolution up to 2160p is
  offered, with the Android and Android VR clients as fallbacks.

### Fixed
- Shorts rail behaviour.
