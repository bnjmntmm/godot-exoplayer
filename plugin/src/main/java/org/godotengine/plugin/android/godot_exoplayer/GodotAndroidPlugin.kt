package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot
import java.nio.ByteBuffer

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

    //needs to be unstable or else there will be red underlines lul
    @OptIn(UnstableApi::class)
    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) {
        runOnUiThread {
            try {
                // 1) If an ExoPlayer already exists for this id, release it first
                exoPlayers[id]?.release()
//                audioExtractors[id]?.reset()



                // 3) Create a new ExoPlayer and use the audiosink
                val newExoPlayer = activity?.let { ctx ->
                    ExoPlayer.Builder(ctx)
                        .setRenderersFactory(GodotRendererFactory(ctx))
                        .build()
                }
                if (newExoPlayer == null) {
                    Log.e(pluginName, "Failed to create ExoPlayer for id $id")
                    return@runOnUiThread
                }
                // 4) Assign the provided Surface
                newExoPlayer.setVideoSurface(surface)

                // 5) Prepare the media
                val mediaItem = MediaItem.fromUri(videoUri)
                newExoPlayer.setMediaItem(mediaItem)
                newExoPlayer.prepare()

                // 6) (Optional) Start playback immediately
                //newExoPlayer.play()

                // 7) Store in the map
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
