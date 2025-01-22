package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

@UnstableApi
class GodotRendererFactory(private val context: Context) : RenderersFactory {
    private val audioSink = GodotAudioSink()

    override fun createRenderers(
        eventHandler: android.os.Handler,
        videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        audioRendererEventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        textRendererOutput: androidx.media3.exoplayer.text.TextOutput,
        metadataRendererOutput: androidx.media3.exoplayer.metadata.MetadataOutput
    ): Array<Renderer> {
        val renderers = ArrayList<Renderer>()

        renderers.add(
            /* TODO Add more stuff to the MediaCodecVideoRenderer constructor because I dont know
            what to put here yet except DEFAULT

             */
            MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT)
        )

        val teeProcessor = TeeAudioProcessor(audioSink)

        // Create audio sink with our processor
        val customAudioSink = DefaultAudioSink.Builder()
            .setAudioProcessors(arrayOf(teeProcessor))
            .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            .build()

        // Add audio renderer
        renderers.add(
            MediaCodecAudioRenderer(
                context,
                MediaCodecSelector.DEFAULT,
                false,
                eventHandler,
                audioRendererEventListener,
                customAudioSink
            )
        )

        return renderers.toTypedArray()
    }
}