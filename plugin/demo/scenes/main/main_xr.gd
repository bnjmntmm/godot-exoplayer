extends Node3D


@export var passthrough : bool = false
@export var video_uri : String = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

@onready var environment : Environment = $WorldEnvironment.environment

var viewport : Viewport
var xr_interface: XRInterface


var player_id : int


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
	
	
	ExoPlayer.connect("player_ready",_on_player_ready)
	ExoPlayer.connect("video_end",_on_video_end)
	await get_tree().create_timer(2).timeout
	create_android_surface()
	### play (id of player)
	#_android_plugin.play(1)
	
	## pause ( id of player)
	## _android_plugin.pause(1)

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


func create_android_surface():
	if ExoPlayer._android_plugin:
		## getting the android surface from the composition layer quad
		var android_surface = $XROrigin3D/CompLayer.get_android_surface()
		if android_surface:
			##create exoplayer using android plugin function
			## if player_id = 0 -> failed to instantiate
			player_id = ExoPlayer.create_exoplayer_instance(android_surface, video_uri)
			##call setup video controls function ont the viewport2din3D videocontrolui
			## we need to pass the exoplayer id
			if player_id > 0:
				pass

func _on_player_ready(id: int, duration:int):
	if id == player_id:
		$XROrigin3D/CompLayer/VideoControls2DIn3D.scene_node.setup_video_controls(player_id,duration) 
		
		## here we also could do ExoPlayer.play(player_id) if we want autoplay

func _on_video_end(id):
	print("Video from player " + str(id) + " has ended. Do something with it!")
