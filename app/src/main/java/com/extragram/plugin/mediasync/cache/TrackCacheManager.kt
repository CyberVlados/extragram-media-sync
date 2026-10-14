package com.extragram.plugin.mediasync.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.extragram.plugin.mediasync.model.TelegramAudioDocument
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe persistent local storage for mapped track metadata.
 * Maps normalized "Artist_Title" -> Telegram audio document / file_id.
 *
 * Uses SharedPreferences with JSON serialization for zero external dependency overhead
 * and immediate persistence across app restarts.
 */
class TrackCacheManager private constructor(context: Context) {

    companion object {
        private const val TAG = "TrackCacheManager"
        private const val PREFS_NAME = "extragram_track_cache_prefs"
        private const val KEY_PREFIX = "track_"

        @Volatile
        private var instance: TrackCacheManager? = null

        fun getInstance(context: Context): TrackCacheManager {
            return instance ?: synchronized(this) {
                instance ?: TrackCacheManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Generates a normalized cache key for an artist and title pair:
         * Lowercase, trimmed, and special characters stripped/normalized.
         */
        fun generateKey(artist: String, title: String): String {
            val cleanArtist = artist.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9а-яё]"), "")
            val cleanTitle = title.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9а-яё]"), "")
            return "${cleanArtist}_$cleanTitle"
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache for ultra-fast lookups
    private val memoryCache = ConcurrentHashMap<String, TelegramAudioDocument>()

    init {
        loadAllFromDisk()
    }

    private fun loadAllFromDisk() {
        try {
            val allEntries: Map<String, *> = prefs.all ?: emptyMap<String, Any>()
            for (entry in allEntries.entries) {
                val key = entry.key
                val value = entry.value
                if (key.startsWith(KEY_PREFIX) && value is String) {
                    try {
                        val json = JSONObject(value)
                        val doc = TelegramAudioDocument.fromJson(json)
                        val cleanKey = key.removePrefix(KEY_PREFIX)
                        memoryCache[cleanKey] = doc
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse cached entry for key $key", e)
                    }
                }
            }
            Log.d(TAG, "Loaded ${memoryCache.size} tracks into memory cache from disk.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error initializing TrackCacheManager", t)
        }
    }

    /**
     * Checks if a track is already cached.
     */
    fun contains(artist: String, title: String): Boolean {
        val key = generateKey(artist, title)
        return memoryCache.containsKey(key)
    }

    /**
     * Retrieves the cached TelegramAudioDocument for a given artist and title.
     * Returns null if not cached (Cache MISS).
     */
    fun get(artist: String, title: String): TelegramAudioDocument? {
        val key = generateKey(artist, title)
        val doc = memoryCache[key]
        if (doc != null) {
            Log.d(TAG, "Cache HIT for '$artist - $title' -> Document ID: ${doc.id}")
        } else {
            Log.d(TAG, "Cache MISS for '$artist - $title'")
        }
        return doc
    }

    /**
     * Stores a matched TelegramAudioDocument into memory and disk cache.
     */
    fun put(artist: String, title: String, document: TelegramAudioDocument) {
        val key = generateKey(artist, title)
        memoryCache[key] = document

        try {
            val jsonString = document.toJson().toString()
            prefs.edit()
                .putString(KEY_PREFIX + key, jsonString)
                .apply()
            Log.i(TAG, "Cache STORED: '$artist - $title' -> Document ID: ${document.id}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist track to disk for key: $key", t)
        }
    }

    /**
     * Returns total number of cached tracks.
     */
    fun size(): Int = memoryCache.size

    /**
     * Clears both in-memory and persistent storage.
     */
    fun clear() {
        memoryCache.clear()
        prefs.edit().clear().apply()
        Log.i(TAG, "Track cache cleared.")
    }
}
