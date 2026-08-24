extends Node

signal player_created(id: int)
signal player_ready(id: int, duration:float)
signal player_error(id: int, error_message: String)
signal video_end(id: int)
signal player_state_changed(id: int, state: int)
signal subtitle_cues(id: int, cues: PackedStringArray)
signal audio_resynced(id: int, queued_frames: int)


var _plugin_name = "godot_exoplayer"
var _android_plugin

## Store player states: { id: {is_ready: bool, duration: float, error: Dictionary}}
var players : Dictionary = {}
var exoplayer_id_array : Array = []
var current_id : int = 1
var _managed_audio_players : Dictionary = {}

const DEFAULT_GODOT_AUDIO_BUFFER_LENGTH := 0.5
const DEFAULT_GODOT_AUDIO_MIX_RATE := 48000
const GODOT_AUDIO_POLL_FRAMES := 2048
const AUDIO_RESYNC_THRESHOLD_RATIO := 0.9375
const AUDIO_RESYNC_SATURATION_MS := 1500
const STATE_IDLE := 1
const STATE_BUFFERING := 2
const STATE_READY := 3
const STATE_ENDED := 4

func _ready() -> void:
	##load plugin
	if Engine.has_singleton(_plugin_name):
		_android_plugin = Engine.get_singleton(_plugin_name)
		connect_plugin_signals()


func create_player(android_surface, video_uri: String, options: Dictionary = {}) -> int:
	if _android_plugin and android_surface:
		var new_id = current_id
		var config := options.duplicate(true)
		config["uri"] = video_uri
		var android_config := config.duplicate(true)
		android_config.erase("godotAudioPlayer")
		android_config.erase("godotAudioPlayers")
		android_config.erase("spatialAudioMode")
		android_config.erase("godotAudioBufferLength")

		players[new_id] = {
			"native_created": false,
			"state": STATE_IDLE,
			"is_ready": false,
			"duration": -1.0,
			"error": null,
			"surface": android_surface,
			"uri": video_uri,
			"options": config,
			"route_audio_to_godot": bool(config.get("routeAudioToGodot", false)),
			"godot_audio_players": [],
			"godot_audio_streams": [],
			"godot_audio_playbacks": [],
			"godot_audio_player": null, # Backwards compatibility
			"godot_audio_stream": null,  # Backwards compatibility
			"godot_audio_playback": null, # Backwards compatibility
			"godot_audio_original_states": [],
			"godot_audio_sample_rate": 0,
			"godot_audio_buffer_length": float(config.get("godotAudioBufferLength", DEFAULT_GODOT_AUDIO_BUFFER_LENGTH)),
			"logical_volume": float(config.get("volume", 1.0)),
			"spatial_audio_mode": int(config.get("spatialAudioMode", 0)),
			"native_is_playing": false,
			"audio_resync_saturated_since_msec": 0
		}

		if players[new_id].route_audio_to_godot:
			_setup_godot_audio(new_id, config)

		_android_plugin.createExoPlayer(current_id, android_surface, android_config)

		exoplayer_id_array.append(new_id)
		current_id +=1
		return new_id
	return -1

## Creates a normal, unprotected URL player. DRM is never required for this path.
func create_url_player(android_surface, video_uri: String, options: Dictionary = {}) -> int:
	var config := options.duplicate(true)
	config["clearDrm"] = true
	return create_player(android_surface, video_uri, config)

## Creates a Widevine player without making DRM part of the common URL workflow.
func create_drm_player(android_surface, video_uri: String, license_url: String, request_headers: Dictionary = {}, options: Dictionary = {}) -> int:
	var config := options.duplicate(true)
	config["clearDrm"] = false
	config["drm"] = {
		"scheme": "widevine",
		"licenseUrl": license_url,
		"requestHeaders": request_headers
	}
	return create_player(android_surface, video_uri, config)

## Backwards-compatible helper for the original Widevine-focused API.
func create_exoplayer_instance(android_surface, video_uri, license_url : String = "") -> int:
	if license_url != "":
		return create_drm_player(android_surface, video_uri, license_url)
	return create_url_player(android_surface, video_uri)

#region Player Controls

func play(id):
	if _android_plugin:
		_android_plugin.play(id)
		_play_godot_audio(id)
func pause(id):
	if _android_plugin:
		_android_plugin.pause(id)
		_pause_godot_audio(id)

func stop(id: int) -> void:
	if _android_plugin:
		_clear_godot_audio_buffer(id)
		_android_plugin.stop(id)
		_pause_godot_audio(id)

func seekTo(id, positionMs):
	if _android_plugin:
		_clear_godot_audio_buffer(id)
		_android_plugin.seekTo(id, positionMs)

func seekBy(id, deltaMs):
	if _android_plugin:
		_clear_godot_audio_buffer(id)
		_android_plugin.seekBy(id, deltaMs)

func seek_to(id: int, position_ms: int) -> void:
	seekTo(id, position_ms)

func seek_by(id: int, delta_ms: int) -> void:
	seekBy(id, delta_ms)


## Returns the Playback Position in the current content in ms
func getCurrentPlaybackPosition(id):
	if _android_plugin:
		return _android_plugin.getCurrentPosition(id)

func get_position_ms(id: int) -> int:
	return int(getCurrentPlaybackPosition(id))

## Returns the duration of the current content in ms
func getVideoDuration(id):
	if is_player_ready(id):
		return players[id].duration
	return -1.0

func get_duration_ms(id: int) -> int:
	return int(getVideoDuration(id))

func is_playing(id: int) -> bool:
	return _android_plugin != null and players.has(id) and _android_plugin.isPlaying(id)

func get_playback_state(id: int) -> int:
	return _android_plugin.getPlaybackState(id) if _android_plugin and players.has(id) else 1

func get_buffered_position(id: int) -> int:
	return int(_android_plugin.getBufferedPosition(id)) if _android_plugin and players.has(id) else -1

func set_surface(id: int, android_surface) -> void:
	if _android_plugin and players.has(id) and android_surface:
		_android_plugin.setSurface(id, android_surface)
		players[id].surface = android_surface

func clear_surface(id: int) -> void:
	if _android_plugin and players.has(id):
		_android_plugin.clearSurface(id)

func get_audio_bridge_stats(id: int) -> Dictionary:
	return _android_plugin.getAudioFormat(id) if _android_plugin and players.has(id) else {}

func setPlayerVolume(id: int, volume: float):
	if _android_plugin:
		var safe_volume := clampf(volume, 0.0, 1.0)
		if players.has(id):
			players[id].logical_volume = safe_volume
			_apply_godot_audio_volume(id)
		_android_plugin.setVolume(id, safe_volume)
func getPlayerVolume(id:int):
	if players.has(id):
		return players[id].get("logical_volume", 1.0)
	if _android_plugin:
		return _android_plugin.getVolume(id)

func set_volume(id: int, volume: float) -> void:
	setPlayerVolume(id, volume)

func get_volume(id: int) -> float:
	return float(getPlayerVolume(id))

func setRepeatMode(id: int, mode: int):
	if _android_plugin:
		_android_plugin.setRepeatMode(id, mode)

func setPlaybackSpeed(id: int, speed: float):
	if _android_plugin:
		_android_plugin.setPlaybackSpeed(id, speed)

func setMedia(id: int, video_uri: String, options: Dictionary = {}):
	if _android_plugin and players.has(id):
		# Preserve optional setup such as caller-owned audio nodes unless overridden.
		var config: Dictionary = players[id].options.duplicate(true)
		config.merge(options, true)
		config["uri"] = video_uri
		config["routeAudioToGodot"] = players[id].route_audio_to_godot
		if not config.has("volume"):
			config["volume"] = players[id].logical_volume
		var android_config := config.duplicate(true)
		android_config.erase("godotAudioPlayer")
		android_config.erase("godotAudioPlayers")
		android_config.erase("spatialAudioMode")
		android_config.erase("godotAudioBufferLength")
		_clear_godot_audio_buffer(id)
		_android_plugin.setMedia(id, android_config)
		players[id].uri = video_uri
		players[id].options = config
		players[id].is_ready = false
		players[id].duration = -1.0
		players[id].logical_volume = float(config.get("volume", players[id].logical_volume))
		if players[id].route_audio_to_godot:
			_release_godot_audio(id)
			_setup_godot_audio(id, config)
		else:
			_release_godot_audio(id)

func set_media(id: int, video_uri: String, options: Dictionary = {}) -> void:
	setMedia(id, video_uri, options)

## Switches an existing player to unprotected media and removes inherited DRM.
func set_url(id: int, video_uri: String, options: Dictionary = {}) -> void:
	var config := options.duplicate(true)
	config.erase("drm")
	config["clearDrm"] = true
	setMedia(id, video_uri, config)

## Switches an existing player to Widevine-protected media.
func set_drm_media(id: int, video_uri: String, license_url: String, request_headers: Dictionary = {}, options: Dictionary = {}) -> void:
	var config := options.duplicate(true)
	config["clearDrm"] = false
	config["drm"] = {
		"scheme": "widevine",
		"licenseUrl": license_url,
		"requestHeaders": request_headers
	}
	setMedia(id, video_uri, config)

func getAvailableAudioTracks(id: int):
	if _android_plugin:
		return _android_plugin.getAudioTracks(id)

func setAudioTrack(player_id: int, audioTrackIndex: int):
	if _android_plugin:
		_android_plugin.setAudioTrack(player_id, audioTrackIndex)

func getAvailableTextTracks(id: int):
	if _android_plugin:
		return _android_plugin.getTextTracks(id)

func setTextTrack(player_id: int, textTrackIndex: int):
	if _android_plugin:
		_android_plugin.setTextTrack(player_id, textTrackIndex)

func getProgramDateTime(id: int) -> String:
	if _android_plugin:
		return _android_plugin.getProgramDateTime(id)
	return ""

#endregion

#region Helpers

func is_player_ready(id: int) -> bool:
	return players.get(id,{}).get("is_ready",false)

func get_player_error(id: int) -> Dictionary:
	return players.get(id,{}).get("error",{})

func release_player(id:int) -> void:
	if _android_plugin and players.has(id):
		_android_plugin.pause(id)
		_release_godot_audio(id)
		_android_plugin.releaseExoPlayer(id)
		players.erase(id)
		exoplayer_id_array.erase(id)


func getVideoResolutions(id: int) :
	if _android_plugin and players.has(id):
		return _android_plugin.getResolutions(id)
	return []

func setVideoResolution(id: int, width : int, height: int):
	if _android_plugin and players.has(id):
		_android_plugin.setResolution(id, width, height)

func get_video_tracks(id: int) -> Array:
	return Array(_android_plugin.getVideoTracks(id)) if _android_plugin and players.has(id) else []

func select_video_track(id: int, track_index: int) -> void:
	if _android_plugin and players.has(id):
		_android_plugin.setVideoTrack(id, track_index)

func get_audio_tracks(id: int) -> Array:
	return Array(getAvailableAudioTracks(id))

func select_audio_track(id: int, track_index: int) -> void:
	setAudioTrack(id, track_index)

func get_subtitle_tracks(id: int) -> Array:
	return Array(getAvailableTextTracks(id))

func select_subtitle_track(id: int, track_index: int) -> void:
	setTextTrack(id, track_index)

func _process(_delta: float) -> void:
	if not _android_plugin:
		return
	for id in players.keys():
		if players.has(id) and players[id].route_audio_to_godot:
			_pump_godot_audio(id)

func _setup_godot_audio(id: int, config: Dictionary) -> void:
	var players_list = []
	var managed_list = []
	var owns_players := false

	var config_players = config.get("godotAudioPlayers", [])
	if config_players.size() > 0:
		for cp in config_players:
			if cp != null:
				players_list.append(cp)
	else:
		var single_player = config.get("godotAudioPlayer", null)
		if single_player != null:
			players_list.append(single_player)
		else:
			var default_player = AudioStreamPlayer.new()
			add_child(default_player)
			players_list.append(default_player)
			managed_list.append(default_player)
			owns_players = true

	var streams_list = []
	var playbacks_list = []
	var original_states = []
	var sample_rate_val = DEFAULT_GODOT_AUDIO_MIX_RATE
	var buffer_length_val = float(config.get("godotAudioBufferLength", DEFAULT_GODOT_AUDIO_BUFFER_LENGTH))

	for p in players_list:
		var original_state := {}
		if p != null and not managed_list.has(p):
			original_state = {
				"stream": p.stream,
				"volume_linear": p.volume_linear,
				"playing": p.playing,
				"position": p.get_playback_position() if p.playing else 0.0
			}
		original_states.append(original_state)
		if p != null:
			var stream := AudioStreamGenerator.new()
			stream.mix_rate = sample_rate_val
			stream.buffer_length = buffer_length_val
			p.stream = stream
			p.volume_linear = players[id].logical_volume
			p.play()
			streams_list.append(stream)
			playbacks_list.append(p.get_stream_playback())
		else:
			streams_list.append(null)
			playbacks_list.append(null)

	players[id].godot_audio_players = players_list
	players[id].godot_audio_streams = streams_list
	players[id].godot_audio_playbacks = playbacks_list
	players[id].godot_audio_original_states = original_states
	players[id].godot_audio_sample_rate = sample_rate_val
	players[id].godot_audio_buffer_length = buffer_length_val

	# Backward compatibility properties
	if players_list.size() > 0:
		players[id].godot_audio_player = players_list[0]
		players[id].godot_audio_stream = streams_list[0]
		players[id].godot_audio_playback = playbacks_list[0]

	if owns_players:
		_managed_audio_players[id] = managed_list

func _pump_godot_audio(id: int) -> void:
	var players_list = players[id].godot_audio_players
	if players_list.size() == 0:
		return

	var format := _android_plugin.getAudioFormat(id) as Dictionary
	_update_godot_audio_format(id, format)
	_audio_resync_guard(id, format)

	var playbacks_list = players[id].godot_audio_playbacks
	for i in range(players_list.size()):
		var p = players_list[i]
		if p != null:
			var playback = playbacks_list[i]
			if playback == null:
				if not p.playing:
					p.play()
				playbacks_list[i] = p.get_stream_playback()

	if playbacks_list.size() > 0:
		players[id].godot_audio_playback = playbacks_list[0]

	# Determine minimum frames available across all playbacks
	var min_frames_available := 999999
	for i in range(playbacks_list.size()):
		var playback = playbacks_list[i]
		if playback != null:
			min_frames_available = mini(min_frames_available, playback.get_frames_available())
		else:
			min_frames_available = 0
			break

	if min_frames_available <= 0:
		return

	var frames_to_poll: int = mini(min_frames_available, GODOT_AUDIO_POLL_FRAMES)
	var samples = _android_plugin.pollAudioFrames(id, frames_to_poll)
	if samples.size() > 0:
		players[id]["last_samples"] = samples
	var frame_count: int = samples.size() / 2
	if frame_count <= 0:
		return

	var spatial_mode = players[id].get("spatial_audio_mode", 0)

	# Split Stereo (1) with exactly 2 speakers
	if spatial_mode == 1 and players_list.size() == 2:
		var p0_playback = playbacks_list[0]
		var p1_playback = playbacks_list[1]
		if p0_playback != null and p1_playback != null:
			var left_frames := PackedVector2Array()
			var right_frames := PackedVector2Array()
			left_frames.resize(frame_count)
			right_frames.resize(frame_count)
			for frame in range(frame_count):
				var left = samples[frame * 2]
				var right = samples[frame * 2 + 1]
				left_frames[frame] = Vector2(left, left)
				right_frames[frame] = Vector2(right, right)
			p0_playback.push_buffer(left_frames)
			p1_playback.push_buffer(right_frames)
	else:
		# Duplicated Stereo
		var frames := PackedVector2Array()
		frames.resize(frame_count)
		for frame in range(frame_count):
			var left = samples[frame * 2]
			var right = samples[frame * 2 + 1]
			frames[frame] = Vector2(left, right)
		for playback in playbacks_list:
			if playback != null:
				playback.push_buffer(frames)

func _update_godot_audio_format(id: int, format: Dictionary) -> void:
	var sample_rate := int(format.get("sampleRate", 0))
	if sample_rate <= 0 or sample_rate == int(players[id].godot_audio_sample_rate):
		return

	var sample_rate_val = sample_rate
	var buffer_length_val = float(players[id].godot_audio_buffer_length)
	var players_list = players[id].godot_audio_players
	var streams_list = []
	var playbacks_list = []

	for i in range(players_list.size()):
		var p = players_list[i]
		if p != null:
			var stream := AudioStreamGenerator.new()
			stream.mix_rate = sample_rate_val
			stream.buffer_length = buffer_length_val
			p.stream = stream
			p.play()
			streams_list.append(stream)
			playbacks_list.append(p.get_stream_playback())
		else:
			streams_list.append(null)
			playbacks_list.append(null)

	players[id].godot_audio_streams = streams_list
	players[id].godot_audio_playbacks = playbacks_list
	players[id].godot_audio_sample_rate = sample_rate_val

	# Backward compatibility properties
	if players_list.size() > 0:
		players[id].godot_audio_player = players_list[0]
		players[id].godot_audio_stream = streams_list[0]
		players[id].godot_audio_playback = playbacks_list[0]

	_android_plugin.clearAudioBuffer(id)

func _play_godot_audio(id: int) -> void:
	if not players.has(id) or not players[id].route_audio_to_godot:
		return
	var players_list = players[id].godot_audio_players
	var playbacks_list = players[id].godot_audio_playbacks
	for i in range(players_list.size()):
		var p = players_list[i]
		if p != null and not p.playing:
			p.play()
			playbacks_list[i] = p.get_stream_playback()
	if playbacks_list.size() > 0:
		players[id].godot_audio_playback = playbacks_list[0]

func _pause_godot_audio(id: int) -> void:
	if not players.has(id) or not players[id].route_audio_to_godot:
		return
	var players_list = players[id].godot_audio_players
	for p in players_list:
		if p != null:
			p.stop()

func _clear_godot_audio_buffer(id: int) -> void:
	if _android_plugin:
		_android_plugin.clearAudioBuffer(id)
	if not players.has(id) or not players[id].route_audio_to_godot:
		return
	var players_list = players[id].godot_audio_players
	var playbacks_list = players[id].godot_audio_playbacks
	for i in range(players_list.size()):
		var p = players_list[i]
		if p != null:
			p.stop()
			p.play()
			playbacks_list[i] = p.get_stream_playback()
	if playbacks_list.size() > 0:
		players[id].godot_audio_playback = playbacks_list[0]

static func _audio_resync_saturated_since_msec(queued_frames: int, max_queued_frames: int, now_msec: int, saturated_since_msec: int) -> int:
	if max_queued_frames <= 0 or queued_frames < int(max_queued_frames * AUDIO_RESYNC_THRESHOLD_RATIO):
		return 0
	return saturated_since_msec if saturated_since_msec > 0 else now_msec

func _audio_resync_guard(id: int, format: Dictionary) -> void:
	if not _android_plugin or not players.has(id) or not bool(players[id].get("native_is_playing", false)):
		if players.has(id):
			players[id]["audio_resync_saturated_since_msec"] = 0
		return
	var queued := int(format.get("queuedFrames", 0))
	var now_msec := Time.get_ticks_msec()
	var saturated_since_msec := _audio_resync_saturated_since_msec(
		queued,
		int(format.get("maxQueuedFrames", 0)),
		now_msec,
		int(players[id].get("audio_resync_saturated_since_msec", 0))
	)
	players[id]["audio_resync_saturated_since_msec"] = saturated_since_msec
	if saturated_since_msec > 0 and now_msec - saturated_since_msec >= AUDIO_RESYNC_SATURATION_MS:
		_clear_godot_audio_buffer(id)
		players[id]["audio_resync_saturated_since_msec"] = 0
		emit_signal("audio_resynced", id, queued)

func _apply_godot_audio_volume(id: int) -> void:
	if not players.has(id) or not players[id].route_audio_to_godot:
		return
	var players_list = players[id].godot_audio_players
	for p in players_list:
		if p != null:
			p.volume_linear = players[id].logical_volume

func _release_godot_audio(id: int) -> void:
	if not players.has(id):
		return
	var players_list = players[id].godot_audio_players
	var original_states = players[id].get("godot_audio_original_states", [])
	for i in range(players_list.size()):
		var p = players_list[i]
		if p != null:
			p.stop()
			if i < original_states.size() and not original_states[i].is_empty():
				var state: Dictionary = original_states[i]
				p.stream = state.stream
				p.volume_linear = state.volume_linear
				if state.playing:
					p.play(state.position)
	if _managed_audio_players.has(id):
		var managed_list = _managed_audio_players[id]
		if managed_list is Array:
			for p in managed_list:
				if p != null:
					p.queue_free()
		else:
			if managed_list != null:
				managed_list.queue_free()
		_managed_audio_players.erase(id)
	players[id].godot_audio_players = []
	players[id].godot_audio_streams = []
	players[id].godot_audio_playbacks = []
	players[id].godot_audio_player = null
	players[id].godot_audio_stream = null
	players[id].godot_audio_playback = null
	players[id].godot_audio_original_states = []


#endregion

#region Signal Functions

func connect_plugin_signals() -> void:
	if _android_plugin:
		_android_plugin.connect("on_player_created", _on_player_created)
		_android_plugin.connect("on_player_ready",_on_player_ready)
		_android_plugin.connect("on_player_error", _on_player_error)
		_android_plugin.connect("on_video_end", _on_video_end)
		_android_plugin.connect("on_player_state_changed", _on_player_state_changed)
		_android_plugin.connect("on_is_playing_changed", _on_is_playing_changed)
		_android_plugin.connect("on_subtitle_cues", _on_subtitle_cues)

func _on_player_created(id: int) -> void:
	if players.has(id):
		players[id].native_created = true
	emit_signal("player_created", id)

func _on_player_ready(id: int, duration: int) -> void:
	if players.has(id):
		players[id].is_ready = true
		players[id].duration = duration
		players[id].error = null
	emit_signal("player_ready",id, duration)

func _on_player_error(id: int, error_message: String) -> void:
	if players.has(id):
		players[id].error = {
			"message" : error_message,
			"timestamp": Time.get_ticks_msec()
		}
	emit_signal("player_error", id, error_message)
#endregion


func _on_video_end(id: int) -> void:
	emit_signal("video_end", id)

func _on_player_state_changed(id: int, state: int) -> void:
	if players.has(id):
		players[id].state = state
	emit_signal("player_state_changed", id, state)

func _on_is_playing_changed(id: int, is_playing: bool) -> void:
	if players.has(id):
		players[id].native_is_playing = is_playing
		if not is_playing:
			players[id].audio_resync_saturated_since_msec = 0

func _on_subtitle_cues(id: int, cues: Array) -> void:
	emit_signal("subtitle_cues", id, PackedStringArray(cues))

func _exit_tree() -> void:
	for id in players.keys():
		release_player(id)
