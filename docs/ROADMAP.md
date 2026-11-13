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
- [x] Comments: native top/newest reader with paging, browser fallback
- [x] Video quality picker: Auto or a pinned height, from the player or Settings
- [x] No ads, no trackers, no account

## 0.2 — player reliability & downloads
- [ ] `n`-parameter transform so `googlevideo` stops throttling/403ing URLs on
      strict networks (the single biggest playback complaint for clients like this)
- [ ] Automatic backend failover: if the player URL fails, retry through another
      client profile or instance rather than showing an error
- [ ] Offline downloads (video + audio merge, or muxed), resumable, with a
      download manager UI and storage cap
- [ ] Playlists: create, reorder, play all
- [ ] Watch Later
- [ ] Queue: add to queue, drag to reorder, autoplay from related
- [ ] Import subscriptions from Google Takeout CSV / OPML
- [ ] Subscription feed (local, newest-first)

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
- [ ] Cast (Chromecast) — requires a Cast receiver app id
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
