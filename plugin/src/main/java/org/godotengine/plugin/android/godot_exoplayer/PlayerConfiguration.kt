package org.godotengine.plugin.android.godot_exoplayer

import android.net.Uri

/** Immutable native configuration for one XR ExoPlayer session. */
internal data class PlayerConfig(
    val uri: Uri,
    val autoplay: Boolean,
    val volume: Float,
    val repeatMode: Int,
    val playbackSpeed: Float,
    val useCache: Boolean,
    val cacheMaxBytes: Long,
    val requestHeaders: Map<String, String>,
    val userAgent: String?,
    val allowCrossProtocolRedirects: Boolean,
    val pauseOnAppPause: Boolean,
    val parseProgramDateTime: Boolean,
    val debugLogging: Boolean,
    val routeAudioToGodot: Boolean,
    val bufferDurations: BufferDurations?,
    val observedUrlPattern: ObservedUrlPattern?,
    val drm: DrmConfig?
)

internal data class BufferDurations(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
) {
    internal companion object {
        fun fromValues(minBufferMs: Int, maxBufferMs: Int, bufferForPlaybackMs: Int, bufferForPlaybackAfterRebufferMs: Int): BufferDurations {
            require(minBufferMs > 0 && maxBufferMs >= minBufferMs) { "Buffer min/max durations must be positive and ordered" }
            require(bufferForPlaybackMs in 1..minBufferMs) { "Buffer-for-playback must be positive and no larger than min buffer" }
            require(bufferForPlaybackAfterRebufferMs in 1..minBufferMs) { "Buffer-after-rebuffer must be positive and no larger than min buffer" }
            return BufferDurations(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
        }
    }
}

/** Optional DRM configuration; a null value always means clear playback. */
internal data class DrmConfig(
    val scheme: String,
    val licenseUrl: String,
    val requestHeaders: Map<String, String>
)
