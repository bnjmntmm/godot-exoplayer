extends Resource
class_name ExoPlayerRetryConfig

## Retry budget per item; 0 disables automatic recovery.
@export_range(0, 20) var max_attempts: int = 0
@export_range(100, 300000, 100, "suffix:ms") var base_delay_ms: int = 1000
@export_range(100, 300000, 100, "suffix:ms") var max_delay_ms: int = 30000
@export var live_at_default_position: bool = true

func to_options() -> Dictionary:
	return {"maxAttempts": max_attempts, "baseDelayMs": base_delay_ms,
		"maxDelayMs": max_delay_ms, "liveAtDefaultPosition": live_at_default_position}
