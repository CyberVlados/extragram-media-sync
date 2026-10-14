package com.extragram.plugin.mediasync.manager

import android.content.Context
import android.util.Log
import com.extragram.plugin.mediasync.cache.TrackCacheManager
import com.extragram.plugin.mediasync.model.TrackInfo
import com.extragram.plugin.mediasync.profile.ProfileAudioWidgetManager
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
 * Main coordinator between Media Listener, Local Cache, Global Search, and Profile Audio Widget.
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
    val widgetManager: ProfileAudioWidgetManager = ProfileAudioWidgetManager.getInstance(appContext)

    @Volatile
    private var currentPlayingTrack: TrackInfo? = null

    private var pendingSearchJob: Job? = null

    var onSyncStatusChanged: ((status: String) -> Unit)? = null

    fun onTrackChanged(newTrack: TrackInfo) {
        if (!newTrack.isPlaying) {
            Log.d(TAG, "Track paused/stopped: ${newTrack.title}")
            pendingSearchJob?.cancel()
            pendingSearchJob = null
            currentPlayingTrack = null
            notifyStatus("Paused: ${newTrack.artist} - ${newTrack.title}")

            widgetManager.onTrackPausedOrStopped()
            return
        }

        val previous = currentPlayingTrack
        if (previous != null &&
            previous.trackKey == newTrack.trackKey &&
            previous.durationSeconds == newTrack.durationSeconds
        ) {
            Log.d(TAG, "Track already active. Skipping duplicate search for: ${newTrack.trackKey}")
            return
        }

        currentPlayingTrack = newTrack
        pendingSearchJob?.cancel()

        pendingSearchJob = scope.launch {
            Log.d(TAG, "Debouncing search for '${newTrack.artist} - ${newTrack.title}' (${DEBOUNCE_DELAY_MS}ms)...")
            delay(DEBOUNCE_DELAY_MS)

            processTrackSync(newTrack)
        }
    }

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
            widgetManager.onTrackMatched(track, cachedDocument)
            return
        }

        // 2. Cache MISS -> search Telegram MTProto
        Log.i(TAG, "[CACHE MISS] No local entry found for '$artist - $title'. Initiating search...")
        notifyStatus("Searching Telegram for: $artist - $title")

        val result = searchService.searchAndMatch(
            artist = artist,
            title = title,
            targetDurationSeconds = duration
        )

        result.fold(
            onSuccess = { matchedDoc ->
                if (matchedDoc != null) {
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
                    notifyStatus("Matched: ${matchedDoc.title}")
                    widgetManager.onTrackMatched(track, matchedDoc)
                } else {
                    Log.w(TAG, "[SEARCH COMPLETED] No matching Telegram audio found for: '$artist - $title'")
                    notifyStatus("No audio match found for: $artist - $title")
                }
            },
            onFailure = { error ->
                if (error is FloodWaitException) {
                    Log.w(TAG, "[RATE LIMIT] Telegram FLOOD_WAIT: ${error.waitSeconds}s remaining.")
                    notifyStatus("Telegram FLOOD_WAIT: wait ${error.waitSeconds}s")
                } else {
                    Log.e(TAG, "[SEARCH FAILED] Error: ${error.message}", error)
                    notifyStatus("Search error: ${error.message}")
                }
            }
        )
    }

    private fun notifyStatus(status: String) {
        onSyncStatusChanged?.invoke(status)
    }
}
