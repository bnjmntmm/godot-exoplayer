extends Control

@onready var option_button: OptionButton = $OptionButton

@onready var video_loaded_label: Label = $VBoxContainer/HBoxContainer2/VBoxContainer/VideoLoadedLabel
@onready var video_duration_label: Label = $VBoxContainer/HBoxContainer2/VBoxContainer/VideoDurationLabel
@onready var video_res_label: Label = $VBoxContainer/HBoxContainer2/VBoxContainer/ScrollContainer/VideoResLabel

var current_selected_player : int
var already_filled : bool = false



func clear_options() -> void:
	option_button.clear()
	already_filled = false
	video_duration_label.text = "Video duration: "
	video_loaded_label.text = "Video loaded: "
	video_res_label.text = "Video Resolutions: "


func _on_fetch_players_button_button_up() -> void:
	clear_options()
	
	for player in ExoPlayer.players:
		option_button.add_item(str(player))




func _on_fetch_player_data_button_button_up() -> void:
	var current = option_button.get_selected_id()
	current_selected_player = int(option_button.get_item_text(current))
	print("Player: ", current_selected_player)
	if not already_filled:
		already_filled = true
		var player_is_ready = ExoPlayer.is_player_ready(current_selected_player)
		var duration_in_ms = ExoPlayer.getVideoDuration(current_selected_player)
		var video_res = ExoPlayer.getResolutions(current_selected_player)
		
		video_loaded_label.text += str(player_is_ready)
		video_duration_label.text += str(duration_in_ms / 1000) + "s"
		video_res_label.text += str(video_res)
	
