package org.godotengine.plugin.android.godot_exoplayer

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.util.Util
import org.godotengine.godot.Dictionary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Thread-safe stereo PCM ring buffer shared by Media3's audio thread and Godot. */
internal class GodotPcmBuffer {
    private val samples = FloatArray(MAX_QUEUED_FRAMES * OUTPUT_CHANNELS)
    private var readFrame = 0
    private var writeFrame = 0
    private var queuedFrames = 0
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var droppedFrames = 0L
    private var underrunFrames = 0L

    @Synchronized
    fun configure(sampleRate: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.encoding = encoding
        droppedFrames = 0
        underrunFrames = 0
        clear()
    }

    @Synchronized
    fun append(buffer: ByteBuffer) {
        if (sampleRate <= 0 || channelCount <= 0) return
        val bytesPerSample = bytesPerSample(encoding)
        if (bytesPerSample <= 0) return

        val source = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = source.remaining() / (bytesPerSample * channelCount)
        val channelMask = Util.getAudioTrackChannelConfig(channelCount)
        for (frame in 0 until frameCount) {
            var left: Float
            var right: Float
            if (channelCount == 1) {
                left = readSample(source, encoding)
                right = left
            } else {
                left = 0f
                right = 0f
                if (channelMask == AudioFormat.CHANNEL_INVALID) {
                    // Unsupported layouts retain the legacy first-two-channel mapping and
                    // accumulate every remaining channel into both stereo outputs.
                    for (channel in 0 until channelCount) {
                        val sample = readSample(source, encoding)
                        when (channel) {
                            0 -> {
                                left = sample
                                right = sample
                            }
                            1 -> right = sample
                            else -> {
                                left += sample * DOWNMIX_GAIN
                                right += sample * DOWNMIX_GAIN
                            }
                        }
                    }
                } else {
                    // Android channel-mask bit order matches the PCM sample order in each frame.
                    var remainingMask = channelMask
                    for (channel in 0 until channelCount) {
                        val sample = readSample(source, encoding)
                        val role = Integer.lowestOneBit(remainingMask)
                        remainingMask = remainingMask and (remainingMask - 1)
                        when (role) {
                            AudioFormat.CHANNEL_OUT_FRONT_LEFT -> left += sample
                            AudioFormat.CHANNEL_OUT_FRONT_RIGHT -> right += sample
                            AudioFormat.CHANNEL_OUT_FRONT_CENTER -> {
                                left += sample * DOWNMIX_GAIN
                                right += sample * DOWNMIX_GAIN
                            }
                            AudioFormat.CHANNEL_OUT_SIDE_LEFT, AudioFormat.CHANNEL_OUT_BACK_LEFT -> left += sample * DOWNMIX_GAIN
                            AudioFormat.CHANNEL_OUT_SIDE_RIGHT, AudioFormat.CHANNEL_OUT_BACK_RIGHT -> right += sample * DOWNMIX_GAIN
                        }
                    }
                }
            }

            if (queuedFrames == MAX_QUEUED_FRAMES) {
                readFrame = (readFrame + 1) % MAX_QUEUED_FRAMES
                queuedFrames--
                droppedFrames++
            }
            val outputIndex = writeFrame * OUTPUT_CHANNELS
            samples[outputIndex] = left.coerceIn(-1f, 1f)
            samples[outputIndex + 1] = right.coerceIn(-1f, 1f)
            writeFrame = (writeFrame + 1) % MAX_QUEUED_FRAMES
            queuedFrames++
        }
    }

    @Synchronized
    fun poll(maxFrames: Int): FloatArray {
        val requestedFrames = maxFrames.coerceAtLeast(0)
        if (requestedFrames == 0) return FloatArray(0)
        if (queuedFrames == 0) {
            underrunFrames += requestedFrames
            return FloatArray(0)
        }

        val framesToRead = min(requestedFrames, queuedFrames)
        val output = FloatArray(framesToRead * OUTPUT_CHANNELS)
        for (frame in 0 until framesToRead) {
            val sourceIndex = readFrame * OUTPUT_CHANNELS
            val targetIndex = frame * OUTPUT_CHANNELS
            output[targetIndex] = samples[sourceIndex]
            output[targetIndex + 1] = samples[sourceIndex + 1]
            readFrame = (readFrame + 1) % MAX_QUEUED_FRAMES
        }
        queuedFrames -= framesToRead
        return output
    }

    @Synchronized
    fun clear() {
        readFrame = 0
        writeFrame = 0
        queuedFrames = 0
    }

    @Synchronized
    fun formatDictionary() = Dictionary().apply {
        put("sampleRate", sampleRate)
        put("channelCount", channelCount)
        put("encoding", encoding)
        put("queuedFrames", queuedFrames)
        put("maxQueuedFrames", MAX_QUEUED_FRAMES)
        put("droppedFrames", droppedFrames)
        put("underrunFrames", underrunFrames)
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_16BIT -> (buffer.short / 32768f).coerceIn(-1f, 1f)
        C.ENCODING_PCM_24BIT -> {
            val value = (buffer.get().toInt() and 0xFF) or
                ((buffer.get().toInt() and 0xFF) shl 8) or
                (buffer.get().toInt() shl 16)
            (value / 8388608f).coerceIn(-1f, 1f)
        }
        C.ENCODING_PCM_32BIT -> (buffer.int / 2147483648f).coerceIn(-1f, 1f)
        C.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
        else -> 0f
    }

    private companion object {
        const val OUTPUT_CHANNELS = 2
        const val MAX_QUEUED_FRAMES = 96_000
        private const val DOWNMIX_GAIN = 0.70710678f
    }
}
