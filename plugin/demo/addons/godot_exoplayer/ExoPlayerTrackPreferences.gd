extends Resource
class_name ExoPlayerTrackPreferences

## -1 removes a limit. Applying preferences clears manual track overrides by default.
@export var max_bitrate: int = -1
@export var max_width: int = -1
@export var max_height: int = -1
@export var audio_language: String = ""
@export var text_language: String = ""
@export var subtitles_enabled: bool = true

func to_options() -> Dictionary:
	return {"maxBitrate": max_bitrate, "maxWidth": max_width, "maxHeight": max_height,
		"audioLanguage": audio_language, "textLanguage": text_language,
		"subtitlesEnabled": subtitles_enabled, "clearOverrides": true}
