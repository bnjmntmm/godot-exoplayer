# Godot ExoPlayer

**Disclaimer: This project is a Work in Progress (WIP).**

This repository integrates Media3 ExoPlayer with Godot XR. It intentionally targets XR: Godot currently exposes the required Android `Surface` through an OpenXR composition layer, so non-XR Android view or texture output is outside this plugin's supported scope.

## Features

- **Android Surface Retrieval:** Utilizes the new capability in Godot 4.4 to obtain Android surfaces from plugins.
- **ExoPlayer Integration:** Embeds ExoPlayer for robust media playback with support for various formats and streaming protocols.
- **Configurable Playback:** Supports plain playback, optional Widevine DRM, cache-backed data sources, repeat mode, playback speed, volume, track selection, subtitles, and player state signals.
- **Reusable Config Resources:** Optional source, DRM, and audio Resources provide inspector-friendly configuration while the dictionary API remains available for advanced extensions.
- **Godot Audio Routing:** Optionally sends decoded PCM through Godot audio players and buses, with queue diagnostics and restoration of caller-owned audio nodes.
- **Live Playback:** Live/DVR window snapshots, wall-clock position, target latency, and a Go live action.
- **Recovery and Diagnostics:** Structured errors, opt-in bounded reconnect, credential refresh, and a reusable diagnostics panel.
- **Media Queues:** Per-item headers, DRM, external subtitles, next/previous, removal, shuffle, and transition events.
- **Quality Preferences and Subtitles:** Resolution/bitrate limits, language preferences, detailed text cues, and a Godot subtitle overlay.
- **Cache Management:** Size/resource statistics, cache/network byte counters, and asynchronous media removal or clearing.
- **Godot Engine Compatibility:** Designed specifically for Godot Engine 4.4.

## Getting Started

### Prerequisites

- **Godot Engine 4.4:** Ensure you have the latest version installed.
- **Android Development Environment:** Set up Android Studio or an equivalent environment to build and deploy Android plugins.

### Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/bnjmntmm/godot-exoplayer.git
    ```
2. Build the Addon yourself using Android Studio and Gradle or use the prebuilt inside from [godot_exoplayer](plugin%2Fdemo%2Faddons%2Fgodot_exoplayer)
3. Test out the demo in [main](plugin%2Fdemo%2Fscenes%2Fmain)

### Usage
1. Enable the Plugin: Activate the ExoPlayer plugin in your Godot project settings.
2. Surface Binding: Add an `ExoPlayerCompositionLayer` node, or create an OpenXRCompositionLayer and select `use_android_surface`.
3. Retrieve the Surface: Use the `get_android_surface()` method to obtain the Android surface from the OpenXRCompositionLayer.
4. Create a player through the `ExoPlayer` autoload:

   ```gdscript
   var surface = $OpenXRCompositionLayer.get_android_surface()
   var player_id = ExoPlayer.create_url_player(surface, "https://example.com/video.m3u8")
   ExoPlayer.play(player_id)
   ```

   Plain URL playback is the default workflow and does not require DRM configuration.
   Use `create_player(...)` directly when composing custom options, or the dedicated
   `create_drm_player(...)` helper for protected content.

   To route decoded audio through Godot's mixer and audio buses:

   ```gdscript
   var player_id = ExoPlayer.create_url_player(surface, video_uri, {
       "routeAudioToGodot": true,
       "godotAudioPlayer": $AudioStreamPlayer3D
   })
   ```

#### ExoPlayerCompositionLayer Node

The addon registers `ExoPlayerCompositionLayer`, a convenience node that extends `OpenXRCompositionLayerQuad`. Add it under your XR origin, set `video_uri` in the inspector, and enable `create_on_ready` to create the player automatically.

The node exposes the common player config in the inspector, including autoplay, volume, repeat mode, playback speed, cache, Widevine license URL, request headers, and Godot audio routing. It emits player-specific `player_ready`, `player_error`, `video_end`, and `player_state_changed` signals, and exposes helper methods such as `play()`, `pause()`, `seek_to()`, `set_media()`, and `release_player()`.

For reusable inspector configuration, assign any of:

- `ExoPlayerSourceConfig`: URL, media request headers, user agent, redirect policy, cache limit, and HLS date-time inspection.
- `ExoPlayerDrmConfig`: optional Widevine, ClearKey, PlayReady, or custom-UUID DRM plus license request headers. Leave its scheme at `NONE` for clear content.
- `ExoPlayerAudioConfig`: Android or Godot output, spatial split mode, and generator buffer length.

The composition layer waits up to `surface_wait_timeout` for its OpenXR Android surface. The old `creation_delay` remains available for project-specific staging, but is no longer required as the primary surface-readiness mechanism.

For Godot-routed audio, enable `route_audio_to_godot`. If `godot_audio_player_path` points to an `AudioStreamPlayer` or `AudioStreamPlayer3D`, the wrapper assigns an `AudioStreamGenerator` to that player and pushes decoded ExoPlayer PCM into it. If the path is empty, the wrapper creates an internal `AudioStreamPlayer`. For `AudioStreamPlayer3D`, scene settings such as transform, bus, attenuation, and spatial behavior remain under your control; the wrapper only manages the stream and `volume_linear`.

#### Player Options

`create_player(surface, uri, options := {})` accepts an optional dictionary for features that are not needed by every project:

```gdscript
var player_id = ExoPlayer.create_player(surface, video_uri, {
	"autoplay": true,
	"volume": 0.8,
	"repeatMode": 0,
	"playbackSpeed": 1.0,
	"useCache": false,
	"parseProgramDateTime": false,
	"debugLogging": false,
	"routeAudioToGodot": true,
	"godotAudioPlayer": $AudioStreamPlayer3D,
	"godotAudioBufferLength": 0.5,
	"drm": {
		"scheme": "widevine",
		"licenseUrl": license_url,
		"requestHeaders": {
			"Authorization": "Bearer <token>"
		}
	}
})
```

For a clearer protected-content call site, the same DRM setup can be written as:

```gdscript
var player_id = ExoPlayer.create_drm_player(
    surface,
    video_uri,
    license_url,
    {"Authorization": "Bearer <token>"},
    {"autoplay": true}
)
```

Use `set_url(id, uri)` to switch a DRM player back to clear content, or
`set_drm_media(id, uri, license_url, headers)` to change it to protected content.

Supported options:

- `autoplay`: Starts playback after the player is prepared. Defaults to `false`.
- `volume`: Initial player volume from `0.0` to `1.0`. Defaults to `1.0`.
- `repeatMode`: ExoPlayer repeat mode integer. Defaults to off.
- `playbackSpeed`: Initial playback speed. Defaults to `1.0`.
- `useCache`: Enables the plugin cache data source. Defaults to `false`.
- `cacheMaxBytes`: Shared LRU streaming-cache limit. Defaults to 256 MiB; the first active cache configuration owns the shared limit.
- `requestHeaders`: Headers applied to media and manifest requests.
- `userAgent`: Optional HTTP user agent.
- `allowCrossProtocolRedirects`: Allows HTTP-to-HTTPS or HTTPS-to-HTTP redirects. Defaults to `false`.
- `pauseOnAppPause`: Pauses active playback while the Android XR app is backgrounded and resumes only players that were previously playing. Defaults to `true`.
- `parseProgramDateTime`: Enables the legacy `getProgramDateTime()` query for the current timeline window's UTC start time. Defaults to `false`. Uses Media3's timeline, including master-playlist resolution and live updates, instead of fetching/parsing the URL separately.
- `debugLogging`: Enables verbose Media3 logging and track debug output. Defaults to `false`.
- `routeAudioToGodot`: Routes decoded ExoPlayer PCM through Godot instead of Android's audible audio output. Defaults to `false`.
- `godotAudioPlayer`: Optional `AudioStreamPlayer` or `AudioStreamPlayer3D` used for routed audio. The wrapper creates and assigns an `AudioStreamGenerator`; you do not need to create the generator yourself.
- `godotAudioBufferLength`: AudioStreamGenerator buffer length in seconds for routed audio. Defaults to `0.5`.
- `drm`: Optional DRM config. Widevine is currently supported with `scheme = "widevine"` and `licenseUrl`.

When `routeAudioToGodot` is enabled, ExoPlayer's Android audio output stays muted and `setPlayerVolume` controls the Godot audio player volume. The Kotlin plugin exposes `pollAudioFrames(id, maxFrames)`, `getAudioFormat(id)`, and `clearAudioBuffer(id)` for the wrapper's polling path.

`get_audio_bridge_stats(id)` returns the current format, queued frames, dropped frames, and underrun frames. Godot routing is intended for bus effects, visualization, or spatial placement. It uses a separate Godot audio clock, so applications should validate latency and A/V synchronization on their target XR hardware.

The old helper still works for simple Widevine usage:

```gdscript
var player_id = ExoPlayer.create_exoplayer_instance(surface, video_uri, license_url)
```

#### Controls and Queries

The wrapper exposes playback controls such as `play`, `pause`, `seekTo`, `seekBy`, `setMedia`, `setPlayerVolume`, `setRepeatMode`, and `setPlaybackSpeed`.

It also exposes `getVideoResolutions`, `setVideoResolution`, `getAvailableAudioTracks`, `setAudioTrack`, `getAvailableTextTracks`, `setTextTrack`, `getCurrentPlaybackPosition`, `getVideoDuration`, and `getProgramDateTime`.

The preferred structured track API is `get_video_tracks`, `get_audio_tracks`, and `get_subtitle_tracks`, paired with `select_video_track`, `select_audio_track`, and `select_subtitle_track`. Track dictionaries contain their exact index, selected/supported state, format metadata, and type-specific properties. The older resolution and camelCase helpers remain for compatibility.

Signals:

- `player_created(id)` — the native player was successfully constructed.
- `player_ready(id, duration)`
- `player_error(id, error_message)`
- `video_end(id)`
- `player_state_changed(id, state)`
- `subtitle_cues(id, cues)` — selected subtitle text for rendering in Godot UI.

### Limitations
- Currently experimental and may contain bugs or incomplete features.
- Only supports Android OpenXR composition-layer surfaces; this is intentionally an XR plugin.
- Godot-routed audio requires target-device validation for latency and A/V drift under XR load.
- May lack complete documentation and features
- Only supports Version 4.4 and onwards (hopefully)

### Live playback

Set `liveTargetOffsetMs` in player/item options (or `ExoPlayerSourceConfig.live_target_offset_ms`).
`-1` keeps the stream defaults; `0` or greater requests a target offset in milliseconds.
This is a latency target, not a guarantee or an override of the source's capabilities.

```gdscript
var id = ExoPlayer.create_url_player(surface, stream_url, {
    "autoplay": true,
    "liveTargetOffsetMs": 4000
})
var live = ExoPlayer.get_live_state(id)
if live.get("is_live", false):
    ExoPlayer.go_live(id)
```

`get_live_state()` returns `is_live`, `is_dynamic`, `is_seekable`, `live_offset_ms`,
`position_ms`, `window_duration_ms`, `default_position_ms`, `window_start_unix_ms`,
`playback_unix_ms`, and `target_offset_ms`. Unknown times are `-1`. Positions are relative
to the current window; its start can move. `go_live()` seeks to the source's default
live position and preserves the playing/paused state. `is_live()` and `is_seekable()`
are convenience queries. `live_state_changed(id, state)` is emitted when the timeline changes;
poll a snapshot for a continuously changing position or offset.

The legacy `getProgramDateTime()` returns the current window's UTC start as an ISO string
when enabled, or an empty string if unknown. Use `playback_unix_ms` for the current media position.

### Diagnostics

`get_playback_stats(id)` collects one snapshot on the native player thread. It includes:

- Playback position/duration, buffered position/duration, state, playing state and play intent.
- Video width/height, bitrate, frame rate, decoder name, dropped frames and bandwidth estimate.
- Buffering count/time, cache/network bytes, retry attempts, current media ID and playlist index.
- Nested `live` and `audio_bridge` dictionaries; the latter retains the existing PCM diagnostics.

Counters cover a player session, including its playlist; `buffering_count` includes initial preparation.
Network bytes count media/source transfers, not DRM license traffic. Unknown format values are `-1`.
PCM queue counters measure the bridge, not end-to-end A/V latency.

Add an `ExoPlayerDiagnosticsPanel` to a Control/SubViewport and assign its `player_id`.
It refreshes twice per second by default and offers previous/next, play/pause, Go live,
retry and quality controls. The existing demo video controls expose it via **Diagnostics**.
Avoid querying native snapshots every rendered frame: the queries synchronize with Android's UI thread.

### Errors and recovery

The existing string `player_error` signal remains. `playback_error(id, error)` additionally
provides `code`, `code_name`, `message`, `http_status` (`0` if absent), `retryable` and `media_id`.
The structured message uses the error name so it does not embed signed request URLs.

```gdscript
ExoPlayer.set_retry_policy(id, {
    "maxAttempts": 3,
    "baseDelayMs": 1000,
    "maxDelayMs": 8000,
    "liveAtDefaultPosition": true
})
# Explicit recovery starts a fresh retry budget and resumes playback.
ExoPlayer.retry(id)       # Live: default live position; VOD: retained position.
ExoPlayer.retry(id, false) # Keep the current position, including for live streams.
```

Automatic recovery is disabled by default. Configure it through `set_retry_policy()`,
the initial `retryPolicy` options dictionary, or an `ExoPlayerRetryConfig` on the layer.
Retries use exponential backoff, capped by `maxDelayMs`. The budget is per media item,
reset by changing items, setting a policy, credential refresh, or explicit `retry()`.
It does not reset merely because a failing stream briefly becomes ready.
Transient connection/timeouts, behind-live-window errors, HTTP 408/429 and HTTP 5xx
are eligible; decoder/format failures and other HTTP errors do not loop automatically.
Signals are `retry_scheduled(id, attempt, delay_ms)` and `retry_exhausted(id, error)`.
Pause, stop, release, source replacement and queue navigation cancel pending retries.

HTTP 401/403 emits `authentication_required(id, error)` and waits for the app:

```gdscript
func _on_authentication_required(id: int, _error: Dictionary) -> void:
    var media_headers = await obtain_fresh_media_headers() # Your authentication service.
    ExoPlayer.refresh_credentials(id, media_headers, {})
```

`refresh_credentials(id, media_headers, license_headers := {})` replaces the current
item's complete header sets, recreates its source, retains the queue and VOD position,
and resumes playback. For DRM, supply fresh license headers in the third argument too.
Connect this signal before playback starts. The plugin does not obtain tokens itself.
If your token is embedded in the URL, supply a new source with `set_url()`/`set_drm_media()`;
those operations replace the queue.

`play()` also prepares a stopped player. Readiness follows the current native state;
background pausing covers buffering players with play intent as well as actively playing ones.

### Quality and language preferences

```gdscript
ExoPlayer.set_track_preferences(id, {
    "maxWidth": 1920, "maxHeight": 1080, "maxBitrate": 6000000,
    "audioLanguage": "de", "textLanguage": "en", "subtitlesEnabled": true
})
```

Only supplied keys change. `-1` removes a size/bitrate limit; an empty language clears
that language preference. Preferences apply across playlist items. They clear manual
track overrides by default; pass `clearOverrides: false` to keep them. Exact selection
with `select_video_track`/`select_audio_track`/`select_subtitle_track` remains available.
`tracks_changed(id)` announces track-list changes, and `selected_tracks_changed(id)`
also covers downstream format changes during adaptive playback. Fetch fresh track
lists after an item transition: indices are not stable across items.

Use the initial `trackPreferences` options dictionary or an `ExoPlayerTrackPreferences`
Resource on the composition layer to apply preferences before preparation.

### External subtitles and text overlay

```gdscript
var options = {"subtitles": [{
    "uri": "https://example.com/captions.vtt", "mimeType": "text/vtt",
    "language": "en", "label": "English", "default": true
}]}
var id = ExoPlayer.create_url_player(surface, video_url, options)
$SubtitleView.player_id = id
```

External subtitles use the item's data source and request headers. An empty `subtitles`
array removes inherited external subtitles when changing media. Inspector configuration
uses `ExoPlayerSourceConfig.subtitles`, an array of `ExoPlayerSubtitleConfig` Resources.

`subtitle_cues_detailed(id, cues)` supplies text, alignment, fractional position/width,
position anchor, line value/type/anchor, `line_set` and a bitmap-presence flag.
The original plain-string `subtitle_cues` signal remains supported.
`ExoPlayerSubtitleView` renders plain text cues, common alignment/position information,
and bottom stacking. Set its `player_id` and size it over the video in a Control or XR
SubViewport. The demo does this automatically. It is not a full ASS/TTML styling or
bitmap subtitle renderer; font spans, vertical writing and bitmap pixels are not transferred.

### Playlists

```gdscript
ExoPlayer.enqueue(id, next_url, {"mediaId": "next-episode"})
ExoPlayer.enqueue(id, protected_url, {
    "mediaId": "premium-episode",
    "requestHeaders": media_headers,
    "drm": {"scheme": "widevine", "licenseUrl": license_url, "requestHeaders": license_headers}
})
ExoPlayer.next(id)
ExoPlayer.previous(id)
ExoPlayer.seek_playlist_item(id, 0) # Default position of an item; does not force playback.
ExoPlayer.remove_playlist_item(id, 1)
ExoPlayer.set_shuffle(id, true)
var queue = ExoPlayer.get_playlist(id)
```

Each item has its own headers, DRM, subtitles and live target. Omitted headers/DRM on
an enqueued item mean clear playback with no media headers; they are not copied from
the previous item. Audio routing and buffer durations belong to the player and cannot
change through enqueue. IDs must be unique within a queue; missing IDs are generated.
`get_playlist()` returns `index`, `media_id`, `shuffle` and an `items` array of index/ID
dictionaries, without exposing request URLs or credentials. Indices refer to the original
queue order even with shuffle enabled.

`playlist_changed(id)` announces timeline/queue updates. `media_item_transition(id,
media_id, index, reason)` reports transitions with Media3's reason integer.
Existing repeat modes apply to the queue. `set_media()`/`set_url()`/`set_drm_media()`
replace it with one item; `video_end` denotes reaching the end of playback, not every transition.

### Cache management

```gdscript
var cache = ExoPlayer.get_cache_stats()
print(cache.get("size_bytes", 0))
# Release every plugin player before deleting cached data.
ExoPlayer.release_player(id)
ExoPlayer.remove_cached_media(video_url)
# Or explicitly remove all cached media:
# ExoPlayer.clear_cache()
```

`get_cache_stats()` returns `initialized`, `busy`, `size_bytes`, `max_bytes` and
`resource_count`. It opens an existing disk cache when necessary. Inspecting the cache
before the first cached player establishes the default 256 MiB shared limit; create
your configured cached player first if you need a different limit.

Removal is asynchronous and reports `cache_operation_completed(success, message)`.
It is rejected while any plugin player exists or another removal is running. New player
creation is rejected during maintenance. Removal by URI includes that source's cached
manifest/segments/subtitles; namespaces also distinguish request headers. Supply the
exact original URI, including its query. Passing an empty URI to `remove_cached_media()`
is rejected; use `clear_cache()` deliberately. Clearing operates on Media3 cache resources,
not arbitrary files. This remains an evictable streaming cache, not an offline downloader.
Entries created by older plugin versions use the old keys and require `clear_cache()`
for complete removal.

All extended player events are also available through `player_event(id, event, data)`
on the autoload and are filtered per player on `ExoPlayerCompositionLayer`. The layer
has convenience methods for snapshots, retry, credential refresh, preferences and queue
controls. Cache operations remain global on the autoload.


### Used Addons
- 

### Contributing
Contributions are welcome! If you encounter issues or have suggestions, feel free to open an issue or submit a pull request. As this is an experimental project, active collaboration will help shape its development.

