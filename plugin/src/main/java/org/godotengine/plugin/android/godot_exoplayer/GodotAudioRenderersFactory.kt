package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Captures decoded Media3 PCM while keeping a silent AudioTrack as ExoPlayer's clock. */
@UnstableApi
internal class GodotAudioRenderersFactory(
    context: Context,
    private val audioBuffer: GodotPcmBuffer
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(
            arrayOf<AudioProcessor>(
                TeeAudioProcessor(GodotAudioSink(audioBuffer)),
                SilenceAudioProcessor()
            )
        )
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}

@UnstableApi
private class GodotAudioSink(
    private val audioBuffer: GodotPcmBuffer
) : TeeAudioProcessor.AudioBufferSink {
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        Log.d("GodotAudioSink", "PCM format: $sampleRateHz Hz, $channelCount channels, encoding $encoding")
        audioBuffer.configure(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) = audioBuffer.append(buffer)
}

@UnstableApi
private class SilenceAudioProcessor : AudioProcessor {
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var silenceBytes = ByteArray(0)
    private var active = false
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        active = true
        return inputAudioFormat
    }

    override fun isActive() = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        outputBuffer = if (outputBuffer.capacity() < remaining) {
            ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
            outputBuffer
        }
        if (silenceBytes.size < remaining) silenceBytes = ByteArray(remaining)
        outputBuffer.put(silenceBytes, 0, remaining)
        outputBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded() = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        active = false
    }
}
