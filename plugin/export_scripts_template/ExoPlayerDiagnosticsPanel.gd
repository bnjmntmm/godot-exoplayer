extends PanelContainer
class_name ExoPlayerDiagnosticsPanel

## Attach to any player. One native snapshot is requested twice per second.
@export var player_id: int = -1
@export_range(0.2, 5.0) var refresh_interval: float = 0.5
var _elapsed: float = 0.0
var _stats_label: Label
var _status: Label
var _go_live: Button

func _ready() -> void:
	custom_minimum_size = Vector2(430, 0)
	var margin := MarginContainer.new()
	for edge in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + edge, 12)
	add_child(margin)
	var column := VBoxContainer.new()
	margin.add_child(column)
	var title := Label.new()
	title.text = "Playback diagnostics"
	title.add_theme_font_size_override("font_size", 20)
	column.add_child(title)
	_stats_label = Label.new()
	_stats_label.text = "Waiting for a player…"
	column.add_child(_stats_label)
	var controls := HBoxContainer.new()
	column.add_child(controls)
	_add_button(controls, "Previous", func(): ExoPlayer.previous(player_id))
	_add_button(controls, "Play / pause", _toggle_playback)
	_add_button(controls, "Next", func(): ExoPlayer.next(player_id))
	_go_live = _add_button(controls, "Go live", func(): ExoPlayer.go_live(player_id))
	_add_button(controls, "Retry", func(): ExoPlayer.retry(player_id))
	var quality := HBoxContainer.new()
	column.add_child(quality)
	_add_button(quality, "Auto quality", func(): ExoPlayer.set_track_preferences(player_id, {"maxWidth": -1, "maxHeight": -1, "maxBitrate": -1}))
	_add_button(quality, "Up to 720p", func(): ExoPlayer.set_track_preferences(player_id, {"maxWidth": 1280, "maxHeight": 720}))
	_add_button(quality, "Up to 1080p", func(): ExoPlayer.set_track_preferences(player_id, {"maxWidth": 1920, "maxHeight": 1080}))
	_status = Label.new()
	_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	column.add_child(_status)
	ExoPlayer.player_event.connect(_on_event)

func _add_button(parent: Node, title: String, action: Callable) -> Button:
	var button := Button.new()
	button.text = title
	button.pressed.connect(action)
	parent.add_child(button)
	return button

func _toggle_playback() -> void:
	if bool(ExoPlayer.get_playback_stats(player_id).get("play_when_ready", false)):
		ExoPlayer.pause(player_id)
	else:
		ExoPlayer.play(player_id)

func _process(delta: float) -> void:
	_elapsed += delta
	if _elapsed < refresh_interval:
		return
	_elapsed = 0.0
	var stats := ExoPlayer.get_playback_stats(player_id)
	if not stats.is_empty():
		show_stats(stats)
	else:
		_stats_label.text = "Waiting for a player…"
		_go_live.disabled = true

func show_stats(stats: Dictionary) -> void:
	if not is_instance_valid(_stats_label):
		return
	var live: Dictionary = stats.get("live", {})
	var audio: Dictionary = stats.get("audio_bridge", {})
	_go_live.disabled = not bool(live.get("is_live", false))
	_stats_label.text = "Player %d · item %s\n%d × %d · %.2f Mbps · %s\nPosition %.1fs · buffered %.1fs · state %d\nDropped frames %d · buffering %d / %.1fs\nNetwork %.2f MiB · cache %.2f MiB\nLive offset %s · PCM queued %d / dropped %d" % [
		player_id, str(stats.get("media_id", "")), int(stats.get("width", -1)), int(stats.get("height", -1)),
		float(stats.get("bitrate_bps", 0)) / 1000000.0, str(stats.get("decoder", "unknown decoder")),
		float(stats.get("position_ms", 0)) / 1000.0, float(stats.get("buffered_duration_ms", 0)) / 1000.0,
		int(stats.get("state", 1)), int(stats.get("dropped_frames", 0)), int(stats.get("buffering_count", 0)),
		float(stats.get("buffering_ms", 0)) / 1000.0, float(stats.get("network_bytes", 0)) / 1048576.0,
		float(stats.get("cache_bytes", 0)) / 1048576.0,
		str(live.get("live_offset_ms", -1)) + " ms" if bool(live.get("is_live", false)) else "VOD",
		int(audio.get("queuedFrames", 0)), int(audio.get("droppedFrames", 0))]

func _on_event(id: int, event: String, data: Dictionary) -> void:
	if id != player_id:
		return
	match event:
		"playback_error": _status.text = "%s (HTTP %d)" % [str(data.get("code_name", "Error")), int(data.get("http_status", 0))]
		"authentication_required": _status.text = "Credentials need refreshing."
		"retry_scheduled": _status.text = "Retry %d in %.1fs" % [int(data.attempt), float(data.delay_ms) / 1000.0]
		"retry_exhausted": _status.text = "Automatic retries exhausted. Press Retry to try again."
		"media_item_transition": _status.text = ""
