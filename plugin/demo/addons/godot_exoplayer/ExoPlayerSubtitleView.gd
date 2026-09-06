extends Control
class_name ExoPlayerSubtitleView

## Place over the video in a Control or XR SubViewport. Renders plain text cues.
@export var player_id: int = -1:
	set(value):
		player_id = value
		if is_inside_tree():
			show_cues([])
@export_range(12, 96) var font_size: int = 26
@export var text_color: Color = Color.WHITE
@export var outline_color: Color = Color.BLACK
@export_range(0, 12) var outline_size: int = 5
@export_range(0.0, 0.3) var safe_margin: float = 0.05
var _cues: Array = []

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	ExoPlayer.subtitle_cues_detailed.connect(_on_cues)
	ExoPlayer.player_state_changed.connect(_on_state)
	resized.connect(_layout_cues)

func _on_cues(id: int, cues: Array) -> void:
	if id == player_id:
		show_cues(cues)

func _on_state(id: int, state: int) -> void:
	if id == player_id and state in [ExoPlayer.STATE_IDLE, ExoPlayer.STATE_ENDED]:
		show_cues([])

## Also useful for previewing subtitle layout without a native player.
func show_cues(cues: Array) -> void:
	_cues = cues.duplicate(true)
	for child in get_children():
		remove_child(child)
		child.queue_free()
	for cue in _cues:
		var label := Label.new()
		label.mouse_filter = Control.MOUSE_FILTER_IGNORE
		label.text = str(cue.get("text", ""))
		label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		label.add_theme_font_size_override("font_size", font_size)
		label.add_theme_color_override("font_color", text_color)
		label.add_theme_color_override("font_outline_color", outline_color)
		label.add_theme_constant_override("outline_size", outline_size)
		match str(cue.get("alignment", "ALIGN_CENTER")):
			"ALIGN_NORMAL": label.horizontal_alignment = HORIZONTAL_ALIGNMENT_LEFT
			"ALIGN_OPPOSITE": label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
			_: label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		add_child(label)
	_layout_cues.call_deferred()

func _layout_cues() -> void:
	var bottom := size.y * (1.0 - safe_margin)
	for index in range(_cues.size() - 1, -1, -1):
		if index >= get_child_count():
			continue
		var cue: Dictionary = _cues[index]
		var label := get_child(index) as Label
		var width_fraction := float(cue.get("size", -1.0))
		if width_fraction < 0.0:
			width_fraction = 1.0 - safe_margin * 2.0
		label.size.x = size.x * clampf(width_fraction, 0.05, 1.0)
		label.size.y = label.get_minimum_size().y
		var horizontal := float(cue.get("position", -1.0))
		var x := (size.x - label.size.x) * 0.5
		if horizontal >= 0.0:
			var anchor_value := int(cue.get("position_anchor", 1))
			if anchor_value < 0:
				anchor_value = 1
				if label.horizontal_alignment == HORIZONTAL_ALIGNMENT_LEFT:
					anchor_value = 0
				elif label.horizontal_alignment == HORIZONTAL_ALIGNMENT_RIGHT:
					anchor_value = 2
			var anchor := clampi(anchor_value, 0, 2) * 0.5
			x = size.x * horizontal - label.size.x * anchor
		var y := bottom - label.size.y
		if bool(cue.get("line_set", false)):
			var line := float(cue.get("line", 0.0))
			var anchor := clampi(int(cue.get("line_anchor", 0)), 0, 2) * 0.5
			if int(cue.get("line_type", 0)) == 0:
				y = size.y * line - label.size.y * anchor
			else:
				y = line * font_size if line >= 0 else size.y + (line + 1) * font_size - label.size.y
		else:
			bottom = y - 6.0
		label.position = Vector2(clampf(x, 0, maxf(0, size.x - label.size.x)), clampf(y, 0, maxf(0, size.y - label.size.y)))
