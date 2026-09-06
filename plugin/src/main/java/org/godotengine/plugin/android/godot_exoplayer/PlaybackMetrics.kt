package org.godotengine.plugin.android.godot_exoplayer

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.godotengine.godot.Dictionary
import java.util.concurrent.atomic.AtomicLong

/** Counters belong to a player session, including all of its playlist items. */
@UnstableApi
internal class PlaybackMetrics(private val onSelectionChanged: () -> Unit) : AnalyticsListener {
    val networkBytes = AtomicLong()
    val cacheBytes = AtomicLong()
    var decoder = ""
    var droppedFrames = 0L
    var bandwidthEstimate = -1L
    private var bufferingSince = -1L
    private var bufferingMs = 0L
    private var bufferingCount = 0

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime,
        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) { onSelectionChanged() }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int,
        elapsedMs: Long) { this.droppedFrames += droppedFrames }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int,
        totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidthEstimate = bitrateEstimate }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (state == Player.STATE_BUFFERING && bufferingSince < 0) {
            bufferingSince = now
            bufferingCount++
        } else if (state != Player.STATE_BUFFERING && bufferingSince >= 0) {
            bufferingMs += now - bufferingSince
            bufferingSince = -1
        }
    }

    fun snapshot() = Dictionary().apply {
        put("decoder", decoder)
        put("dropped_frames", droppedFrames)
        put("bandwidth_estimate_bps", bandwidthEstimate)
        put("network_bytes", networkBytes.get())
        put("cache_bytes", cacheBytes.get())
        put("buffering_count", bufferingCount)
        put("buffering_ms", bufferingMs + if (bufferingSince < 0) 0 else SystemClock.elapsedRealtime() - bufferingSince)
    }
}
