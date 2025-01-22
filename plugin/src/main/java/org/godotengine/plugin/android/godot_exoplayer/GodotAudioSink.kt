package org.godotengine.plugin.android.godot_exoplayer

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
@UnstableApi
class GodotAudioSink : TeeAudioProcessor.AudioBufferSink {
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        Log.d("GodotAudioSink", "Audio flush - Sample Rate: $sampleRateHz, Channels: $channelCount, Encoding: $encoding")
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        try {
            val arr = ByteArray(buffer.remaining())
            buffer.get(arr)
            // TODO: Send arr to Godot here
            Log.d("GodotAudioSink", "Received audio buffer of size: ${arr.size}, first byte: ${arr[0]}")
        } catch (exception: Exception) {
            Log.e("GodotAudioSink", "Error handling audio buffer: ${exception.message}")
        }
    }
}