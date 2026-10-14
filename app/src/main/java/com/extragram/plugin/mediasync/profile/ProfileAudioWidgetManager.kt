package com.extragram.plugin.mediasync.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.extragram.plugin.mediasync.model.TelegramAudioDocument
import com.extragram.plugin.mediasync.model.TrackInfo
import com.extragram.plugin.mediasync.telegram.ExtragramBridgeProfileAudioClient
import com.extragram.plugin.mediasync.telegram.FloodWaitException
import com.extragram.plugin.mediasync.telegram.ITelegramProfileAudioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages syncing the user's active audio track directly to the Telegram / Extragram Profile Audio Widget.
 */
class ProfileAudioWidgetManager private constructor(
    context: Context,
    val profileClient: ITelegramProfileAudioClient = ExtragramBridgeProfileAudioClient()
) {

    companion object {
        private const val TAG = "ProfileAudioWidget"
        private const val PREFS_NAME = "extragram_profile_audio_prefs"
        private const val KEY_SYNC_ENABLED = "key_audio_widget_sync_enabled"

        const val CLEAR_DELAY_MS = 25000L
        const val MIN_UPDATE_INTERVAL_MS = 10000L

        @Volatile
        private var instance: ProfileAudioWidgetManager? = null

        fun getInstance(context: Context): ProfileAudioWidgetManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileAudioWidgetManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Volatile
    var currentActiveDocument: TelegramAudioDocument? = null
        private set

    @Volatile
    var currentPlayingTrack: TrackInfo? = null
        private set

    @Volatile
    var lastSyncStatus: String = "Idle"
        private set

    private var lastUpdateTimestampMs: Long = 0L
    private var floodWaitExpirationEpochMs: Long = 0L
    private var pendingClearJob: Job? = null

    // Callback for live UI Dashboard updates
    var onWidgetStateChanged: (() -> Unit)? = null

    /**
     * Master switch for automatic profile audio sync.
     */
    var isSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()
            if (!value) {
                scope.launch { clearProfileWidgetNow() }
            }
            notifyStateChanged()
        }

    fun isFloodWaitActive(): Boolean = System.currentTimeMillis() < floodWaitExpirationEpochMs

    fun getRemainingFloodWaitSeconds(): Long {
        val remaining = floodWaitExpirationEpochMs - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000L else 0L
    }

    /**
     * Called when a track is actively playing and a matching Telegram audio document is available.
     */
    fun onTrackMatched(track: TrackInfo, document: TelegramAudioDocument) {
        if (!isSyncEnabled) {
            Log.d(TAG, "Profile audio sync is disabled.")
            return
        }

        // Cancel any pending clear job
        pendingClearJob?.cancel()
        pendingClearJob = null

        currentPlayingTrack = track

        scope.launch {
            mutex.withLock {
                applyProfileAudioWidget(document)
            }
        }
    }

    /**
     * Called when playback pauses or stops.
     */
    fun onTrackPausedOrStopped() {
        if (!isSyncEnabled) return

        pendingClearJob?.cancel()

        pendingClearJob = scope.launch {
            Log.i(TAG, "Playback paused. Scheduling Profile Audio Widget clear in ${CLEAR_DELAY_MS / 1000}s...")
            lastSyncStatus = "Paused (clearing in ${CLEAR_DELAY_MS / 1000}s)"
            notifyStateChanged()

            delay(CLEAR_DELAY_MS)

            mutex.withLock {
                clearProfileWidgetNow()
            }
        }
    }

    private suspend fun applyProfileAudioWidget(document: TelegramAudioDocument) {
        if (currentActiveDocument?.id == document.id) {
            Log.d(TAG, "Audio document ${document.id} already active in Profile Widget.")
            return
        }

        if (isFloodWaitActive()) {
            val wait = getRemainingFloodWaitSeconds()
            lastSyncStatus = "FLOOD_WAIT active (${wait}s)"
            notifyStateChanged()
            return
        }

        val elapsed = System.currentTimeMillis() - lastUpdateTimestampMs
        if (elapsed < MIN_UPDATE_INTERVAL_MS) {
            val waitMs = MIN_UPDATE_INTERVAL_MS - elapsed
            Log.d(TAG, "Throttling widget update. Waiting ${waitMs}ms...")
            delay(waitMs)
        }

        lastSyncStatus = "Syncing ${document.title} to Profile Widget..."
        notifyStateChanged()

        val result = profileClient.setProfileAudio(document)
        result.fold(
            onSuccess = {
                currentActiveDocument = document
                lastUpdateTimestampMs = System.currentTimeMillis()
                lastSyncStatus = "Active: ${document.performer} - ${document.title}"
                Log.i(
                    TAG,
                    "==================== PROFILE AUDIO WIDGET SYNCED ====================\n" +
                            "Track:    ${document.performer} - ${document.title}\n" +
                            "Doc ID:   ${document.id}\n" +
                            "Duration: ${document.durationSeconds}s\n" +
                            "====================================================================="
                )
                notifyStateChanged()
            },
            onFailure = { error ->
                if (error is FloodWaitException) {
                    floodWaitExpirationEpochMs = System.currentTimeMillis() + (error.waitSeconds * 1000L)
                    lastSyncStatus = "FLOOD_WAIT (${error.waitSeconds}s)"
                } else {
                    lastSyncStatus = "Sync Error: ${error.message}"
                }
                notifyStateChanged()
            }
        )
    }

    /**
     * Clears the profile widget immediately.
     */
    suspend fun clearProfileWidgetNow(): Boolean {
        pendingClearJob?.cancel()
        pendingClearJob = null

        val result = profileClient.clearProfileAudio()
        return result.fold(
            onSuccess = {
                currentActiveDocument = null
                currentPlayingTrack = null
                lastSyncStatus = "Profile Audio Widget cleared."
                Log.i(TAG, "Profile Audio Widget cleared.")
                notifyStateChanged()
                true
            },
            onFailure = {
                lastSyncStatus = "Clear failed: ${it.message}"
                notifyStateChanged()
                false
            }
        )
    }

    private fun notifyStateChanged() {
        onWidgetStateChanged?.invoke()
    }
}
