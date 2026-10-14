package com.extragram.plugin.mediasync.manager

import android.content.Context
import android.util.Log
import com.extragram.plugin.mediasync.cache.TrackCacheManager
import com.extragram.plugin.mediasync.model.TrackInfo
import com.extragram.plugin.mediasync.profile.ProfileSyncManager
import com.extragram.plugin.mediasync.search.TelegramMusicSearchService
import com.extragram.plugin.mediasync.telegram.ExtragramBridgeSearchClient
import com.extragram.plugin.mediasync.telegram.FloodWaitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates Step 1 playback events, Step 2 local caching & search, and Step 3 profile updates.
 *
 * Flow:
 * 1. Active playback detected -> Debounce 1.5s
 * 2. Check local cache (HIT -> Update profile bio immediately)
 * 3. Cache MISS -> Query MTProto messages.searchGlobal
 * 4. Match candidate within ±3s tolerance -> Save to cache -> Update profile bio
 * 5. Playback paused/stopped -> Delay 25s -> Restore user's original bio
 */
class TrackSyncManager private constructor(context: Context) {

    companion object {
        private const val TAG = "TrackSyncManager"
        private const val DEBOUNCE_DELAY_MS = 1500L

        @Volatile
        private var instance: TrackSyncManager? = null

        fun getInstance(context: Context): TrackSyncManager {
            return instance ?: synchronized(this) {
                instance ?: TrackSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val cacheManager: TrackCacheManager = TrackCacheManager.getInstance(appContext)
    val searchService: TelegramMusicSearchService = TelegramMusicSearchService(ExtragramBridgeSearchClient())
    val profileManager: ProfileSyncManager = ProfileSyncManager.getInstance(appContext)

    // Tracks current active playing song to avoid re-searching identical tracks
    @Volatile
    private var currentPlayingTrack: TrackInfo? = null

    // Job for debouncing search queries
    private var pendingSearchJob: Job? = null

    // Listener callback for UI status updates
    var onSyncStatusChanged: ((status: String) -> Unit)? = null

    /**
     * Called when a track update arrives from MediaNotificationListenerService.
     */
    fun onTrackChanged(newTrack: TrackInfo) {
        if (!newTrack.isPlaying) {
            Log.d(TAG, "Track is paused or stopped: ${newTrack.title}")
            pendingSearchJob?.cancel()
            pendingSearchJob = null
            currentPlayingTrack = null
            notifyStatus("Paused: ${newTrack.artist} - ${newTrack.title}")

            // Trigger delayed profile revert
            profileManager.onTrackPausedOrStopped()
            return
        }

        // Check if track is identical to currently playing track
        val previous = currentPlayingTrack
        if (previous != null &&
            previous.trackKey == newTrack.trackKey &&
            previous.durationSeconds == newTrack.durationSeconds
        ) {
            Log.d(TAG, "Track already active. Skipping duplicate search for: ${newTrack.trackKey}")
            return
        }

        currentPlayingTrack = newTrack

        // Cancel previous pending search (debouncing rapid track skipping)
        pendingSearchJob?.cancel()

        pendingSearchJob = scope.launch {
            Log.d(TAG, "Debouncing search for '${newTrack.artist} - ${newTrack.title}' (${DEBOUNCE_DELAY_MS}ms)...")
            delay(DEBOUNCE_DELAY_MS)

            processTrackSync(newTrack)
        }
    }

    /**
     * Executes the local cache check, Telegram search, and profile bio update pipeline.
     */
    private suspend fun processTrackSync(track: TrackInfo) {
        val artist = track.artist
        val title = track.title
        val duration = track.durationSeconds

        notifyStatus("Processing: $artist - $title")

        // 1. Check local cache
        val cachedDocument = cacheManager.get(artist, title)
        if (cachedDocument != null) {
            Log.i(
                TAG,
                "==================== [CACHE HIT] ====================\n" +
                        "Track:       $artist - $title\n" +
                        "Cached Doc:  ${cachedDocument.title} (${cachedDocument.durationSeconds}s)\n" +
                        "File ID:     ${cachedDocument.fileIdFormatted}\n" +
                        "Doc ID:      ${cachedDocument.id}\n" +
                        "====================================================="
            )
            notifyStatus("Cache Hit: Found ${cachedDocument.title}")

            // Update Telegram profile bio using cached document info
            profileManager.onTrackStartedPlaying(track, cachedDocument)
            return
        }

        // 2. Cache MISS -> perform Telegram search
        Log.i(TAG, "[CACHE MISS] No local entry found for '$artist - $title'. Initiating Telegram search...")
        notifyStatus("Searching Telegram for: $artist - $title")

        val result = searchService.searchAndMatch(
            artist = artist,
            title = title,
            targetDurationSeconds = duration
        )

        result.fold(
            onSuccess = { matchedDoc ->
                if (matchedDoc != null) {
                    // 3. Save matched document into local cache
                    cacheManager.put(artist, title, matchedDoc)
                    Log.i(
                        TAG,
                        "==================== [CACHE SAVED] ====================\n" +
                                "Saved key:   ${TrackCacheManager.generateKey(artist, title)}\n" +
                                "Track:       ${matchedDoc.performer} - ${matchedDoc.title}\n" +
                                "Doc ID:      ${matchedDoc.id}\n" +
                                "File ID:     ${matchedDoc.fileIdFormatted}\n" +
                                "Total Cache: ${cacheManager.size()} items\n" +
                                "======================================================="
                    )
                    notifyStatus("Matched & Cached: ${matchedDoc.title}")
                    profileManager.onTrackStartedPlaying(track, matchedDoc)
                } else {
                    Log.w(TAG, "[SEARCH COMPLETED] No matching Telegram audio found for: '$artist - $title'")
                    notifyStatus("No match found for: $artist - $title")
                    // Even if audio file isn't on Telegram, update profile bio with track info
                    profileManager.onTrackStartedPlaying(track, null)
                }
            },
            onFailure = { error ->
                if (error is FloodWaitException) {
                    Log.w(TAG, "[RATE LIMIT] Telegram FLOOD_WAIT enforced: ${error.waitSeconds}s remaining.")
                    notifyStatus("Telegram FLOOD_WAIT: wait ${error.waitSeconds}s")
                } else {
                    Log.e(TAG, "[SEARCH FAILED] Error querying Telegram: ${error.message}", error)
                    notifyStatus("Search error: ${error.message}")
                    // Still update profile with current media player metadata
                    profileManager.onTrackStartedPlaying(track, null)
                }
            }
        )
    }

    private fun notifyStatus(status: String) {
        onSyncStatusChanged?.invoke(status)
    }
}
