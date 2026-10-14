package com.extragram.plugin.mediasync.search

import android.util.Log
import com.extragram.plugin.mediasync.model.TelegramAudioDocument
import com.extragram.plugin.mediasync.telegram.FloodWaitException
import com.extragram.plugin.mediasync.telegram.ITelegramSearchClient
import com.extragram.plugin.mediasync.telegram.TelegramSearchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Service that handles Telegram music queries and filters candidates based on duration tolerance.
 *
 * @param searchClient MTProto search client implementation.
 */
class TelegramMusicSearchService(
    private val searchClient: ITelegramSearchClient
) {

    companion object {
        private const val TAG = "MusicSearchService"
        const val DEFAULT_DURATION_TOLERANCE_SECONDS = 3
    }

    // Cooldown timestamp after encountering FLOOD_WAIT
    @Volatile
    private var floodWaitExpirationEpochMs: Long = 0L

    /**
     * Checks if Telegram API is currently imposing a FLOOD_WAIT rate limit.
     */
    fun isFloodWaitActive(): Boolean {
        return System.currentTimeMillis() < floodWaitExpirationEpochMs
    }

    /**
     * Gets remaining FLOOD_WAIT time in seconds.
     */
    fun getRemainingFloodWaitSeconds(): Long {
        val remainingMs = floodWaitExpirationEpochMs - System.currentTimeMillis()
        return if (remainingMs > 0) remainingMs / 1000L else 0L
    }

    /**
     * Searches Telegram for an audio file matching "Artist - Title", filtering results
     * within a specified duration tolerance (default: ±3 seconds).
     *
     * @param artist The track artist.
     * @param title The track title.
     * @param targetDurationSeconds Duration reported by the active media player.
     * @param toleranceSeconds Allowed duration deviation in seconds (default ±3s).
     * @return Result containing matched TelegramAudioDocument or null if no candidate met the criteria.
     */
    suspend fun searchAndMatch(
        artist: String,
        title: String,
        targetDurationSeconds: Long,
        toleranceSeconds: Int = DEFAULT_DURATION_TOLERANCE_SECONDS
    ): Result<TelegramAudioDocument?> = withContext(Dispatchers.IO) {

        // 1. Check active FLOOD_WAIT rate limit
        if (isFloodWaitActive()) {
            val remaining = getRemainingFloodWaitSeconds()
            Log.w(TAG, "[FLOOD_WAIT ACTIVE] Skipping search for '$artist - $title'. Cooldown remaining: ${remaining}s")
            return@withContext Result.failure(
                FloodWaitException(
                    remaining.toInt(),
                    "FLOOD_WAIT is active. Skipping search for ${remaining}s"
                )
            )
        }

        // 2. Build search query "Artist - Title"
        val query = "${artist.trim()} - ${title.trim()}".trim()
        Log.i(TAG, "[SEARCH START] Query: \"$query\" | Target Duration: ${targetDurationSeconds}s (tolerance: ±${toleranceSeconds}s)")

        // 3. Execute search with transient backoff retry
        var attempts = 0
        val maxAttempts = 2
        var lastException: Throwable? = null

        while (attempts < maxAttempts) {
            attempts++
            try {
                val candidates = searchClient.searchGlobalMusic(query, limit = 20)
                Log.d(TAG, "Retrieved ${candidates.size} raw candidates from Telegram searchGlobal")

                if (candidates.isEmpty()) {
                    Log.i(TAG, "[NO CANDIDATES] Telegram returned 0 music files for query: \"$query\"")
                    return@withContext Result.success(null)
                }

                // 4. Filter candidates by duration tolerance: abs(candidate.duration - target) <= tolerance
                val matchedCandidates = if (targetDurationSeconds > 0) {
                    candidates.filter { candidate ->
                        val diff = abs(candidate.durationSeconds.toLong() - targetDurationSeconds)
                        diff <= toleranceSeconds.toLong()
                    }
                } else {
                    // If active player duration was unknown/0, fallback to all returned candidates
                    candidates
                }

                Log.d(TAG, "Filtered candidates within ±${toleranceSeconds}s tolerance: ${matchedCandidates.size}")

                if (matchedCandidates.isEmpty()) {
                    Log.w(
                        TAG,
                        "[DURATION MISMATCH] Candidates found (${candidates.size}), but none matched target ${targetDurationSeconds}s ±${toleranceSeconds}s."
                    )
                    return@withContext Result.success(null)
                }

                // 5. Select best candidate (closest duration)
                val bestMatch = matchedCandidates.minByOrNull {
                    abs(it.durationSeconds.toLong() - targetDurationSeconds)
                }

                if (bestMatch != null) {
                    val durationDiff = abs(bestMatch.durationSeconds.toLong() - targetDurationSeconds)
                    Log.i(
                        TAG,
                        "==================== SEARCH MATCH ====================\n" +
                                "Matched Track: ${bestMatch.performer} - ${bestMatch.title}\n" +
                                "Document ID:   ${bestMatch.id}\n" +
                                "Duration:      ${bestMatch.durationSeconds}s (Diff: ${durationDiff}s from target ${targetDurationSeconds}s)\n" +
                                "File ID:       ${bestMatch.fileIdFormatted}\n" +
                                "======================================================"
                    )
                }

                return@withContext Result.success(bestMatch)

            } catch (flood: FloodWaitException) {
                // Record FLOOD_WAIT expiration
                val cooldownMs = flood.waitSeconds * 1000L
                floodWaitExpirationEpochMs = System.currentTimeMillis() + cooldownMs
                Log.w(
                    TAG,
                    "[FLOOD_WAIT TRIGGERED] Telegram requested wait of ${flood.waitSeconds} seconds. Setting cooldown.",
                    flood
                )
                return@withContext Result.failure(flood)

            } catch (t: Throwable) {
                lastException = t
                Log.w(TAG, "Search attempt $attempts failed for query '$query': ${t.message}")
                if (attempts < maxAttempts) {
                    // Exponential backoff before retry (e.g., 1000ms)
                    delay(1000L * attempts)
                }
            }
        }

        Result.failure(lastException ?: TelegramSearchException("Search failed after $maxAttempts attempts"))
    }
}
