# Changelog

Notable changes to WeTube, newest first. Grouped as Added, Fixed, Changed and
Security. This release is 0.2.7 (versionCode 17).

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
