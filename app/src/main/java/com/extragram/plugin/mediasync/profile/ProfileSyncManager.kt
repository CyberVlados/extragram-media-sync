package com.extragram.plugin.mediasync.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.extragram.plugin.mediasync.model.TelegramAudioDocument
import com.extragram.plugin.mediasync.model.TrackInfo
import com.extragram.plugin.mediasync.telegram.ExtragramBridgeProfileClient
import com.extragram.plugin.mediasync.telegram.FloodWaitException
import com.extragram.plugin.mediasync.telegram.ITelegramProfileClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages syncing active track metadata to the Telegram profile bio/status.
 *
 * Safeguards:
 * - Strict 70-character bio limit with intelligent truncation.
 * - Stores original user bio persistently and restores it when music stops.
 * - 25-second delay before reverting on pause to prevent thrashing.
 * - Minimum 15-second interval between bio updates to prevent rate-limiting.
 * - Active FLOOD_WAIT cooldown management.
 */
class ProfileSyncManager private constructor(
    context: Context,
    private val profileClient: ITelegramProfileClient = ExtragramBridgeProfileClient()
) {

    companion object {
        private const val TAG = "ProfileSyncManager"
        private const val PREFS_NAME = "extragram_profile_sync_prefs"
        private const val KEY_ORIGINAL_BIO = "key_original_bio"
        private const val KEY_IS_BIO_MODIFIED = "key_is_bio_modified"
        private const val KEY_SYNC_ENABLED = "key_sync_enabled"

        const val MAX_BIO_LENGTH = 70
        const val REVERT_DELAY_MS = 25000L
        const val MIN_UPDATE_INTERVAL_MS = 15000L

        @Volatile
        private var instance: ProfileSyncManager? = null

        fun getInstance(context: Context): ProfileSyncManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileSyncManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Formats track into a clean bio string respecting Telegram's 70-character limit.
         */
        fun formatTrackBio(artist: String, title: String): String {
            val prefix = "🎧 "
            val full = "$prefix${artist.trim()} - ${title.trim()}"
            if (full.length <= MAX_BIO_LENGTH) {
                return full
            }
            // Truncate and append ellipsis
            val allowedLength = MAX_BIO_LENGTH - prefix.length - 3
            val body = "${artist.trim()} - ${title.trim()}"
            return prefix + body.take(allowedLength.coerceAtLeast(10)) + "..."
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // Last applied bio to prevent redundant updates
    @Volatile
    private var lastAppliedBio: String? = null

    // Timestamp of last successful profile update
    @Volatile
    private var lastUpdateTimestampMs: Long = 0L

    // FLOOD_WAIT expiration timestamp
    @Volatile
    private var floodWaitExpirationEpochMs: Long = 0L

    // Pending delayed revert job on track pause
    private var pendingRevertJob: Job? = null

    // Callback for UI updates
    var onProfileStatusChanged: ((status: String) -> Unit)? = null

    /**
     * Whether profile sync is enabled by the user.
     */
    var isSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()
            if (!value) {
                // If user disabled sync, revert immediately
                scope.launch { restoreOriginalBio() }
            }
        }

    /**
     * Checks if Telegram FLOOD_WAIT is active.
     */
    fun isFloodWaitActive(): Boolean = System.currentTimeMillis() < floodWaitExpirationEpochMs

    fun getRemainingFloodWaitSeconds(): Long {
        val remaining = floodWaitExpirationEpochMs - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000L else 0L
    }

    /**
     * Called when a track is actively playing and has been matched/cached.
     */
    fun onTrackStartedPlaying(track: TrackInfo, matchedDoc: TelegramAudioDocument? = null) {
        if (!isSyncEnabled) {
            Log.d(TAG, "Profile sync is disabled. Skipping bio update.")
            return
        }

        // Cancel any pending revert job since playback resumed
        pendingRevertJob?.cancel()
        pendingRevertJob = null

        val newBio = formatTrackBio(
            artist = matchedDoc?.performer?.takeIf { it.isNotBlank() } ?: track.artist,
            title = matchedDoc?.title?.takeIf { it.isNotBlank() } ?: track.title
        )

        scope.launch {
            mutex.withLock {
                applyTrackBio(newBio)
            }
        }
    }

    /**
     * Called when active playback pauses or stops.
     */
    fun onTrackPausedOrStopped() {
        if (!isSyncEnabled) return

        // Cancel previous pending revert
        pendingRevertJob?.cancel()

        // Schedule delayed revert
        pendingRevertJob = scope.launch {
            Log.i(TAG, "Playback paused/stopped. Scheduling bio revert in ${REVERT_DELAY_MS / 1000}s...")
            notifyStatus("Paused: Reverting bio in ${REVERT_DELAY_MS / 1000}s...")
            delay(REVERT_DELAY_MS)

            mutex.withLock {
                restoreOriginalBio()
            }
        }
    }

    /**
     * Updates the bio to the current track text with safety checks.
     */
    private suspend fun applyTrackBio(newBio: String) {
        if (newBio == lastAppliedBio) {
            Log.d(TAG, "Bio already matches '$newBio'. Skipping duplicate update.")
            return
        }

        if (isFloodWaitActive()) {
            val wait = getRemainingFloodWaitSeconds()
            Log.w(TAG, "[FLOOD_WAIT ACTIVE] Skipping bio update for ${wait}s.")
            notifyStatus("Profile update paused (FLOOD_WAIT: ${wait}s)")
            return
        }

        // Rate limit: ensure MIN_UPDATE_INTERVAL_MS has elapsed
        val timeSinceLast = System.currentTimeMillis() - lastUpdateTimestampMs
        if (timeSinceLast < MIN_UPDATE_INTERVAL_MS) {
            val waitMs = MIN_UPDATE_INTERVAL_MS - timeSinceLast
            Log.d(TAG, "Throttling profile update. Waiting ${waitMs}ms...")
            delay(waitMs)
        }

        // 1. Back up original bio if this is the first sync in the session
        val isModified = prefs.getBoolean(KEY_IS_BIO_MODIFIED, false)
        if (!isModified) {
            val currentBio = profileClient.getCurrentBio() ?: ""
            prefs.edit()
                .putString(KEY_ORIGINAL_BIO, currentBio)
                .putBoolean(KEY_IS_BIO_MODIFIED, true)
                .apply()
            Log.i(TAG, "Backed up original bio: \"$currentBio\"")
        }

        // 2. Perform MTProto account.updateProfile
        Log.i(TAG, "Setting profile bio to: \"$newBio\"")
        notifyStatus("Updating bio: $newBio")

        val result = profileClient.updateBio(newBio)
        result.fold(
            onSuccess = {
                lastAppliedBio = newBio
                lastUpdateTimestampMs = System.currentTimeMillis()
                Log.i(TAG, "==================== BIO UPDATED ====================\nBio: $newBio\n=====================================================")
                notifyStatus("Bio active: $newBio")
            },
            onFailure = { error ->
                handleUpdateError(error)
            }
        )
    }

    /**
     * Restores the user's original bio.
     */
    suspend fun restoreOriginalBio(): Boolean {
        val isModified = prefs.getBoolean(KEY_IS_BIO_MODIFIED, false)
        if (!isModified) {
            Log.d(TAG, "Bio is not marked as modified. Nothing to restore.")
            notifyStatus("Bio is in original state.")
            return true
        }

        if (isFloodWaitActive()) {
            val wait = getRemainingFloodWaitSeconds()
            Log.w(TAG, "[FLOOD_WAIT ACTIVE] Cannot restore bio right now. Wait ${wait}s.")
            return false
        }

        val originalBio = prefs.getString(KEY_ORIGINAL_BIO, "") ?: ""
        Log.i(TAG, "Restoring original bio: \"$originalBio\"")
        notifyStatus("Restoring original bio...")

        val result = profileClient.updateBio(originalBio)
        return result.fold(
            onSuccess = {
                prefs.edit().putBoolean(KEY_IS_BIO_MODIFIED, false).apply()
                lastAppliedBio = originalBio
                lastUpdateTimestampMs = System.currentTimeMillis()
                Log.i(TAG, "Successfully restored original bio: \"$originalBio\"")
                notifyStatus("Original bio restored: \"$originalBio\"")
                true
            },
            onFailure = { error ->
                handleUpdateError(error)
                false
            }
        )
    }

    private fun handleUpdateError(error: Throwable) {
        if (error is FloodWaitException) {
            floodWaitExpirationEpochMs = System.currentTimeMillis() + (error.waitSeconds * 1000L)
            Log.w(TAG, "[FLOOD_WAIT] Telegram rate-limited profile updates for ${error.waitSeconds}s.")
            notifyStatus("FLOOD_WAIT: ${error.waitSeconds}s cooldown")
        } else {
            Log.e(TAG, "Error updating profile: ${error.message}", error)
            notifyStatus("Profile error: ${error.message}")
        }
    }

    private fun notifyStatus(status: String) {
        onProfileStatusChanged?.invoke(status)
    }
}
