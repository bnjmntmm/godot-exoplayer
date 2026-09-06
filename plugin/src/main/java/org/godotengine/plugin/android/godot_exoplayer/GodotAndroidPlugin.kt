package org.godotengine.plugin.android.godot_exoplayer

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Log as ExoLogger
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.util.EventLogger
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {

    override fun getPluginName() = BuildConfig.GODOT_PLUGIN_NAME

    override fun getPluginSignals() = mutableSetOf(
        SignalInfo("on_player_created", Integer::class.java),
        SignalInfo("on_player_ready", Integer::class.java, Integer::class.java),
        SignalInfo("on_video_end", Integer::class.java),
        SignalInfo("on_player_error", Integer::class.java, String::class.java),
        SignalInfo("on_player_state_changed", Integer::class.java, Integer::class.java),
        SignalInfo("on_is_playing_changed", Integer::class.java, java.lang.Boolean::class.java),
        SignalInfo("on_subtitle_cues", Integer::class.java, Array<String>::class.java),
        SignalInfo("on_media_request_observed", Integer::class.java, String::class.java),
        SignalInfo("on_player_event", Integer::class.java, String::class.java, Dictionary::class.java)
    )

    private val exoPlayers = ConcurrentHashMap<Int, ExoPlayer>()
    private val drmConfigurations = ConcurrentHashMap<Int, Dictionary>()
    private val playerConfigurations = ConcurrentHashMap<Int, PlayerConfig>()
    private val audioBuffers = ConcurrentHashMap<Int, GodotPcmBuffer>()
    private val playerSurfaces = ConcurrentHashMap<Int, Surface>()

    private var downloadDirectory: File? = null
    private var downloadCache: Cache? = null
    private var databaseProvider: DatabaseProvider? = null
    private var cacheMaxBytes: Long = DEFAULT_CACHE_MAX_BYTES
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val resumeAfterAppPause = mutableSetOf<Int>()
    private val metrics = ConcurrentHashMap<Int, PlaybackMetrics>()
    private val itemConfigurations = mutableMapOf<Int, MutableMap<String, PlayerConfig>>()
    private val retryPolicies = mutableMapOf<Int, RetryPolicy>()
    private val retryAttempts = mutableMapOf<Int, Int>()
    private val retryCallbacks = mutableMapOf<Int, Runnable>()
    private val lastErrors = mutableMapOf<Int, Dictionary>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appPaused = false
    private var cacheMaintenance = false
    private var destroyed = false

    // --- ExoPlayer Management ---

    @UsedByGodot
    fun createExoPlayer(id: Int, surface: Surface, config: Dictionary) = runOnUiThread {
        try {
            require(surface.isValid) { "OpenXR composition-layer surface is not valid" }
            val playerConfig = buildPlayerConfig(id, config)
            check(!cacheMaintenance) { "Cache maintenance is in progress" }
            cancelRetry(id)
            metrics[id] = PlaybackMetrics { featureEvent(id, "selected_tracks_changed") }
            itemConfigurations[id] = mutableMapOf(playerConfig.mediaId to playerConfig)
            val context = activity as Context
            val dataSourceFactory = buildDataSourceFactory(context, id, playerConfig)

            exoPlayers.remove(id)?.release()
            playerSurfaces[id] = surface

            val player = buildPlayer(context, id, playerConfig, dataSourceFactory)

            exoPlayers[id] = player
            playerConfigurations[id] = playerConfig
            configurePlayer(id, player, playerConfig, surface)
            getDictionary(config, "retryPolicy")?.let { setRetryPolicyInternal(id, it) }
            getDictionary(config, "trackPreferences")?.let { applyTrackPreferences(player, it) }
            player.prepare()
            updateGlobalLogLevel()
            emitSignal("on_player_created", id)

            if (playerConfig.autoplay) play(id)

            Log.v(pluginName, "ExoPlayer($id) created with media: ${MediaUrlLog.redact(playerConfig.uri.toString())}")
        } catch (e: Exception) {
            releaseExoPlayer(id)
            emitAndLogError(id, "Error creating ExoPlayer($id): ${e.message}")
        }
    }

    @UsedByGodot
    fun createExoPlayerSurface(id: Int, videoUri: String, surface: Surface) {
        createExoPlayer(id, surface, Dictionary().apply {
            put("uri", videoUri)
        })
    }

    @UsedByGodot
    fun releaseExoPlayer(id: Int) = runOnUiThread {
        cancelRetry(id)
        resumeAfterAppPause.remove(id)
        metrics.remove(id)
        itemConfigurations.remove(id)
        retryPolicies.remove(id)
        retryAttempts.remove(id)
        lastErrors.remove(id)
        exoPlayers.remove(id)?.release()
        playerConfigurations.remove(id)
        drmConfigurations.remove(id)
        audioBuffers.remove(id)
        playerSurfaces.remove(id)
        updateGlobalLogLevel()
    }

    @UsedByGodot
    fun getProgramDateTime(id: Int): String = runAndWaitUI {
        if (playerConfigurations[id]?.parseProgramDateTime != true) return@runAndWaitUI ""
        val start = getLiveState(id)["window_start_unix_ms"] as? Long ?: return@runAndWaitUI ""
        if (start < 0) "" else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(start))
    }

    // --- Playback Controls ---

    @UsedByGodot
    fun play(id: Int) = runOnUiThread {
        exoPlayers[id]?.let {
            cancelRetry(id)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (appPaused && playerConfigurations[id]?.pauseOnAppPause == true) resumeAfterAppPause.add(id)
            else it.play()
        } ?: logNotFound(id, "play")
    }

    @UsedByGodot
    fun pause(id: Int) = runOnUiThread {
        cancelRetry(id)
        resumeAfterAppPause.remove(id)
        exoPlayers[id]?.pause() ?: logNotFound(id, "pause")
    }

    @UsedByGodot
    fun stop(id: Int) = runOnUiThread {
        cancelRetry(id)
        resumeAfterAppPause.remove(id)
        audioBuffers[id]?.clear()
        exoPlayers[id]?.let { it.pause(); it.stop() } ?: logNotFound(id, "stop")
    }

    @UsedByGodot
    fun seekTo(id: Int, positionMs: Long) = runOnUiThread {
        audioBuffers[id]?.clear()
        exoPlayers[id]?.seekTo(positionMs) ?: logNotFound(id, "seek")
    }

    @UsedByGodot
    fun seekBy(id: Int, deltaMs: Long) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val newPos = if (player.duration != C.TIME_UNSET) {
                (player.currentPosition + deltaMs).coerceIn(0, player.duration)
            } else {
                (player.currentPosition + deltaMs).coerceAtLeast(0)
            }
            audioBuffers[id]?.clear()
            player.seekTo(newPos)
        } ?: logNotFound(id, "seekBy")
    }

    @UsedByGodot
    fun setRepeatMode(id: Int, mode: Int) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            player.repeatMode = mode
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(repeatMode = mode) }
            Log.v(pluginName, "ExoPlayer($id) set repeat mode to $mode")
        } ?: logNotFound(id, "setRepeatMode")
    }

    @UsedByGodot
    fun setPlaybackSpeed(id: Int, speed: Float) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val safeSpeed = speed.coerceAtLeast(0.1f)
            player.playbackParameters = PlaybackParameters(safeSpeed)
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(playbackSpeed = safeSpeed) }
            Log.v(pluginName, "ExoPlayer($id) set playback speed to $safeSpeed")
        } ?: logNotFound(id, "setPlaybackSpeed")
    }

    @UsedByGodot
    fun setMedia(id: Int, config: Dictionary) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setMedia")
        try {
            val previousConfig = playerConfigurations[id]
                ?: throw IllegalStateException("Missing saved configuration for ExoPlayer($id)")
            val playerConfig = buildPlayerConfig(id, config, previousConfig)
            cancelRetry(id)
            retryAttempts[id] = 0
            val shouldPlayWhenReady = playerConfig.autoplay || player.playWhenReady
            if (playerConfig.routeAudioToGodot != previousConfig.routeAudioToGodot) {
                throw IllegalArgumentException("routeAudioToGodot cannot be changed with setMedia; recreate the player")
            }
            val context = activity as Context
            val dataSourceFactory = buildDataSourceFactory(context, id, playerConfig)

            audioBuffers[id]?.clear()
            val activePlayer = if (playerConfig.bufferDurations != previousConfig.bufferDurations) {
                val surface = playerSurfaces[id]
                    ?: throw IllegalStateException("Cannot change bufferDurations after the video surface was cleared")
                val replacement = buildPlayer(context, id, playerConfig, dataSourceFactory)
                exoPlayers[id] = replacement
                configurePlayer(id, replacement, playerConfig, surface)
                replacement.trackSelectionParameters = player.trackSelectionParameters
                player.release()
                replacement.prepare()
                Log.v(pluginName, "ExoPlayer($id) rebuilt to apply updated buffer durations")
                replacement
            } else {
                player.setMediaSource(
                    buildMediaSourceFactory(context, dataSourceFactory)
                        .createMediaSource(buildMediaItem(playerConfig))
                )
                player.prepare()
                player
            }
            playerConfigurations[id] = playerConfig
            itemConfigurations[id] = mutableMapOf(playerConfig.mediaId to playerConfig)
            getDictionary(config, "retryPolicy")?.let { setRetryPolicyInternal(id, it) }
            getDictionary(config, "trackPreferences")?.let { applyTrackPreferences(activePlayer, it) }
            updateGlobalLogLevel()
            if (shouldPlayWhenReady) {
                play(id)
            }

            Log.v(pluginName, "ExoPlayer($id) media changed to: ${MediaUrlLog.redact(playerConfig.uri.toString())}")
        } catch (e: Exception) {
            emitAndLogError(id, "Error changing ExoPlayer($id) media: ${e.message}")
        }
    }

    @UsedByGodot
    fun changeVideoUrl(id: Int, videoUri: String) {
        setMedia(id, Dictionary().apply {
            put("uri", videoUri)
        })
    }

    // --- Volume ---

    @UsedByGodot
    fun setVolume(id: Int, volume: Float) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            val safeVolume = volume.coerceIn(0f, 1f)
            player.volume = if (playerConfigurations[id]?.routeAudioToGodot == true) 1.0f else safeVolume
            playerConfigurations[id]?.let { playerConfigurations[id] = it.copy(volume = safeVolume) }
        } ?: logNotFound(id, "setVolume")
    }

    @UsedByGodot
    fun getVolume(id: Int): Float = runAndWaitUI { playerConfigurations[id]?.volume ?: exoPlayers[id]?.volume ?: -1f }

    @UsedByGodot
    fun pollAudioFrames(id: Int, maxFrames: Int): FloatArray {
        return audioBuffers[id]?.poll(maxFrames) ?: FloatArray(0)
    }

    @UsedByGodot
    fun getAudioFormat(id: Int): Dictionary {
        return audioBuffers[id]?.formatDictionary() ?: Dictionary().apply {
            put("sampleRate", 0)
            put("channelCount", 0)
            put("encoding", C.ENCODING_INVALID)
            put("queuedFrames", 0)
            put("maxQueuedFrames", 0)
        }
    }

    @UsedByGodot
    fun clearAudioBuffer(id: Int) {
        audioBuffers[id]?.clear()
    }

    @UsedByGodot
    fun setSurface(id: Int, surface: Surface) = runOnUiThread {
        if (!surface.isValid) {
            emitAndLogError(id, "Cannot attach an invalid OpenXR composition-layer surface")
            return@runOnUiThread
        }
        playerSurfaces[id] = surface
        exoPlayers[id]?.setVideoSurface(surface) ?: logNotFound(id, "setSurface")
    }

    @UsedByGodot
    fun clearSurface(id: Int) = runOnUiThread {
        playerSurfaces.remove(id)
        exoPlayers[id]?.clearVideoSurface() ?: logNotFound(id, "clearSurface")
    }

    @UsedByGodot
    fun isPlaying(id: Int): Boolean = runAndWaitUI { exoPlayers[id]?.isPlaying ?: false }

    @UsedByGodot
    fun getPlaybackState(id: Int): Int = runAndWaitUI {
        exoPlayers[id]?.playbackState ?: Player.STATE_IDLE
    }

    @UsedByGodot
    fun getBufferedPosition(id: Int): Long = runAndWaitUI {
        exoPlayers[id]?.bufferedPosition ?: -1L
    }

    // --- Tracks & Resolutions ---

    @UsedByGodot
    fun getResolutions(id: Int): Array<String> = runAndWaitUI {
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO }
            ?.flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    val f = group.getTrackFormat(i)
                    if (f.width > 0 && f.height > 0) {
                        "${f.width}x${f.height} - ${f.bitrate / 1000} kbps"
                    } else {
                        null
                    }
                }
            } ?: emptyList()
    }.toTypedArray()

    @UsedByGodot
    fun setResolution(id: Int, width: Int, height: Int) = runOnUiThread {
        exoPlayers[id]?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(width, height).build()
            Log.v(pluginName, "ExoPlayer($id) set max size ${width}x$height")
        } ?: logNotFound(id, "setResolution")
    }

    @UsedByGodot
    fun getVideoTracks(id: Int): Array<Dictionary> = runAndWaitUI {
        buildTrackList(id, C.TRACK_TYPE_VIDEO) { format, index, selected, supported ->
            Dictionary().apply {
                put("index", index)
                put("width", format.width)
                put("height", format.height)
                put("bitrate", format.bitrate)
                put("frameRate", format.frameRate)
                put("mimeType", format.sampleMimeType ?: "")
                put("codecs", format.codecs ?: "")
                put("selected", selected)
                put("supported", supported)
            }
        }
    }.toTypedArray()

    @UsedByGodot
    fun setVideoTrack(id: Int, videoTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setVideoTrack")
        val builder = player.trackSelectionParameters.buildUpon()
        if (videoTrackIndex == -1) {
            player.trackSelectionParameters = builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            return@runOnUiThread
        }
        val selection = findTrackSelection(player, C.TRACK_TYPE_VIDEO, videoTrackIndex) ?: return@runOnUiThread
        val group = player.currentTracks.groups[selection.first]
        player.trackSelectionParameters = builder
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, selection.second))
            .build()
    }

    @UsedByGodot
    fun getAudioTracks(id: Int): Array<Dictionary> = runAndWaitUI {
        buildTrackList(id, C.TRACK_TYPE_AUDIO) { format, index, selected, supported ->
            Dictionary().apply {
                put("index", index)
                put("language", format.language ?: "und")
                put("channels", format.channelCount)
                put("sampleRate", format.sampleRate)
                put("label", format.label ?: "")
                put("bitrate", format.bitrate)
                put("mimeType", format.sampleMimeType ?: "")
                put("selected", selected)
                put("supported", supported)
            }
        }
    }.toTypedArray()

    @UsedByGodot
    fun setAudioTrack(id: Int, audioTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setAudioTrack")
        val selection = findTrackSelection(player, C.TRACK_TYPE_AUDIO, audioTrackIndex) ?: return@runOnUiThread
        val group = player.currentTracks.groups[selection.first]
        audioBuffers[id]?.clear()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, selection.second))
            .build()
        Log.v(pluginName, "ExoPlayer($id) set audio track $audioTrackIndex")
    }

    @UsedByGodot
    fun getTextTracks(id: Int): Array<Dictionary> = runAndWaitUI {
        buildTrackList(id, C.TRACK_TYPE_TEXT) { format, index, selected, supported ->
            Dictionary().apply {
                put("index", index)
                put("language", format.language ?: "und")
                put("label", format.label ?: "")
                put("mimeType", format.sampleMimeType ?: "")
                put("selected", selected)
                put("supported", supported)
            }
        }
    }.toTypedArray()

    @UsedByGodot
    fun setTextTrack(id: Int, textTrackIndex: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "setTextTrack")
        if (textTrackIndex == -1) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            Log.v(pluginName, "ExoPlayer($id) disabled text tracks")
            return@runOnUiThread
        }

        val selection = findTrackSelection(player, C.TRACK_TYPE_TEXT, textTrackIndex) ?: return@runOnUiThread
        val group = player.currentTracks.groups[selection.first]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, selection.second))
            .build()
        Log.v(pluginName, "ExoPlayer($id) set text track $textTrackIndex")
    }

    // --- Position & Duration ---

    @UsedByGodot
    fun getCurrentPosition(id: Int): Long = runCatching {
        runAndWaitUI { exoPlayers[id]?.currentPosition ?: -1L }
    }.getOrElse { -1L }

    @UsedByGodot
    fun getDuration(id: Int): Float = runAndWaitUI {
        exoPlayers[id]?.let { player ->
            if (player.playbackState == Player.STATE_READY && player.duration != C.TIME_UNSET) {
                player.duration.toFloat()
            } else {
                -1f
            }
        } ?: -1f
    }

    // --- DRM Setup ---

    @UsedByGodot
    fun setupWidevine(id: Int, data: Dictionary) {
        drmConfigurations[id] = data
    }

    // --- Plugin Cleanup ---

    override fun onMainDestroy() {
        runOnUiThread {
            destroyed = true
            mainHandler.removeCallbacksAndMessages(null)
            retryCallbacks.clear()
            metrics.clear()
            itemConfigurations.clear()
            retryPolicies.clear()
            retryAttempts.clear()
            lastErrors.clear()
            resumeAfterAppPause.clear()
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
            playerConfigurations.clear()
            audioBuffers.clear()
            playerSurfaces.clear()
            val cache = downloadCache
            downloadCache = null
            databaseProvider = null
            // Serialize cache release after any already queued maintenance operation.
            ioExecutor.execute { cache?.release() }
            ioExecutor.shutdown()
        }
        super.onMainDestroy()
    }

    override fun onMainPause() {
        runOnUiThread {
            appPaused = true
            resumeAfterAppPause.clear()
            exoPlayers.forEach { (id, player) ->
                if (playerConfigurations[id]?.pauseOnAppPause == true && player.playWhenReady) {
                    cancelRetry(id)
                    resumeAfterAppPause.add(id)
                    player.pause()
                }
            }
        }
        super.onMainPause()
    }

    override fun onMainResume() {
        super.onMainResume()
        runOnUiThread {
            appPaused = false
            resumeAfterAppPause.toList().forEach { id ->
                val player = exoPlayers[id]
                if (player?.playerError == null) play(id)
                else {
                    player.playWhenReady = true
                    val error = lastErrors[id]
                    if (error?.get("retryable") == true) scheduleRetry(id)
                }
            }
            resumeAfterAppPause.clear()
        }
    }

    // --- Helpers ---

    private fun configurePlayer(id: Int, player: ExoPlayer, config: PlayerConfig, surface: Surface) {
        player.setMediaItem(buildMediaItem(config))
        player.setVideoSurface(surface)
        player.volume = if (config.routeAudioToGodot) 1.0f else config.volume
        player.repeatMode = config.repeatMode
        player.playbackParameters = PlaybackParameters(config.playbackSpeed)
        player.addListener(createPlayerListener(id, player))
        metrics[id]?.let { player.addAnalyticsListener(it) }
        if (config.debugLogging) {
            player.addAnalyticsListener(EventLogger())
        }
    }

    private fun buildPlayer(
        context: Context,
        id: Int,
        config: PlayerConfig,
        dataSourceFactory: DataSource.Factory
    ): ExoPlayer {
        val playerBuilder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(buildMediaSourceFactory(context, dataSourceFactory))
        config.bufferDurations?.let { durations ->
            playerBuilder.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                durations.minBufferMs, durations.maxBufferMs,
                durations.bufferForPlaybackMs, durations.bufferForPlaybackAfterRebufferMs
            ).build())
        }
        if (config.routeAudioToGodot) {
            playerBuilder.setRenderersFactory(
                GodotAudioRenderersFactory(context, audioBuffers.getOrPut(id) { GodotPcmBuffer() })
            )
        } else {
            audioBuffers.remove(id)
        }
        return playerBuilder.build()
    }

    private fun buildPlayerConfig(id: Int, data: Dictionary, defaults: PlayerConfig? = null): PlayerConfig {
        val uriString = getString(data, "uri") ?: defaults?.uri?.toString()
            ?: throw IllegalArgumentException("Missing required config key: uri")
        require(uriString.isNotBlank()) { "Media URI must not be blank" }

        return PlayerConfig(
            uri = Uri.parse(uriString),
            autoplay = getBoolean(data, "autoplay", defaults?.autoplay ?: false),
            volume = getFloat(data, "volume", defaults?.volume ?: 1f).coerceIn(0f, 1f),
            repeatMode = getInt(data, "repeatMode", defaults?.repeatMode ?: Player.REPEAT_MODE_OFF),
            playbackSpeed = getFloat(data, "playbackSpeed", defaults?.playbackSpeed ?: 1f).coerceAtLeast(0.1f),
            useCache = getBoolean(data, "useCache", defaults?.useCache ?: false),
            cacheMaxBytes = getLong(data, "cacheMaxBytes", defaults?.cacheMaxBytes ?: DEFAULT_CACHE_MAX_BYTES)
                .coerceAtLeast(MIN_CACHE_MAX_BYTES),
            requestHeaders = if (data.containsKey("requestHeaders")) {
                getStringMap(getDictionary(data, "requestHeaders"))
            } else {
                defaults?.requestHeaders ?: emptyMap()
            },
            userAgent = getString(data, "userAgent") ?: defaults?.userAgent,
            allowCrossProtocolRedirects = getBoolean(
                data,
                "allowCrossProtocolRedirects",
                defaults?.allowCrossProtocolRedirects ?: false
            ),
            pauseOnAppPause = getBoolean(data, "pauseOnAppPause", defaults?.pauseOnAppPause ?: true),
            parseProgramDateTime = getBoolean(data, "parseProgramDateTime", defaults?.parseProgramDateTime ?: false),
            debugLogging = getBoolean(data, "debugLogging", defaults?.debugLogging ?: false),
            routeAudioToGodot = getBoolean(data, "routeAudioToGodot", defaults?.routeAudioToGodot ?: false),
            bufferDurations = if (data.containsKey("bufferDurations")) {
                val bufferData = getDictionary(data, "bufferDurations")
                    ?: throw IllegalArgumentException("bufferDurations must be a dictionary")
                BufferDurations.fromValues(
                    getInt(bufferData, "minBufferMs", -1), getInt(bufferData, "maxBufferMs", -1),
                    getInt(bufferData, "bufferForPlaybackMs", -1), getInt(bufferData, "bufferForPlaybackAfterRebufferMs", -1)
                )
            } else defaults?.bufferDurations,
            observedUrlPattern = if (data.containsKey("observedUrlPattern")) {
                val pattern = getString(data, "observedUrlPattern")
                    ?: throw IllegalArgumentException("observedUrlPattern must be a string")
                ObservedUrlPattern.from(pattern)
            } else defaults?.observedUrlPattern,
            mediaId = getString(data, "mediaId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            liveTargetOffsetMs = getLong(data, "liveTargetOffsetMs", defaults?.liveTargetOffsetMs ?: -1).also {
                require(it >= -1) { "liveTargetOffsetMs must be -1 or nonnegative" }
            },
            subtitles = if (data.containsKey("subtitles")) parseSubtitles(data["subtitles"]) else defaults?.subtitles ?: emptyList(),
            drm = when {
                getBoolean(data, "clearDrm", false) -> null
                getDictionary(data, "drm") != null -> buildDrmConfig(getDictionary(data, "drm")!!)
                else -> legacyDrmConfig(id) ?: defaults?.drm
            }
        )
    }

    private fun buildMediaItem(config: PlayerConfig): MediaItem {
        val builder = MediaItem.Builder().setUri(config.uri).setMediaId(config.mediaId)
            .setSubtitleConfigurations(config.subtitles)
        if (config.liveTargetOffsetMs >= 0) builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(config.liveTargetOffsetMs).build())
        config.drm?.let { builder.setDrmConfiguration(buildDrmConfiguration(it)) }
        return builder.build()
    }

    private fun buildDrmConfiguration(config: DrmConfig): MediaItem.DrmConfiguration {
        val uuid = getDrmUuid(config.scheme)
            ?: throw IllegalArgumentException("Unsupported DRM scheme: ${config.scheme}")
        return MediaItem.DrmConfiguration.Builder(uuid)
            .setLicenseUri(config.licenseUrl)
            .setLicenseRequestHeaders(config.requestHeaders)
            .build()
    }

    private fun buildDrmConfig(data: Dictionary): DrmConfig {
        val licenseUrl = getString(data, "licenseUrl")
            ?: throw IllegalArgumentException("Missing required DRM config key: licenseUrl")
        return DrmConfig(
            scheme = getString(data, "scheme") ?: "widevine",
            licenseUrl = licenseUrl,
            requestHeaders = getStringMap(getDictionary(data, "requestHeaders"))
        )
    }

    private fun legacyDrmConfig(id: Int): DrmConfig? {
        val data = drmConfigurations[id] ?: return null
        val licenseUrl = getString(data, "licenseUrl") ?: return null
        if (licenseUrl.isEmpty()) return null
        return DrmConfig(
            scheme = getString(data, "scheme") ?: "widevine",
            licenseUrl = licenseUrl,
            requestHeaders = getStringMap(getDictionary(data, "requestHeaders"))
        )
    }

    private fun getDrmUuid(scheme: String): UUID? {
        return when (scheme.lowercase()) {
            "widevine" -> C.WIDEVINE_UUID
            "clearkey", "clear_key" -> C.CLEARKEY_UUID
            "playready" -> C.PLAYREADY_UUID
            else -> runCatching { UUID.fromString(scheme) }.getOrNull()
        }
    }

    private fun getHttpDataSourceFactory(
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        allowCrossProtocolRedirects: Boolean = false
    ): HttpDataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects)
        if (!userAgent.isNullOrBlank()) factory.setUserAgent(userAgent)
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        return factory
    }

    private fun buildReadOnlyCacheDataSource(upstreamFactory: DefaultDataSource.Factory, cache: Cache,
        id: Int, config: PlayerConfig): CacheDataSource.Factory {
        val counter = metrics[id]
        val namespace = cacheNamespace(config.uri.toString())
        val headerKey = cacheNamespace(config.requestHeaders.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" })
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory { spec -> "$namespace:$headerKey:${cacheNamespace(spec.uri.toString())}" }
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) { counter?.cacheBytes?.addAndGet(cachedBytesRead) }
                override fun onCacheIgnored(reason: Int) {}
            })
    }

    private fun getDownloadDirectory(context: Context): File {
        if (downloadDirectory == null) {
            downloadDirectory = context.getExternalFilesDirs(null)?.firstOrNull() ?: context.filesDir
        }
        return downloadDirectory!!
    }

    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (databaseProvider == null) {
            databaseProvider = StandaloneDatabaseProvider(context)
        }
        return databaseProvider!!
    }

    private fun getDownloadCache(context: Context, requestedMaxBytes: Long): Cache {
        if (downloadCache != null && cacheMaxBytes != requestedMaxBytes) {
            Log.w(pluginName, "Cache is shared by all players; keeping active limit $cacheMaxBytes bytes")
        }
        if (downloadCache == null) {
            cacheMaxBytes = requestedMaxBytes
            val downloadContentDirectory = File(getDownloadDirectory(context), "downloads")
            downloadCache = SimpleCache(
                downloadContentDirectory,
                LeastRecentlyUsedCacheEvictor(cacheMaxBytes),
                getDatabaseProvider(context)
            )
        }
        return downloadCache!!
    }

    private fun buildDataSourceFactory(context: Context, id: Int, config: PlayerConfig): DataSource.Factory {
        check(!cacheMaintenance) { "Cache maintenance is in progress" }
        val counter = metrics[id]
        val upstreamFactory = DefaultDataSource.Factory(
            context,
            getHttpDataSourceFactory(
                config.requestHeaders,
                config.userAgent,
                config.allowCrossProtocolRedirects
            )
        ).setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) counter?.networkBytes?.addAndGet(bytesTransferred.toLong())
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        })
        val factory: DataSource.Factory = if (config.useCache) {
            buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(context, config.cacheMaxBytes), id, config)
        } else {
            upstreamFactory
        }
        val pattern = config.observedUrlPattern ?: return factory
        return ObservingDataSourceFactory(factory) { url ->
            if (pattern.matches(url)) {
                runOnUiThread { emitSignal("on_media_request_observed", id, url) }
            }
        }
    }

    private class ObservingDataSourceFactory(
        private val upstream: DataSource.Factory,
        private val onOpen: (String) -> Unit
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ObservingDataSource(upstream.createDataSource(), onOpen)
    }

    private class ObservingDataSource(
        private val upstream: DataSource,
        private val onOpen: (String) -> Unit
    ) : DataSource {
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            onOpen(dataSpec.uri.toString())
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
        override fun getUri(): Uri? = upstream.uri
        override fun close() = upstream.close()
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    }

    private fun buildMediaSourceFactory(context: Context, dataSourceFactory: DataSource.Factory): MediaSource.Factory {
        val drmSessionManagerProvider = DefaultDrmSessionManagerProvider()
        drmSessionManagerProvider.setDrmHttpDataSourceFactory(getHttpDataSourceFactory())
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    private fun createPlayerListener(id: Int, owner: ExoPlayer) = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (exoPlayers[id] !== owner) return
            featureEvent(id, "tracks_changed")
            featureEvent(id, "selected_tracks_changed")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (exoPlayers[id] !== owner) return
            cancelRetry(id)
            retryAttempts[id] = 0
            mediaItem?.let { itemConfigurations[id]?.get(it.mediaId)?.let { config -> playerConfigurations[id] = config } }
            audioBuffers[id]?.clear()
            featureEvent(id, "media_item_transition", Dictionary().apply {
                put("media_id", mediaItem?.mediaId ?: "")
                put("index", exoPlayers[id]?.currentMediaItemIndex ?: -1)
                put("reason", reason)
            })
            emitSignal("on_subtitle_cues", id, emptyArray<String>())
            featureEvent(id, "subtitle_cues_detailed", Dictionary().apply { put("cues", emptyArray<Dictionary>()) })
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (exoPlayers[id] !== owner) return
            featureEvent(id, "live_state_changed", getLiveState(id))
            featureEvent(id, "playlist_changed")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (exoPlayers[id] !== owner) return
            emitSignal("on_is_playing_changed", id, isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (exoPlayers[id] !== owner) return
            when (playbackState) {
                Player.STATE_READY -> {
                    exoPlayers[id]?.let { player ->
                        val duration = if (player.duration == C.TIME_UNSET) -1 else player.duration
                        Log.v(pluginName, "ExoPlayer($id) ready, duration: $duration")
                        if (playerConfigurations[id]?.debugLogging == true) {
                            logTrackDebugInfo(id, player)
                        }
                        emitSignal("on_player_ready", id, duration.toInt())
                        emitSignal("on_player_state_changed", id, Player.STATE_READY)
                    }
                }
                Player.STATE_ENDED -> {
                    emitSignal("on_video_end", id)
                    emitSignal("on_player_state_changed", id, Player.STATE_ENDED)
                }
                Player.STATE_BUFFERING -> {
                    Log.v(pluginName, "ExoPlayer($id) buffering")
                    emitSignal("on_player_state_changed", id, Player.STATE_BUFFERING)
                }
                Player.STATE_IDLE -> {
                    Log.v(pluginName, "ExoPlayer($id) idle")
                    emitSignal("on_player_state_changed", id, Player.STATE_IDLE)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (exoPlayers[id] !== owner) return
            val details = error.message?.let { "${error.errorCodeName}: $it" } ?: error.errorCodeName
            emitSignal("on_player_error", id, details)
            val httpStatus = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode ?: 0
            val structured = Dictionary().apply {
                put("code", error.errorCode)
                put("code_name", error.errorCodeName)
                put("message", error.errorCodeName) // Error messages can contain signed URLs; keep structured events safe.
                put("http_status", httpStatus)
                put("retryable", RetryPolicy.isTransient(error.errorCode, httpStatus))
                put("media_id", exoPlayers[id]?.currentMediaItem?.mediaId ?: "")
            }
            lastErrors[id] = structured
            featureEvent(id, "playback_error", structured)
            if (httpStatus == 401 || httpStatus == 403) featureEvent(id, "authentication_required", structured)
            else if (RetryPolicy.isTransient(error.errorCode, httpStatus)) scheduleRetry(id)
        }

        override fun onCues(cueGroup: CueGroup) {
            if (exoPlayers[id] !== owner) return
            val cues = cueGroup.cues.mapNotNull { it.text?.toString() }.toTypedArray()
            emitSignal("on_subtitle_cues", id, cues)
            featureEvent(id, "subtitle_cues_detailed", Dictionary().apply {
                put("cues", cueGroup.cues.map { cue -> Dictionary().apply {
                    put("text", cue.text?.toString() ?: "")
                    put("bitmap", cue.bitmap != null)
                    put("alignment", cue.textAlignment?.name ?: "ALIGN_CENTER")
                    put("position", if (cue.position == androidx.media3.common.text.Cue.DIMEN_UNSET) -1f else cue.position)
                    put("position_anchor", cue.positionAnchor)
                    put("line", if (cue.line == androidx.media3.common.text.Cue.DIMEN_UNSET) -1f else cue.line)
                    put("line_set", cue.line != androidx.media3.common.text.Cue.DIMEN_UNSET)
                    put("line_type", cue.lineType)
                    put("line_anchor", cue.lineAnchor)
                    put("size", if (cue.size == androidx.media3.common.text.Cue.DIMEN_UNSET) -1f else cue.size)
                } }.toTypedArray())
            })
        }
    }

    // Extended API. All player and policy mutations run on the player's Android main looper.
    private fun featureEvent(id: Int, name: String, data: Dictionary = Dictionary()) =
        emitSignal("on_player_event", id, name, data)

    private fun knownTime(value: Long) = if (value == C.TIME_UNSET) -1L else value

    @UsedByGodot
    fun getLiveState(id: Int): Dictionary = runAndWaitUI {
        val player = exoPlayers[id] ?: return@runAndWaitUI Dictionary()
        val window = if (player.currentTimeline.isEmpty) null else
            player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        Dictionary().apply {
            put("is_live", player.isCurrentMediaItemLive)
            put("is_dynamic", player.isCurrentMediaItemDynamic)
            put("is_seekable", player.isCurrentMediaItemSeekable)
            put("live_offset_ms", knownTime(player.currentLiveOffset))
            put("window_duration_ms", knownTime(player.duration))
            put("position_ms", player.currentPosition)
            put("default_position_ms", window?.let { knownTime(it.defaultPositionMs) } ?: -1L)
            put("window_start_unix_ms", window?.let { knownTime(it.windowStartTimeMs) } ?: -1L)
            put("playback_unix_ms", window?.takeIf { it.windowStartTimeMs != C.TIME_UNSET }
                ?.let { it.windowStartTimeMs + player.currentPosition } ?: -1L)
            put("target_offset_ms", window?.liveConfiguration?.let { knownTime(it.targetOffsetMs) } ?: -1L)
        }
    }

    @UsedByGodot
    fun goLive(id: Int) = runOnUiThread {
        exoPlayers[id]?.let { if (it.isCurrentMediaItemLive) { audioBuffers[id]?.clear(); it.seekToDefaultPosition() } }
    }

    @UsedByGodot
    fun getPlaybackStats(id: Int): Dictionary = runAndWaitUI {
        val player = exoPlayers[id] ?: return@runAndWaitUI Dictionary()
        (metrics[id]?.snapshot() ?: Dictionary()).apply {
            put("position_ms", player.currentPosition)
            put("duration_ms", knownTime(player.duration))
            put("buffered_position_ms", player.bufferedPosition)
            put("buffered_duration_ms", player.totalBufferedDuration)
            put("state", player.playbackState)
            put("is_playing", player.isPlaying)
            put("play_when_ready", player.playWhenReady)
            put("width", player.videoFormat?.width ?: -1)
            put("height", player.videoFormat?.height ?: -1)
            put("bitrate_bps", player.videoFormat?.bitrate ?: -1)
            put("frame_rate", player.videoFormat?.frameRate ?: -1f)
            put("media_id", player.currentMediaItem?.mediaId ?: "")
            put("playlist_index", player.currentMediaItemIndex)
            put("retry_attempts", retryAttempts[id] ?: 0)
            put("audio_bridge", getAudioFormat(id))
            put("live", getLiveState(id))
        }
    }

    private fun parseSubtitles(value: Any?): List<MediaItem.SubtitleConfiguration> {
        val entries = when (value) { is Array<*> -> value.toList(); is List<*> -> value; else -> error("subtitles must be an array") }
        return entries.map {
            val entry = it as? Dictionary ?: error("Each subtitle must be a dictionary")
            val uri = getString(entry, "uri")?.takeIf(String::isNotBlank) ?: error("Subtitle uri is required")
            val mime = getString(entry, "mimeType")?.takeIf(String::isNotBlank) ?: error("Subtitle mimeType is required")
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setMimeType(mime)
                .setLanguage(getString(entry, "language")).setLabel(getString(entry, "label"))
                .setSelectionFlags(if (getBoolean(entry, "default", false)) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
    }

    @UsedByGodot
    fun setTrackPreferences(id: Int, data: Dictionary) = runOnUiThread {
        runCatching { exoPlayers[id]?.let { applyTrackPreferences(it, data) } }
            .onFailure { emitAndLogError(id, it.message ?: "Invalid track preferences") }
    }

    private fun applyTrackPreferences(player: ExoPlayer, data: Dictionary) {
        val builder = player.trackSelectionParameters.buildUpon()
        fun limit(key: String): Int = getInt(data, key, -1).also { require(it == -1 || it > 0) { "$key must be -1 or positive" } }
            .let { if (it == -1) Int.MAX_VALUE else it }
        if (data.containsKey("maxBitrate")) builder.setMaxVideoBitrate(limit("maxBitrate"))
        if (data.containsKey("maxWidth") || data.containsKey("maxHeight")) builder.setMaxVideoSize(
            if (data.containsKey("maxWidth")) limit("maxWidth") else player.trackSelectionParameters.maxVideoWidth,
            if (data.containsKey("maxHeight")) limit("maxHeight") else player.trackSelectionParameters.maxVideoHeight)
        if (data.containsKey("audioLanguage")) builder.setPreferredAudioLanguage(getString(data, "audioLanguage")?.takeIf(String::isNotBlank))
        if (data.containsKey("textLanguage")) builder.setPreferredTextLanguage(getString(data, "textLanguage")?.takeIf(String::isNotBlank))
        if (data.containsKey("subtitlesEnabled")) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !getBoolean(data, "subtitlesEnabled", true))
        if (getBoolean(data, "clearOverrides", true)) builder.clearOverrides()
        player.trackSelectionParameters = builder.build()
    }

    private fun cancelRetry(id: Int) { retryCallbacks.remove(id)?.let { mainHandler.removeCallbacks(it) } }

    private fun setRetryPolicyInternal(id: Int, data: Dictionary) {
        val policy = RetryPolicy(getInt(data, "maxAttempts", 0), getLong(data, "baseDelayMs", 1000),
            getLong(data, "maxDelayMs", 30000), getBoolean(data, "liveAtDefaultPosition", true))
        cancelRetry(id)
        retryPolicies[id] = policy
        retryAttempts[id] = 0
    }

    @UsedByGodot
    fun setRetryPolicy(id: Int, data: Dictionary) = runOnUiThread {
        runCatching { setRetryPolicyInternal(id, data) }.onFailure { emitAndLogError(id, it.message ?: "Invalid retry policy") }
    }

    private fun scheduleRetry(id: Int) {
        val player = exoPlayers[id] ?: return
        val policy = retryPolicies[id] ?: return
        if (!player.playWhenReady || (appPaused && playerConfigurations[id]?.pauseOnAppPause == true)) return
        val attempt = (retryAttempts[id] ?: 0) + 1
        if (attempt > policy.maxAttempts) {
            if (policy.maxAttempts > 0) featureEvent(id, "retry_exhausted", lastErrors[id] ?: Dictionary())
            return
        }
        cancelRetry(id)
        retryAttempts[id] = attempt
        val delay = policy.delayMs(attempt)
        val callback = Runnable {
            retryCallbacks.remove(id)
            if (exoPlayers[id] === player && player.playWhenReady) retryInternal(id, policy.liveAtDefaultPosition)
        }
        retryCallbacks[id] = callback
        mainHandler.postDelayed(callback, delay)
        featureEvent(id, "retry_scheduled", Dictionary().apply { put("attempt", attempt); put("delay_ms", delay) })
    }

    private fun retryInternal(id: Int, liveAtDefaultPosition: Boolean) {
        val player = exoPlayers[id] ?: return
        audioBuffers[id]?.clear()
        if (liveAtDefaultPosition && player.isCurrentMediaItemLive) player.seekToDefaultPosition()
        player.prepare()
        if (appPaused && playerConfigurations[id]?.pauseOnAppPause == true) resumeAfterAppPause.add(id)
        else player.play()
    }

    @UsedByGodot
    fun retry(id: Int, liveAtDefaultPosition: Boolean) = runOnUiThread {
        cancelRetry(id)
        retryAttempts[id] = 0
        retryInternal(id, liveAtDefaultPosition)
    }

    /** Rebuild just the current item's source with fresh credentials; retain the queue and VOD position. */
    @UsedByGodot
    fun refreshCredentials(id: Int, headers: Dictionary, licenseHeaders: Dictionary) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread
        val item = player.currentMediaItem ?: return@runOnUiThread
        val config = itemConfigurations[id]?.get(item.mediaId) ?: return@runOnUiThread
        runCatching {
            val updated = config.copy(requestHeaders = getStringMap(headers),
                drm = config.drm?.copy(requestHeaders = getStringMap(licenseHeaders)))
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val live = player.isCurrentMediaItemLive && (retryPolicies[id]?.liveAtDefaultPosition != false)
            val source = sourceFor(id, updated)
            cancelRetry(id)
            retryAttempts[id] = 0
            itemConfigurations[id]?.set(updated.mediaId, updated)
            playerConfigurations[id] = updated
            player.removeMediaItem(index)
            player.addMediaSource(index, source)
            player.seekTo(index, if (live) C.TIME_UNSET else position)
            retryInternal(id, live)
        }.onFailure { emitAndLogError(id, it.message ?: "Credential refresh failed") }
    }

    private fun sourceFor(id: Int, config: PlayerConfig): MediaSource = buildMediaSourceFactory(activity as Context,
        buildDataSourceFactory(activity as Context, id, config)).createMediaSource(buildMediaItem(config))

    @UsedByGodot
    fun enqueue(id: Int, data: Dictionary) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread logNotFound(id, "enqueue")
        runCatching {
            val defaults = playerConfigurations[id] ?: error("Missing player configuration")
            // Playback settings are session-wide; credentials and media settings are deliberately item-local.
            val config = buildPlayerConfig(id, data, defaults.copy(drm = null, requestHeaders = emptyMap(),
                subtitles = emptyList(), liveTargetOffsetMs = -1)).copy(drm =
                getDictionary(data, "drm")?.let { buildDrmConfig(it) })
            require(config.routeAudioToGodot == defaults.routeAudioToGodot && config.bufferDurations == defaults.bufferDurations) {
                "Playlist items cannot change audio routing or buffer durations"
            }
            require(itemConfigurations[id]?.containsKey(config.mediaId) != true) { "mediaId must be unique in a playlist" }
            val source = sourceFor(id, config)
            itemConfigurations.getOrPut(id) { mutableMapOf() }[config.mediaId] = config
            player.addMediaSource(source)
        }.onFailure { emitAndLogError(id, it.message ?: "Could not enqueue media") }
    }

    @UsedByGodot
    fun removePlaylistItem(id: Int, index: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread
        if (index !in 0 until player.mediaItemCount) return@runOnUiThread emitAndLogError(id, "Playlist index out of range")
        val mediaId = player.getMediaItemAt(index).mediaId
        cancelRetry(id)
        player.removeMediaItem(index)
        itemConfigurations[id]?.remove(mediaId)
    }

    @UsedByGodot
    fun seekPlaylistItem(id: Int, index: Int) = runOnUiThread {
        val player = exoPlayers[id] ?: return@runOnUiThread
        if (index !in 0 until player.mediaItemCount) return@runOnUiThread emitAndLogError(id, "Playlist index out of range")
        cancelRetry(id)
        audioBuffers[id]?.clear()
        player.seekToDefaultPosition(index)
    }

    @UsedByGodot
    fun next(id: Int) = runOnUiThread { cancelRetry(id); audioBuffers[id]?.clear(); exoPlayers[id]?.seekToNextMediaItem() }

    @UsedByGodot
    fun previous(id: Int) = runOnUiThread { cancelRetry(id); audioBuffers[id]?.clear(); exoPlayers[id]?.seekToPreviousMediaItem() }

    @UsedByGodot
    fun setShuffle(id: Int, enabled: Boolean) = runOnUiThread { exoPlayers[id]?.shuffleModeEnabled = enabled }

    @UsedByGodot
    fun getPlaylist(id: Int): Dictionary = runAndWaitUI {
        val player = exoPlayers[id] ?: return@runAndWaitUI Dictionary()
        Dictionary().apply {
            put("index", player.currentMediaItemIndex)
            put("media_id", player.currentMediaItem?.mediaId ?: "")
            put("shuffle", player.shuffleModeEnabled)
            put("items", (0 until player.mediaItemCount).map { index -> Dictionary().apply {
                put("index", index); put("media_id", player.getMediaItemAt(index).mediaId)
            } }.toTypedArray())
        }
    }

    private fun cacheNamespace(uri: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @UsedByGodot
    fun getCacheStats(): Dictionary = runAndWaitUI {
        // Inspection also opens an existing on-disk cache, even before the first player.
        val cache = if (destroyed) null else getDownloadCache(activity as Context, cacheMaxBytes)
        Dictionary().apply {
            put("initialized", cache != null)
            put("busy", cacheMaintenance)
            put("size_bytes", downloadCache?.cacheSpace ?: 0L)
            put("max_bytes", cacheMaxBytes)
            put("resource_count", downloadCache?.keys?.size ?: 0)
        }
    }

    /** Empty URI clears all resources. Requiring released players prevents active loader/cache races. */
    @UsedByGodot
    fun removeCachedMedia(uri: String) = runOnUiThread {
        if (exoPlayers.isNotEmpty() || cacheMaintenance || destroyed) {
            featureEvent(0, "cache_operation_completed", Dictionary().apply {
                put("success", false); put("message", "Release all players before cache maintenance")
            })
            return@runOnUiThread
        }
        val cache = runCatching { getDownloadCache(activity as Context, cacheMaxBytes) }.getOrElse {
            featureEvent(0, "cache_operation_completed", Dictionary().apply { put("success", false); put("message", "Could not open cache") })
            return@runOnUiThread
        }
        cacheMaintenance = true
        ioExecutor.execute {
            val result = runCatching {
                val prefix = if (uri.isEmpty()) "" else cacheNamespace(uri) + ":"
                cache.keys.toList().filter { it.startsWith(prefix) }.forEach { cache.removeResource(it) }
            }
            runOnUiThread {
                cacheMaintenance = false
                if (!destroyed) featureEvent(0, "cache_operation_completed", Dictionary().apply {
                    put("success", result.isSuccess)
                    put("message", if (result.isSuccess) "" else "Cache removal failed")
                })
            }
        }
    }

    private fun buildTrackList(
        id: Int,
        trackType: Int,
        itemBuilder: (androidx.media3.common.Format, Int, Boolean, Boolean) -> Dictionary
    ): ArrayList<Dictionary> {
        val tracks = ArrayList<Dictionary>()
        var index = 0
        exoPlayers[id]?.currentTracks?.groups?.filter { it.type == trackType }?.forEach { group ->
            (0 until group.length).forEach { i ->
                tracks.add(itemBuilder(group.getTrackFormat(i), index, group.isTrackSelected(i), group.isTrackSupported(i)))
                index++
            }
        }
        return tracks
    }

    private fun findTrackSelection(player: ExoPlayer, trackType: Int, requestedIndex: Int): Pair<Int, Int>? {
        var index = 0
        for ((groupIndex, group) in player.currentTracks.groups.withIndex()) {
            if (group.type != trackType) continue
            for (trackIndex in 0 until group.length) {
                if (index == requestedIndex) {
                    return Pair(groupIndex, trackIndex)
                }
                index++
            }
        }
        return null
    }

    private fun getString(data: Dictionary, key: String): String? {
        return data[key] as? String
    }

    private fun getDictionary(data: Dictionary, key: String): Dictionary? {
        return data[key] as? Dictionary
    }

    private fun getBoolean(data: Dictionary, key: String, defaultValue: Boolean): Boolean {
        return when (val value = data[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getInt(data: Dictionary, key: String, defaultValue: Int): Int {
        return when (val value = data[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getFloat(data: Dictionary, key: String, defaultValue: Float): Float {
        return when (val value = data[key]) {
            is Float -> value
            is Double -> value.toFloat()
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getLong(data: Dictionary, key: String, defaultValue: Long): Long {
        return when (val value = data[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getStringMap(data: Dictionary?): Map<String, String> {
        if (data == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in data.keys) {
            val stringKey = key as? String ?: continue
            val stringValue = data[key] as? String ?: continue
            result[stringKey] = stringValue
        }
        return result
    }

    private fun <T> runAndWaitUI(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()

        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(UI_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for Android UI thread")
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun emitAndLogError(id: Int, msg: String) {
        Log.e(pluginName, msg)
        emitSignal("on_player_error", id, msg)
    }

    private fun logNotFound(id: Int, action: String) {
        emitAndLogError(id, "ExoPlayer($id) not found when trying to $action")
    }

    private fun logTrackDebugInfo(id: Int, player: ExoPlayer) {
        val tracks = player.currentTracks
        val debugInfo = buildString {
            append("ExoPlayer($id) debug info:\n")
            append("Total track groups: ${tracks.groups.size}\n")
            for (group in tracks.groups) {
                append("Track group (type: ${group.type}, length: ${group.length}):\n")
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    append("  Track $i: mime=${format.sampleMimeType}, codecs=${format.codecs}, bitrate=${format.bitrate}bps")
                    if (format.width > 0 && format.height > 0) append(", resolution=${format.width}x${format.height}")
                    if (group.type == C.TRACK_TYPE_VIDEO) append(", frameRate=${format.frameRate}")
                    if (group.type == C.TRACK_TYPE_AUDIO) append(", channels=${format.channelCount}, sampleRate=${format.sampleRate}")
                    append("\n")
                }
            }
        }
        Log.v(pluginName, debugInfo)
    }

    private fun updateGlobalLogLevel() {
        ExoLogger.setLogLevel(
            if (playerConfigurations.values.any { it.debugLogging }) {
                ExoLogger.LOG_LEVEL_ALL
            } else {
                ExoLogger.LOG_LEVEL_WARNING
            }
        )
    }

    private companion object {
        const val DEFAULT_CACHE_MAX_BYTES = 256L * 1024L * 1024L
        const val MIN_CACHE_MAX_BYTES = 8L * 1024L * 1024L
        const val UI_QUERY_TIMEOUT_SECONDS = 5L
    }
}
