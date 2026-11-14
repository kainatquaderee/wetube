# Changelog

Notable changes to WeTube, newest first. Grouped as Added, Fixed, Changed and
Security. This release is 0.1.8 (versionCode 9).

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
