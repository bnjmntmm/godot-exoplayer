package org.godotengine.plugin.android.godot_exoplayer

import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot

class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME


    // Keep track of multiple ExoPlayer instances by ID.
    private val exoPlayers = mutableMapOf<Int, ExoPlayer>()


    /**
     * Creates or recreates an ExoPlayer instance with a given ID.
     *
     * @param id       Unique identifier for this ExoPlayer instance.
     * @param videoUri The URI of the video to play.
     * @param surface  The Android Surface to render video onto (provided from Godot).
     */
    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) {
        runOnUiThread {
            try {
                // 1) If an ExoPlayer already exists for this id, release it first
                exoPlayers[id]?.release()

                // 2) Create a new ExoPlayer
                val newExoPlayer = activity?.let { ExoPlayer.Builder(it).build() }
                if (newExoPlayer == null) {
                    Log.e(pluginName, "Failed to create ExoPlayer for id $id")
                    return@runOnUiThread
                }

                // 3) Assign the provided Surface
                newExoPlayer.setVideoSurface(surface)

                // 4) Prepare the media
                val mediaItem = MediaItem.fromUri(videoUri)
                newExoPlayer.setMediaItem(mediaItem)
                newExoPlayer.prepare()

                // 5) (Optional) Start playback immediately
                //newExoPlayer.play()

                // 6) Store in the map
                exoPlayers[id] = newExoPlayer

                Log.v(pluginName, "ExoPlayer($id) created and set up with video: $videoUri")

            } catch (e: Exception) {
                Log.e(pluginName, "Error creating ExoPlayer($id): ${e.message}")
            }
        }
    }

    /**
     * Play (or resume) the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun play(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.play() ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to play")
        }
    }

    /**
     * Pause the ExoPlayer with the given ID.
     */
    @UsedByGodot
    fun pause(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.pause() ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to pause")
        }
    }

    /**
     * Optional: Stop or release a specific ExoPlayer instance.
     * This is useful if you want to free resources for one player but keep others running.
     */
    @UsedByGodot
    fun releaseExoPlayer(id: Int) {
        runOnUiThread {
            exoPlayers[id]?.let { exo ->
                exo.release()
                exoPlayers.remove(id)
                Log.v(pluginName, "ExoPlayer($id) released and removed.")
            } ?: Log.e(pluginName, "ExoPlayer($id) not found when attempting to release")
        }
    }

    /**
     * Cleanup if needed (called when Godot unloads the plugin or the activity is destroyed).
     * Releases *all* ExoPlayer instances.
     */
    override fun onMainDestroy() {
        runOnUiThread {
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
        }
        super.onMainDestroy()
    }
}
