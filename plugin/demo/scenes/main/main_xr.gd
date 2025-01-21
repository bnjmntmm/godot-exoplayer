extends Node3D


@export var passthrough : bool = false

@onready var environment : Environment = $WorldEnvironment.environment

var viewport : Viewport
var xr_interface: XRInterface

var _plugin_name = "godot_exoplayer"
var _android_plugin



func _ready() -> void:
	viewport = get_viewport()
	xr_interface = XRServer.find_interface("OpenXR")
	if xr_interface and xr_interface.is_initialized():
		print("OpenXR initialised")
		DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_DISABLED)
		if passthrough:
			enable_passthrough()
		get_viewport().use_xr = true

	else:
		print("OpenXR not initialized!")
	
	##load plugin
	if Engine.has_singleton(_plugin_name):
		_android_plugin = Engine.get_singleton(_plugin_name)
		
	
	await get_tree().create_timer(3).timeout
	get_android_surface()
	_android_plugin.play(1)

func get_android_surface():
	if _android_plugin:
		var android_surface = $XROrigin3D/CompLayer.get_android_surface()
		if android_surface:
			print(android_surface)
			##create exoplayer
			_android_plugin.createExoPlayerSurface(1,"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",android_surface)
		

func enable_passthrough():
	if xr_interface:
		var modes = xr_interface.get_supported_environment_blend_modes()
		if XRInterface.XR_ENV_BLEND_MODE_ALPHA_BLEND in modes:
			xr_interface.environment_blend_mode = XRInterface.XR_ENV_BLEND_MODE_ALPHA_BLEND
			viewport.transparent_bg = true
			print("transparent background!11")
		elif XRInterface.XR_ENV_BLEND_MODE_ADDITIVE in modes:
			xr_interface.environment_blend_mode = XRInterface.XR_ENV_BLEND_MODE_ADDITIVE
			viewport.transparent_bg = false
	else:
		return false
	environment.background_mode = Environment.BG_COLOR
	environment.background_color = Color(0.0, 0.0, 0.0, 0.0)
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	return true
