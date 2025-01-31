extends Control

var currently_attached_id : int
var videoDuration : float = 0
var videoPaused : bool = true
var isMute : bool = false
var prevVolume : float = 1.0


@onready var timeline_timer: Timer = $VBoxContainer/HBoxContainer2/Timeline/TimelineTimer
@onready var timeline: HSlider = $VBoxContainer/HBoxContainer2/Timeline
@onready var play_pause_button: Button = $VBoxContainer/MarginContainer/HBoxContainer/PlayPauseButton
@onready var error_label: Label = $VBoxContainer/MarginContainer/HBoxContainer/ErrorLabel
@onready var volume_slider: HSlider = $VBoxContainer/MarginContainer/HBoxContainer2/VolumeSlider

func setup_video_controls(exoplayer_id: int, duration) -> void:
	update_ui_state(false)
	currently_attached_id = exoplayer_id
	#ExoPlayer.player_ready.connect(_on_player_ready) ## player is ready before even setup is called -> not working. Need to call directly
	ExoPlayer.player_error.connect(_on_player_error)
	_on_player_ready(exoplayer_id, duration)
	error_label.hide()
	pass
	
#region ExoPlayer Signals and Functions
func _on_player_ready(id: int, duration: int) -> void:
	if id == currently_attached_id:
		videoDuration = duration
		timeline.max_value = duration / 1000.0 ## dividing because we want seconds
		update_ui_state(true)
		error_label.hide()
		#volume_slider.value = ExoPlayer.getPlayerVolume(currently_attached_id)
	pass
	
func _on_player_error(id: int, error_code: int, error_message : String) -> void:
	if id== currently_attached_id:
		show_error("Player Error %d: %s" % [error_code, error_message])
		update_ui_state(false)

func update_ui_state(ready:bool) -> void:
	timeline.editable = ready
	play_pause_button.disabled = !ready
	$VBoxContainer/MarginContainer/HBoxContainer/PlusTenButton.disabled = !ready
	$VBoxContainer/MarginContainer/HBoxContainer/MinusTenButton.disabled  = !ready

func show_error(message: String) -> void:
	error_label.text = message
	error_label.show()
#endregion

### function to set the current value while playing
func _on_timeline_timer_timeout() -> void:
	if not videoPaused:
		var current_time = ExoPlayer.getCurrentPlaybackPosition(currently_attached_id) / 1000.0
		timeline.set_block_signals(true)
		timeline.value = lerp(timeline.value, current_time, 0.3)
		timeline.set_block_signals(false)
		


func _on_timeline_value_changed(value: float) -> void:
	## we need to use seekTo but multiply by 1000 for ms
	ExoPlayer.seekTo(currently_attached_id,value*1000)


func _on_play_pause_button_pressed() -> void:
	if videoPaused:
		ExoPlayer.play(currently_attached_id)
		timeline_timer.start(0.0)
		play_pause_button.text = "Pause"
		videoPaused = false
	else:
		timeline_timer.stop()
		ExoPlayer.pause(currently_attached_id)
		play_pause_button.text = "Play"
		videoPaused = true


func _on_plus_ten_button_pressed() -> void:
	ExoPlayer.seekBy(currently_attached_id,10000) ## 1000 = 1s -> 10k -> 10s
	pass # Replace with function body.


func _on_minus_ten_button_button_up() -> void:
	ExoPlayer.seekBy(currently_attached_id,-10000)


func _on_volume_slider_value_changed(value: float) -> void:
	ExoPlayer.setPlayerVolume(currently_attached_id, value)
func _on_mute_button_pressed() -> void:
	if not isMute:
		isMute = true
		prevVolume = volume_slider.value
		volume_slider.value = 0
	else:
		isMute = false
		volume_slider.value = prevVolume
