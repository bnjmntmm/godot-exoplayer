# Godot ExoPlayer

**Disclaimer: This project is a Work in Progress (WIP).**

This repository explores the integration of ExoPlayer with Godot Engine 4.4. The goal is to leverage the new Android surface retrieval feature introduced in Godot 4.4 to embed ExoPlayer as a media player within Godot applications. Expect potential instability and incomplete features as this is an experimental project under active development.

## Features

- **Android Surface Retrieval:** Utilizes the new capability in Godot 4.4 to obtain Android surfaces from plugins.
- **ExoPlayer Integration:** Embeds ExoPlayer for robust media playback with support for various formats and streaming protocols.
- **Godot Engine Compatibility:** Designed specifically for Godot Engine 4.4.

## Getting Started

### Prerequisites

- **Godot Engine 4.4:** Ensure you have the latest version installed.
- **Android Development Environment:** Set up Android Studio or an equivalent environment to build and deploy Android plugins.

### Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/bnjmntmm/godot-exoplayer.git
    ```
2. Build the Addon yourself using Android Studio and Gradle or use the prebuilt inside from [godot_exoplayer](plugin%2Fdemo%2Faddons%2Fgodot_exoplayer)
3. Test out the demo in [main](plugin%2Fdemo%2Fscenes%2Fmain)

### Usage
1. Enable the Plugin: Activate the ExoPlayer plugin in your Godot project settings.
2. Surface Binding: Create a OpenXRCompositionLayer and select `use_android_surface`.
3. Retrieve the Surface: Use the `get_surface()` method to obtain the Android surface from the OpenXRCompositionLayer
4. Pass the Surface to ExoPlayer using the `createExoPlayerSurface(id: int, videoUri : String ,surface: Surface)` method.#
5. Play the video using the `play(id: int)` method.

### Limitations
- Currently experimental and may contain bugs or incomplete features.
- Only supports Android platforms.
- May lack complete documentation and features
- Only supports Version 4.4 and onwards (hopefully)


### Used Addons
- 

### Contributing
Contributions are welcome! If you encounter issues or have suggestions, feel free to open an issue or submit a pull request. As this is an experimental project, active collaboration will help shape its development.

