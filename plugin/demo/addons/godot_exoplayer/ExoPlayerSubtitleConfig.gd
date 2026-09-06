extends Resource
class_name ExoPlayerSubtitleConfig

@export var uri: String = ""
@export_enum("text/vtt", "application/x-subrip", "application/ttml+xml", "text/x-ssa") var mime_type: String = "text/vtt"
@export var language: String = ""
@export var label: String = ""
@export var select_by_default: bool = false

func to_options() -> Dictionary:
	return {"uri": uri, "mimeType": mime_type, "language": language, "label": label, "default": select_by_default}
