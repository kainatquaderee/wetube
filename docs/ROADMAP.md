# Roadmap

Ordered by what users of alternative clients actually ask for, not by what is
easy to demo.

## 0.1 — shipped in this tree
- [x] Home feed by region, search with suggestions + real continuation paging
- [x] Player: merged A/V, HLS for live, speed, audio-only, resume, chapters
- [x] Background playback + media notification
- [x] Channel pages, related videos, likes/subscribers/publish date
- [x] Local history, favourites
- [x] SponsorBlock, Return YouTube Dislike
- [x] Backend/instance switching (direct InnerTube default), deep links
- [x] Subtitles/CC picker with YouTube auto-translation and original-track fallback
- [x] Hold the video for 2× playback
- [x] Background playback can be switched off
- [x] Shorts: swipe feed, progress bar, tap-to-pause, CC
- [x] Shorts rail: like/dislike/comment counts and a tappable channel
- [x] Comments: native top/newest reader with paging, browser fallback
- [x] Video quality picker: Auto or a pinned height, from the player or Settings
- [x] In-player settings panel: quality, speed, captions, audio-only, background,
      SponsorBlock, dislikes
- [x] No ads, no trackers, no account

## 0.1.7 — quality & polish (shipped in this tree)
- [x] Preferences written inside the DataStore transaction, so a concurrent
      change can no longer be lost
- [x] Every paged list de-duplicates by id, so a repeated item cannot collide
      with a Compose key
- [x] Changing quality or audio-only mid-video keeps the play/pause state
- [x] Deep links: host allow-list, id validation and one-shot consumption
- [x] Null-safe player position and duration
- [x] Sign-in WebView remembered and destroyed; navigation allow-listed
- [x] Comments paginate only once the list footer is visible
- [x] Age-gate ids capped FIFO at 500
- [x] Backup schema v2 with version validation and size/count caps
- [x] Player dislike count (read-only, gated by "Show dislikes")
- [x] Share from the player
- [x] Subscribe/Subscribed on the player channel row, wired to the local library
- [x] End screen with the next related video
- [x] Playback errors keep the video metadata visible below
- [x] Shared text/count/speed helpers, with entities decoded at the backend
      boundary
- [x] Search dismisses the keyboard and paginates on scroll
- [x] Hardening: https-only host validation, cleartext traffic disabled,
      percent-encoded URL parameters

## 0.1.8 — casting (shipped in this tree)
- [x] Discover DLNA/UPnP `MediaRenderer` devices on the local network while the
      cast sheet is open, with the multicast lock held per sweep
- [x] Cast the current video to a renderer: muxed stream, title, channel and
      artwork, resuming at the phone's position
- [x] Cast icon in the player (filled state while active) and a two-page sheet:
      device list, then remote controls with live position and play state
- [x] Remote surface replaces the local player while casting, with transport,
      scrubber, volume, retry and "Play on this phone"
- [x] Lock-screen/notification transport routed to the renderer through the media
      session, so play/pause does not resume silent local audio
- [x] Quality and audio-only recast at the renderer's live position; SponsorBlock
      skips and "Play here instead" follow the active output
- [x] Cast failures carry a specific reason and a recovery action
- [ ] Custom cast receiver (Google Cast needs a registered app id and is not part
      of this release)
- [ ] Queue management on the renderer

## 0.1.9 — polish follow-ups
- [ ] Mini-player: keep the current video docked while browsing the rest of the app
- [ ] Comment composer: post and reply from the app when signed in
- [ ] Fullscreen auto-rotate for landscape video, with a portrait lock option

## 0.2.0 — account sync (pull-first, shipped in this tree)
- [x] Sync subscriptions with a signed-in account, both ways but pull-first: the
      account is fetched and applied before anything is written
- [x] Sync a favourites mirror: the first consented push creates a private
      "Favourites" playlist WeTube owns (seeded in the create call) and later
      pushes reuse that remembered id; the user's own likes (`LL`) are never written
- [x] First sync stops before its first write and asks once, showing counts only;
      a change made before consent can never be pushed
- [x] Presence-based three-way merge, keyed by a non-reversible per-account key,
      with tombstones and per-account quarantine so accounts cannot leak into
      each other
- [x] Ordering, batching (50) and a per-sync cap (200) on writes, with retry for
      transient failures and a pause that keeps the queued change on anything else
- [x] Removals only for playlists WeTube itself created; mirroring account
      removals locally is opt-in (off by default)
- [x] Sync status bar in the Library, an "Account sync" section in Settings, and a
      debug-only capability check; backups bump to schema v3
- [ ] Verify the write endpoints against a real account (the SYNC-0 capability
      test in `docs/SYNC_PROTOCOL.md`); until then the push path is `UNVERIFIED`
- [ ] Sync on the Invidious/Piped backends (they have no account API)

## 0.2 — player reliability & downloads
- [ ] `n`-parameter transform for the fallback clients, so strictly throttled
      networks stop 403ing their URLs (IOS-first already avoids this for
      recorded video)
- [ ] Automatic backend failover: if the player URL fails, retry through another
      client profile or instance rather than showing an error
- [x] Offline downloads (video + audio merge, or muxed), resumable, with a
      download manager UI and storage cap (0.2.3)
- [x] Playlists: create, reorder, play all (local, device-only; 0.2.1)
- [x] Watch Later (device-only; 0.2.1)
- [x] Queue: add to queue, drag to reorder, autoplay from the queue (0.2.1)
- [x] YouTube playlist sync (pull-first mirror shipped in 0.2.0)
- [x] YouTube subscription sync (pull-first, both-way, shipped in 0.2.0)
- [x] Import subscriptions from Google Takeout CSV / OPML (0.2.2)
- [x] Subscription feed (local, newest-first) (0.2.2)

## 0.3 — player polish
- [ ] Gesture controls: double-tap seek, vertical swipe volume/brightness
- [ ] Pinch to zoom / fill mode, aspect ratio override
- [ ] Caption style (size, background), download, per-channel defaults
- [ ] Sleep timer, "stop after this video"
- [ ] PiP with proper enter/exit animations
- [ ] Stats for nerds: resolution, codec, bitrate, dropped frames
- [ ] SponsorBlock category editor + per-video segment list
- [ ] DeArrow titles/thumbnails (opt-in)

## 0.4 — social without an account
- [ ] Reply threads under each comment
- [ ] Timestamped comments that seek
- [ ] Share sheet, copy link at timestamp
- [ ] Local "likes" for organising favourites

## 0.5 — reach
- [x] Cast to a DLNA/UPnP renderer on the local network (shipped in 0.1.8)
- [ ] Route audio to a picked Bluetooth/USB output
- [ ] Google Cast support (needs a registered receiver app id)
- [ ] Custom cast receiver app
- [ ] Queue management on the renderer
- [ ] Android TV / leanback UI
- [ ] Tablet two-pane layout
- [ ] Wear OS media controls
- [ ] Multiple instance health checks and automatic failover
- [ ] Optional cookie import for age-restricted or private content

## Explicitly not planned
- Ads, telemetry, recommendation profiling
- Reimplementing DRM-protected playback
- Scraping at scale, or bundling credentials
- Shorts infinite-scroll autoplay (a vertical Shorts *browser* may come later,
  off by default)
