extends Node2D

@export var video_uri : String = ""
# TODO: Update to match your plugin's name
var _plugin_name = "godot_exoplayer"
var _android_plugin

func _ready():
	if Engine.has_singleton(_plugin_name):
		_android_plugin = Engine.get_singleton(_plugin_name)
	else:
		printerr("Couldn't find plugin " + _plugin_name)

func _on_Button_pressed():
	if _android_plugin:
		# TODO: Update to match your plugin's API
		_android_plugin.helloWorld()


func _on_p_lay_video_button_pressed() -> void:
	var surface = _android_plugin.createExoPlayerSurface(video_uri)
	print(surface)
	pass # Replace with function body.
