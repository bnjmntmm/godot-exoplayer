@tool
extends OpenXRCompositionLayerQuad
class_name ExoPlayerCompositionLayer

signal player_created(id: int)
signal player_ready(id: int, duration: float)
signal player_error(id: int, error_message: String)
signal video_end(id: int)
signal player_state_changed(id: int, state: int)
signal subtitle_cues(id: int, cues: PackedStringArray)
signal audio_resynced(id: int, queued_frames: int)
signal media_request_observed(id: int, url: String)

@export_group("ExoPlayer")
@export var video_uri: String = ""
@export var source_config: ExoPlayerSourceConfig
@export var buffer_config: ExoPlayerBufferConfig
@export var create_on_ready: bool = true
@export var creation_delay: float = 0.0
@export_range(0.1, 30.0, 0.1, "suffix:s") var surface_wait_timeout: float = 5.0
@export var autoplay: bool = false
@export_range(0.0, 1.0, 0.01) var volume: float = 1.0
@export var repeat_mode: int = 0
@export_range(0.1, 4.0, 0.01) var playback_speed: float = 1.0
@export var use_cache: bool = false
@export var parse_program_date_time: bool = false
@export var debug_logging: bool = false
@export var observed_url_pattern: String = ""

@export_group("DRM")
@export var drm_config: ExoPlayerDrmConfig
@export var widevine_license_url: String = ""
@export var widevine_request_headers: Dictionary = {}

@export_group("Godot Audio")
@export var audio_config: ExoPlayerAudioConfig
@export var route_audio_to_godot: bool = false
@export var godot_audio_player_path: NodePath
@export var godot_audio_players: Array[NodePath] = []
@export_enum("Duplicated Stereo", "Split Stereo") var spatial_audio_mode: int = 0
@export_range(0.05, 2.0, 0.01) var godot_audio_buffer_length: float = 0.5

var player_id: int = -1

func _ready() -> void:
	use_android_surface = true
	if Engine.is_editor_hint():
		return

	if _has_exoplayer_singleton():
		_connect_exoplayer_signals()

	if create_on_ready:
		if creation_delay > 0.0:
			await get_tree().create_timer(creation_delay).timeout
		await create_player_when_surface_ready()

func create_player_when_surface_ready(uri: String = "") -> int:
	var deadline := Time.get_ticks_msec() + int(surface_wait_timeout * 1000.0)
	while get_android_surface() == null and Time.get_ticks_msec() < deadline:
		await get_tree().process_frame
	if get_android_surface() == null:
		push_warning("Android OpenXR composition-layer surface did not become available in time.")
		return -1
	return create_player(uri)

func create_player(uri: String = "") -> int:
	if Engine.is_editor_hint():
		return -1
	if not _has_exoplayer_singleton():
		push_warning("ExoPlayer autoload is not available. Enable the godot_exoplayer plugin.")
		return -1

	if player_id > 0:
		release_player()

	var android_surface = get_android_surface()
	if android_surface == null:
		push_warning("Android surface is not available yet.")
		return -1

	var configured_uri := source_config.uri if source_config != null and source_config.uri != "" else video_uri
	var resolved_uri := uri if uri != "" else configured_uri
	if resolved_uri == "":
		push_warning("No video URI configured.")
		return -1

	player_id = ExoPlayer.create_player(android_surface, resolved_uri, _build_options())
	return player_id

func release_player() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.release_player(player_id)
	player_id = -1

func play() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.play(player_id)

func pause() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.pause(player_id)

func stop() -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.stop(player_id)

func seek_to(position_ms: int) -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.seekTo(player_id, position_ms)

func seek_by(delta_ms: int) -> void:
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.seekBy(player_id, delta_ms)

func set_media(uri: String) -> void:
	video_uri = uri
	if player_id > 0 and _has_exoplayer_singleton():
		if _is_drm_enabled():
			ExoPlayer.setMedia(player_id, uri, _build_options())
		else:
			ExoPlayer.set_url(player_id, uri, _build_options())

func set_player_volume(new_volume: float) -> void:
	volume = clampf(new_volume, 0.0, 1.0)
	if player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.setPlayerVolume(player_id, volume)

func get_player_volume() -> float:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getPlayerVolume(player_id)
	return volume

func is_player_ready() -> bool:
	return player_id > 0 and _has_exoplayer_singleton() and ExoPlayer.is_player_ready(player_id)

func get_duration() -> float:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getVideoDuration(player_id)
	return -1.0

func get_current_position() -> int:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.getCurrentPlaybackPosition(player_id)
	return -1

func is_playing() -> bool:
	return player_id > 0 and _has_exoplayer_singleton() and ExoPlayer.is_playing(player_id)

func get_playback_state() -> int:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.get_playback_state(player_id)
	return 1

func get_buffered_position() -> int:
	if player_id > 0 and _has_exoplayer_singleton():
		return ExoPlayer.get_buffered_position(player_id)
	return -1

func _exit_tree() -> void:
	if not Engine.is_editor_hint():
		release_player()

func _notification(what: int) -> void:
	if Engine.is_editor_hint() or player_id <= 0:
		return
	if what == NOTIFICATION_APPLICATION_RESUMED:
		_reattach_surface_when_ready.call_deferred()

func _reattach_surface_when_ready() -> void:
	var deadline := Time.get_ticks_msec() + int(surface_wait_timeout * 1000.0)
	var surface = get_android_surface()
	while surface == null and Time.get_ticks_msec() < deadline:
		await get_tree().process_frame
		surface = get_android_surface()
	if surface != null and player_id > 0 and _has_exoplayer_singleton():
		ExoPlayer.set_surface(player_id, surface)

func _build_options() -> Dictionary:
	var options := {
		"autoplay": autoplay,
		"volume": volume,
		"repeatMode": repeat_mode,
		"playbackSpeed": playback_speed,
		"useCache": use_cache,
		"parseProgramDateTime": parse_program_date_time,
		"debugLogging": debug_logging,
		"routeAudioToGodot": route_audio_to_godot,
		"godotAudioBufferLength": godot_audio_buffer_length,
		"spatialAudioMode": spatial_audio_mode
	}
	if source_config != null:
		options.merge(source_config.to_options(), true)
	if buffer_config != null:
		options.merge(buffer_config.to_options(), true)
	if not observed_url_pattern.is_empty():
		options["observedUrlPattern"] = observed_url_pattern
	if audio_config != null:
		options.merge(audio_config.to_options(), true)
	if drm_config != null:
		options.merge(drm_config.to_options(), true)

	if drm_config == null and widevine_license_url != "":
		options["drm"] = {
			"scheme": "widevine",
			"licenseUrl": widevine_license_url,
			"requestHeaders": widevine_request_headers
		}

	var resolved_players := []
	for path in godot_audio_players:
		var p = get_node_or_null(path)
		if p is AudioStreamPlayer or p is AudioStreamPlayer3D:
			resolved_players.append(p)
	if resolved_players.size() > 0:
		options["godotAudioPlayers"] = resolved_players
	else:
		var godot_audio_player = get_node_or_null(godot_audio_player_path)
		if godot_audio_player is AudioStreamPlayer or godot_audio_player is AudioStreamPlayer3D:
			options["godotAudioPlayer"] = godot_audio_player

	return options

func _is_drm_enabled() -> bool:
	return (drm_config != null and drm_config.is_enabled()) or widevine_license_url != ""

func _get_configuration_warnings() -> PackedStringArray:
	var warnings := PackedStringArray()
	if video_uri == "" and (source_config == null or source_config.uri == ""):
		warnings.append("Set video_uri or assign an ExoPlayerSourceConfig with a URI.")
	if drm_config != null and drm_config.is_enabled() and drm_config.license_url == "":
		warnings.append("The selected DRM configuration requires a license URL.")
	if drm_config != null and drm_config.scheme == ExoPlayerDrmConfig.Scheme.CUSTOM_UUID and drm_config.custom_scheme_uuid == "":
		warnings.append("Custom DRM requires a scheme UUID.")
	return warnings

func _connect_exoplayer_signals() -> void:
	if not ExoPlayer.player_created.is_connected(_on_exoplayer_created):
		ExoPlayer.player_created.connect(_on_exoplayer_created)
	if not ExoPlayer.player_ready.is_connected(_on_exoplayer_ready):
		ExoPlayer.player_ready.connect(_on_exoplayer_ready)
	if not ExoPlayer.player_error.is_connected(_on_exoplayer_error):
		ExoPlayer.player_error.connect(_on_exoplayer_error)
	if not ExoPlayer.video_end.is_connected(_on_exoplayer_video_end):
		ExoPlayer.video_end.connect(_on_exoplayer_video_end)
	if not ExoPlayer.player_state_changed.is_connected(_on_exoplayer_state_changed):
		ExoPlayer.player_state_changed.connect(_on_exoplayer_state_changed)
	if not ExoPlayer.subtitle_cues.is_connected(_on_exoplayer_subtitle_cues):
		ExoPlayer.subtitle_cues.connect(_on_exoplayer_subtitle_cues)
	if not ExoPlayer.audio_resynced.is_connected(_on_exoplayer_audio_resynced):
		ExoPlayer.audio_resynced.connect(_on_exoplayer_audio_resynced)
	if not ExoPlayer.media_request_observed.is_connected(_on_exoplayer_media_request_observed):
		ExoPlayer.media_request_observed.connect(_on_exoplayer_media_request_observed)

func _on_exoplayer_created(id: int) -> void:
	if id == player_id:
		emit_signal("player_created", id)

func _on_exoplayer_ready(id: int, duration: float) -> void:
	if id == player_id:
		emit_signal("player_ready", id, duration)

func _on_exoplayer_error(id: int, error_message: String) -> void:
	if id == player_id:
		emit_signal("player_error", id, error_message)

func _on_exoplayer_video_end(id: int) -> void:
	if id == player_id:
		emit_signal("video_end", id)

func _on_exoplayer_state_changed(id: int, state: int) -> void:
	if id == player_id:
		emit_signal("player_state_changed", id, state)

func _on_exoplayer_subtitle_cues(id: int, cues: PackedStringArray) -> void:
	if id == player_id:
		emit_signal("subtitle_cues", id, cues)

func _on_exoplayer_audio_resynced(id: int, queued_frames: int) -> void:
	if id == player_id:
		emit_signal("audio_resynced", id, queued_frames)

func _on_exoplayer_media_request_observed(id: int, url: String) -> void:
	if id == player_id:
		emit_signal("media_request_observed", id, url)

func _has_exoplayer_singleton() -> bool:
	return Engine.has_singleton("ExoPlayer") or has_node("/root/ExoPlayer")
