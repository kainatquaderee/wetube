# Sync Protocol (SYNC-0)

Clean-room interoperability notes for the InnerTube calls WeTube 0.2.0 needs in
order to sync subscriptions and playlists with a signed-in Google account.

WeTube is an independent, clean-room client. This document records the request
shapes WeTube intends to use, inferred from **the public web client's request
shape** and from **the client's own traffic as observed on the wire**. Nothing
here is derived from any proprietary source, and no proprietary code or assets
are used. Endpoints are not contractual and may change without notice.

> **Status.** Every *read* below is believed correct and is exercised by the
> app's normal feeds. Every *write* is **unverified** until the SYNC-0 capacity
> test (section 6) records a pass in this file. Until then all write paths are
> `UNKNOWN` and the engine treats them as unavailable.

No watch-history endpoint is used, researched, or implemented. History sync is
permanently out of scope.

## 1. Common envelope, auth and headers

All calls are `POST https://www.youtube.com/youtubei/v1/<endpoint>?prettyPrint=false`
with a JSON body whose first member is the WEB client context (identical to the
context `InnerTubeBackend` already builds; `X-Goog-Api-Format-Version: 2` is
already sent by it):

```json
{ "context": { "client": { "clientName": "WEB", "clientVersion": "2.20240101.00.00",
  "hl": "en", "gl": "<region>", "visitorData": "<visitor>" } }, ... }
```

Auth headers come from `AuthStore.headers()` and are already implemented:

| Header | Value | Certainty |
|---|---|---|
| `Cookie` | the account's cookie jar | confirmed (feeds work) |
| `Authorization` | `SAPISIDHASH <epoch>_<sha1("<epoch> <SAPISID> https://www.youtube.com")>` | confirmed |
| `X-Goog-AuthUser` | `0` | confirmed for account 0 |
| `X-Origin` | `https://www.youtube.com` | confirmed |

Additional headers we believe a **write** needs, and which the read path does not
currently send:

| Header | Value | Certainty |
|---|---|---|
| `X-Goog-Api-Key` | the public web key already present in `InnerTubeBackend` | **uncertain** — some WEB reads ignore it, writes may require it |
| `X-Youtube-Client-Name` | `1` | confirmed on reads |
| `X-Youtube-Client-Version` | `2.20240101.00.00` | confirmed on reads |
| `X-Goog-Visitor-Id` | the cached visitor id | confirmed on reads |
| `X-Youtube-Bootstrap-Logged-In` | `true` | **uncertain** for writes |
| `X-Goog-Correlation-Id` / EOM variants | — | **uncertain**; not sent initially |

`SAPISIDHASH` is time-stamped to the second. A request whose timestamp is far
from the server clock is rejected as unauthenticated; WeTube re-derives it per
request (`headers()` computes it on demand) and never caches it.

**Account identity.** `account/account_menu` is the one signed-in read we
already make. We intend to read the account's own channel id (`UC...`) from it
for the non-reversible `accountKey`. If the channel id cannot be read, the
fallback is to hash the SAPISID *value*: both are non-reversible; only the
channel id is stable across a re-login.

## 2. Reads

### 2.1 Subscriptions (channel set)
- **Believed shape:** `POST browse?prettyPrint=false`, body
  `{ "context": {...}, "browseId": "FEchannels" }`; page with `continuation`
  (`{ "continuation": "<token>" }`).
- **Believed response:** `gridChannelRenderer` / channel entries carrying
  `channelId` (`UC...`), `title`, `thumbnail`, `subscriberCountText`.
- **Stable ids/timestamps:** `channelId` is the stable id. **No per-subscription
  timestamp is returned.** We therefore reconcile by *presence*, not by time
  (`localUpdatedAt` is our own clock).
- **Uncertain:** whether `FEchannels` is still served to WEB, and whether it is
  paginated by `continuation` or a `reloadContinuationData`.
- **Fallback:** `browseId: "FEsubscriptions"` (the video feed the app already
  reads) and collect `browseEndpoint.browseId` from each card's byline to gather
  channel ids. This is **degraded** (only channels with a recent upload appear)
  and must be labelled as such: the merge prompt warns that the account list may
  be incomplete, and reconciles presence-only.

### 2.2 Playlists (the account's playlists)
- **Believed shape:** `browse?prettyPrint=false`, body `{ "browseId":
  "FEplaylist_aggregation" }` (the web client's "Playlists" tab).
- **Believed response:** `gridPlaylistRenderer` / `lockupViewModel` with
  `contentType` PLAYLIST, carrying `playlistId` (`PL...`), title, and an item
  count.
- **Fallback:** scan the signed-in `browse` guide/`FEwhat_to_watch` response for
  any `playlistId` seen in `browseEndpoint`/`navigationEndpoint`. Degraded; used
  only to locate the favourites playlist, not to enumerate.

### 2.3 Playlist contents
- **Believed shape:** `browse?prettyPrint=false`, body `{ "browseId":
  "VL" + playlistId }`; page with `continuation`.
- **Believed response:** `playlistVideoRenderer` entries with `videoId`, the
  per-membership **`setVideoId`**, `index`, title, byline.
- **Stable ids/timestamps:** `videoId` identifies the video; **`setVideoId` is
  the identity of the membership** and is required to remove it. Again no
  timestamp is returned.
- **Uncertain:** whether `setVideoId` is returned on every renderer shape, and
  whether `WL` (Watch Later) omits it.
- **Fallback:** if `setVideoId` is absent, removal is impossible; the design
  keeps such items pull-only and surfaces "this item can't be removed remotely"
  rather than guessing.

### 2.4 Account channel id (for `accountKey`)
- **Believed shape:** the existing `account/account_menu` call; read the
  `browseEndpoint.browseId` of the "Your channel" entry.
- **Fallback:** hash the SAPISID value.

## 3. Writes

Every write returns a JSON object; success is `HTTP 200` **and** either an empty
`actions` or no `error` member. We treat any non-2xx, any
`playabilityStatus`-style error, or a body containing `"error"` as a failure.

### 3.1 Subscribe / unsubscribe
- **Believed shape (subscribe):** `POST subscription/subscribe?prettyPrint=false`,
  body `{ "context": {...}, "channelIds": ["UC..."] }`.
- **Believed shape (unsubscribe):** `POST subscription/unsubscribe?prettyPrint=false`,
  body `{ "context": {...}, "channelIds": ["UC..."] }`.
- **Uncertain:** the exact endpoint spelling and casing; whether `channelIds` is
  plural/array (we send an array of one, and batch several only after a test
  proves the array is honoured); whether `X-Goog-Api-Key` is required.
- **Fail-safe:** never batched speculatively — a first push sends **one** channel
  id and pauses; success unlocks batching.
- **Fallback:** if the endpoint 404s, requires attestation, or rejects the
  session, subscribe/unsubscribe is marked `UNAVAILABLE`; local subscription
  state is preserved and the user is offered the channel's watch page in the
  browser. Local edits stay queued as `pendingOp`.

### 3.2 Playlist create / edit / delete
- **Create:** `POST playlist/create?prettyPrint=false`, body
  `{ "context": {...}, "title": "<name>", "privacyStatus": "PRIVATE",
     "videoIds": [...] }`; believed response carries `playlistId`.
- **Edit:** `POST playlist/edit?prettyPrint=false`, body
  `{ "context": {...}, "playlistId": "PL...", "actions": [ ... ] }` with
  actions such as
  `{"action":"ACTION_SET_PLAYLIST_NAME","playlistName":"..."}`,
  `{"action":"ACTION_SET_PLAYLIST_PRIVACY","privacyStatus":"PRIVATE"}`,
  `{"action":"ACTION_ADD_VIDEO","addedVideoId":"..."}`,
  `{"action":"ACTION_REMOVE_VIDEO","removedVideoId":"...","setVideoId":"..."}`,
  and (not used in 0.2.0) `ACTION_MOVE_VIDEO_BEFORE`/`ACTION_MOVE_VIDEO_AFTER`.
  We believe several actions may be sent in one call; the cap we enforce is 50.
- **Delete:** `POST playlist/delete?prettyPrint=false`, body
  `{ "context": {...}, "playlistId": "PL..." }`.
- **Uncertain:** the exact action string constants, whether `setVideoId` is
  mandatory on removal, the privacy enum values, and the batch limit.
- **Fail-safe:** delete is sent **only** for a playlist WeTube itself created
  (never for a playlist the user created elsewhere); create is
  always `PRIVATE`; a failed create aborts the push and leaves favourites
  pull-only.
- **Fallback:** if `playlist/create` is unavailable, favourites push is disabled
  and the UI offers to map favourites to an existing playlist the user names, or
  to stay read-only.

### 3.3 Add / remove a video from a playlist
Covered by `playlist/edit` above (`ACTION_ADD_VIDEO` / `ACTION_REMOVE_VIDEO`).
Removal requires the `setVideoId` captured from §2.3.

### 3.4 Liked videos (`LL`) — deliberately not used
Adding to `LL` is a *like*, not a playlist add, and removing is an *unlike*.
WeTube does **not** write to `LL` in 0.2.0; the favourites playlist is a
WeTube-owned private playlist so the user's own likes are never mutated.

## 4. Rate limits and safety margins

- One sync sends at most **200** mutating operations; the remainder is deferred
  to the next sync. `pendingOp` is only cleared when its operation is confirmed.
- Write operations are sequential, in a fixed order (creates → adds → subscribes
  → removes → unsubscribes → deletes), one small batch at a time, with a short
  pause between batches.
- Retries: at most 3 attempts with backoff 0.5s / 2s / 8s, and only for
  transient failures (network, HTTP 5xx, HTTP 429, timeout). Any 4xx that is not
  429, or an attestation/sign-in wall, is terminal for the whole push.

## 5. Open questions for the capability test

1. Does `FEchannels` still answer WEB with a full channel list?
2. Does a write require `X-Goog-Api-Key`?
3. Does `subscription/subscribe` accept an array of more than one id?
4. Are the `ACTION_*` constants correct, and is `setVideoId` mandatory on remove?
5. Does `playlist/create` return `playlistId` at the top level or nested?

## 6. SYNC-0 capability test (manual, opt-in)

A debug-only tool ("Run sync capability check") runs, **only after the user
taps it**:

1. Read probes: `FEchannels`, `FEplaylist_aggregation`, a `browse` of an
   existing playlist, `account/account_menu`. Pass = HTTP 200 with recognisable
   renderers.
2. Optional write probe: create a **private** playlist named
   `WeTube sync probe`, then delete it. Pass = create returns an id and delete
   returns 200. This is the canary that proves the write path end to end.
3. Subscribe/unsubscribe is **not** probed against a real channel by the tool.
   It is discovered on the first user-approved push, one id at a time.

Each probe result is printed on screen so a maintainer can copy the outcome into
this file. A failing write probe sets that capability to `UNAVAILABLE` for a
cooldown and never retries blindly.
