extends Resource
class_name ExoPlayerBufferConfig

## Optional Media3 buffer durations. Leave this resource unset to preserve Media3 defaults.
@export_range(1, 120000, 1, "suffix:ms") var min_buffer_ms: int = 30000
@export_range(1, 120000, 1, "suffix:ms") var max_buffer_ms: int = 60000
@export_range(1, 120000, 1, "suffix:ms") var buffer_for_playback_ms: int = 10000
@export_range(1, 120000, 1, "suffix:ms") var buffer_for_playback_after_rebuffer_ms: int = 15000

func to_options() -> Dictionary:
	return {"bufferDurations": {"minBufferMs": min_buffer_ms, "maxBufferMs": max_buffer_ms, "bufferForPlaybackMs": buffer_for_playback_ms, "bufferForPlaybackAfterRebufferMs": buffer_for_playback_after_rebuffer_ms}}
