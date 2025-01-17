// TODO: Update to match your plugin's package name.
package org.godotengine.plugin.android.godot_exoplayer

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot

class GodotAndroidPlugin(godot: Godot): GodotPlugin(godot) {

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    private var exoPlayer: ExoPlayer? = null

    /**
     * Example showing how to declare a method that's used by Godot.
     *
     * Shows a 'Hello World' toast.
     */
    @UsedByGodot
    fun helloWorld() {
        runOnUiThread {
            Toast.makeText(activity, "Hello World", Toast.LENGTH_LONG).show()
            Log.v(pluginName, "Hello World")
        }
    }

    @UsedByGodot
    fun createExoPlayerSurface(videoUri: String): Any? {
        runOnUiThread {
            try {
                // 1) Release old instance
                exoPlayer?.release()

                // 2) Create new ExoPlayer
                exoPlayer = activity?.let { ExoPlayer.Builder(it).build() }

                // 3) Create SurfaceTexture / Surface
                val surfaceTexture = SurfaceTexture(0)
                val surface = Surface(surfaceTexture)

                // 4) Set it on ExoPlayer
                exoPlayer?.setVideoSurface(surface)

                // 5) Prepare & play
                val mediaItem = MediaItem.fromUri(videoUri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
                exoPlayer?.play()

                // 6) Return the Surface to Godot
                // We can't return the surface here because we are in a different thread.
                // We need to find another way to pass the surface to Godot.
                // For example, we can use a callback.
                // For this example, we will just log a message.
                Log.v(pluginName, "ExoPlayer surface created successfully")
            } catch (e: Exception) {
                Log.e(pluginName, "Error creating ExoPlayer surface: ${e.message}")
            }
        }
        return null
    }
    /**
     * Cleanup if needed (called when Godot unloads the plugin or the activity is destroyed).
     */
    override fun onMainDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onMainDestroy()
    }
}
