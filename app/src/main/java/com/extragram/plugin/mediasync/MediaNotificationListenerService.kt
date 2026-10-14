package com.extragram.plugin.mediasync

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.extragram.plugin.mediasync.model.TrackInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Android NotificationListenerService that listens for active media sessions across all apps.
 *
 * Requirements:
 * - Declared in AndroidManifest.xml with BIND_NOTIFICATION_LISTENER_SERVICE permission.
 * - User must grant "Notification Access" in Android Settings.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "ExtragramMediaSync"
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks registered controllers and their callbacks
    private val controllerCallbacks = ConcurrentHashMap<MediaController, MediaController.Callback>()

    // Last reported track to prevent redundant logging on progress/seek ticks
    @Volatile
    private var lastReportedTrack: TrackInfo? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.d(TAG, "Active media sessions updated: count=${controllers?.size ?: 0}")
            updateControllers(controllers)
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected. Initializing MediaSessionManager...")

        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            componentName = ComponentName(this, MediaNotificationListenerService::class.java)

            val manager = mediaSessionManager
            val component = componentName
            if (manager != null && component != null) {
                // Register for session changes
                manager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    component,
                    mainHandler
                )

                // Query existing active sessions
                val activeControllers = manager.getActiveSessions(component)
                updateControllers(activeControllers)
            } else {
                Log.e(TAG, "Failed to obtain MediaSessionManager or ComponentName.")
            }
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "SecurityException: Notification access might not be granted. " +
                        "Please enable Notification Access in system settings.",
                e
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error in onListenerConnected", t)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "NotificationListenerService disconnected. Cleaning up...")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MediaNotificationListenerService destroyed.")
        cleanup()
    }

    /**
     * Synchronizes active MediaControllers: attaches callbacks to new controllers
     * and detaches from stale ones.
     */
    @Synchronized
    private fun updateControllers(controllers: List<MediaController>?) {
        val currentControllers = controllers ?: emptyList()
        val currentSet = currentControllers.toSet()

        // Unregister removed controllers
        val iterator = controllerCallbacks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentSet) {
                try {
                    entry.key.unregisterCallback(entry.value)
                    Log.d(TAG, "Unregistered controller for package: ${entry.key.packageName}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Error unregistering controller: ${t.message}")
                }
                iterator.remove()
            }
        }

        // Register new controllers
        for (controller in currentControllers) {
            if (!controllerCallbacks.containsKey(controller)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        inspectController(controller, "MetadataChanged")
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        inspectController(controller, "PlaybackStateChanged")
                    }

                    override fun onSessionDestroyed() {
                        Log.d(TAG, "Session destroyed for package: ${controller.packageName}")
                        try {
                            controller.unregisterCallback(this)
                        } catch (ignored: Throwable) {}
                        controllerCallbacks.remove(controller)
                    }
                }

                try {
                    controller.registerCallback(callback, mainHandler)
                    controllerCallbacks[controller] = callback
                    Log.d(TAG, "Registered callback for package: ${controller.packageName}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to register callback for ${controller.packageName}: ${t.message}")
                }
            }
        }

        // Evaluate all controllers to find the currently playing one
        evaluateActivePlayback()
    }

    /**
     * Inspects a specific controller that triggered a callback.
     */
    private fun inspectController(controller: MediaController, trigger: String) {
        val track = extractTrackInfo(controller)
        Log.d(TAG, "Event [$trigger] from ${controller.packageName}: $track")

        // If this controller is currently playing, report it immediately
        if (track != null && track.isPlaying) {
            reportTrackChange(track)
        } else {
            // Otherwise, re-check across all active controllers
            evaluateActivePlayback()
        }
    }

    /**
     * Scans through all registered controllers and selects the playing one.
     * If no controller is actively playing, checks if the last known track was paused.
     */
    private fun evaluateActivePlayback() {
        var playingTrack: TrackInfo? = null

        for (controller in controllerCallbacks.keys) {
            val track = extractTrackInfo(controller) ?: continue
            if (track.isPlaying) {
                playingTrack = track
                break
            }
        }

        if (playingTrack != null) {
            reportTrackChange(playingTrack)
        } else {
            // All controllers are paused/stopped or idle
            val last = lastReportedTrack
            if (last != null && last.isPlaying) {
                // Transition to paused
                val pausedTrack = last.copy(isPlaying = false)
                reportTrackChange(pausedTrack)
            }
        }
    }

    /**
     * Extracts TrackInfo (Title, Artist, Duration in seconds, PlaybackState) from a MediaController.
     */
    private fun extractTrackInfo(controller: MediaController): TrackInfo? {
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

        // If metadata is null and playbackState is null, there is nothing to extract
        if (metadata == null && playbackState == null) {
            return null
        }

        // 1. Extract Title
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
            ?: "Unknown Title"

        // 2. Extract Artist
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.description?.subtitle?.toString()
            ?: "Unknown Artist"

        // Ignore completely empty or stub metadata
        if (title.isBlank() && artist.isBlank()) {
            return null
        }

        // 3. Extract Duration (milliseconds -> seconds)
        val rawDurationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val durationSeconds = if (rawDurationMs > 0) rawDurationMs / 1000L else 0L

        return TrackInfo(
            title = title.trim(),
            artist = artist.trim(),
            durationSeconds = durationSeconds,
            isPlaying = isPlaying,
            packageName = controller.packageName
        )
    }

    /**
     * Logs the track change clearly to Logcat if the track or playback state actually changed.
     * Ready to be hooked into Step 2 (filtering/caching) and Step 3 (Telegram search).
     */
    private fun reportTrackChange(newTrack: TrackInfo) {
        val previous = lastReportedTrack

        // Avoid logging if the metadata and playing state have not changed
        if (previous != null &&
            previous.trackKey == newTrack.trackKey &&
            previous.isPlaying == newTrack.isPlaying &&
            previous.durationSeconds == newTrack.durationSeconds
        ) {
            return
        }

        lastReportedTrack = newTrack

        // Clean, structured Logcat output for Step 1
        Log.i(TAG, "==================== TRACK UPDATE ====================")
        Log.i(TAG, "App:      ${newTrack.packageName}")
        Log.i(TAG, "Title:    ${newTrack.title}")
        Log.i(TAG, "Artist:   ${newTrack.artist}")
        Log.i(TAG, "Duration: ${newTrack.durationSeconds}s")
        Log.i(TAG, "Status:   ${if (newTrack.isPlaying) "PLAYING" else "PAUSED/STOPPED"}")
        Log.i(TAG, "======================================================")

        // Trigger Step 2: Local Caching & Telegram Music Search
        com.extragram.plugin.mediasync.manager.TrackSyncManager.getInstance(this).onTrackChanged(newTrack)
    }

    private fun cleanup() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Error removing OnActiveSessionsChangedListener", t)
        }

        for ((controller, callback) in controllerCallbacks) {
            try {
                controller.unregisterCallback(callback)
            } catch (ignored: Throwable) {}
        }
        controllerCallbacks.clear()
        lastReportedTrack = null
    }
}
