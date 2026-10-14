# Extragram Media Sync (Step 1 & Step 2)

Android media listener and Telegram music search & caching plugin/app for Extragram.
Detects active playback across all media players, queries Telegram MTProto for matching audio files (`messages.searchGlobal`), validates duration within ±3s tolerance, and maintains a persistent local cache.

---

## Architecture Plan & Progress

- [x] **Step 1: Android Media Listener**
  - NotificationListenerService & MediaSessionManager callbacks
  - Active controller metadata & playback state extraction
- [x] **Step 2: Telegram Global Search & Local Caching**
  - MTProto `messages.searchGlobal` with `inputMessagesFilterMusic`
  - Candidate duration matching (±3 seconds tolerance)
  - Persistent thread-safe cache (`Artist_Title` -> Telegram Document / `file_id`)
  - Prevention of redundant searches on seek / pause / identical tracks
  - Rate limiting & `FLOOD_WAIT` cooldown handling with exponential backoff
- [ ] **Step 3 & 4 (Next): Profile Music Update & Flood Handling**
  - MTProto profile update method with matched audio document
  - Real-time profile status sync

---

## Project Structure

```
├── .github/workflows/build.yml     # Cloud CI/CD workflow (builds APK on push)
├── app/
│   ├── build.gradle.kts            # Android module config (compileSdk 34, minSdk 26)
│   ├── src/main/
│   │   ├── AndroidManifest.xml     # Service with BIND_NOTIFICATION_LISTENER_SERVICE
│   │   ├── java/com/extragram/plugin/mediasync/
│   │   │   ├── MainActivity.kt                     # Status UI, permission, cache management
│   │   │   ├── MediaNotificationListenerService.kt # Core media session listener
│   │   │   ├── cache/
│   │   │   │   └── TrackCacheManager.kt            # Local key-value persistent storage
│   │   │   ├── manager/
│   │   │   │   └── TrackSyncManager.kt             # Orchestration, debouncing, deduplication
│   │   │   ├── model/
│   │   │   │   ├── TelegramAudioDocument.kt        # MTProto audio document data model
│   │   │   │   └── TrackInfo.kt                    # Active player metadata model
│   │   │   ├── search/
│   │   │   │   └── TelegramMusicSearchService.kt   # ±3s duration filtering & FLOOD_WAIT logic
│   │   │   ├── telegram/
│   │   │   │   ├── ExtragramBridgeSearchClient.kt  # MTProto reflection bridge & standalone fallback
│   │   │   │   ├── ITelegramSearchClient.kt        # Search client interface
│   │   │   │   └── TelegramExceptions.kt           # FloodWaitException and search errors
│   │   │   └── util/
│   │   │       └── MediaPermissionHelper.kt        # Notification access helper
│   │   └── res/                                    # UI layout, themes, strings
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts                # Root build configuration (AGP 8.3.2, Kotlin 1.9.23)
├── settings.gradle.kts             # Dependency & plugin repositories
└── gradle.properties
```

---

## Testing on Android & Logcat

1. Build or download the APK from GitHub Actions.
2. Grant Notification Access in the app.
3. Play music in Spotify, YouTube Music, or any media app.
4. Filter Logcat:
   ```bash
   adb logcat -s ExtragramMediaSync,TrackSyncManager,MusicSearchService,TrackCacheManager
   ```

### Expected Logcat Flow:

**On Cache Miss (first time playing track):**
```text
ExtragramMediaSync: ==================== TRACK UPDATE ====================
ExtragramMediaSync: App:      com.spotify.music
ExtragramMediaSync: Title:    Blinding Lights
ExtragramMediaSync: Artist:   The Weeknd
ExtragramMediaSync: Duration: 200s
ExtragramMediaSync: Status:   PLAYING
ExtragramMediaSync: ======================================================
TrackCacheManager: Cache MISS for 'The Weeknd - Blinding Lights'
MusicSearchService: [SEARCH START] Query: "The Weeknd - Blinding Lights" | Target Duration: 200s (tolerance: ±3s)
MusicSearchService: ==================== SEARCH MATCH ====================
MusicSearchService: Matched Track: The Weeknd - Blinding Lights
MusicSearchService: Document ID:   5829104829103
MusicSearchService: Duration:      200s (Diff: 0s from target 200s)
MusicSearchService: File ID:       doc_5829104829103_92019481029481
MusicSearchService: ======================================================
TrackSyncManager: ==================== [CACHE SAVED] ====================
TrackSyncManager: Saved key:   theweeknd_blindinglights
TrackSyncManager: Track:       The Weeknd - Blinding Lights
TrackSyncManager: Doc ID:      5829104829103
TrackSyncManager: Total Cache: 1 items
TrackSyncManager: =======================================================
```

**On Cache Hit (replaying or resuming):**
```text
TrackCacheManager: Cache HIT for 'The Weeknd - Blinding Lights' -> Document ID: 5829104829103
TrackSyncManager: ==================== [CACHE HIT] ====================
TrackSyncManager: Track:       The Weeknd - Blinding Lights
TrackSyncManager: Cached Doc:  Blinding Lights (200s)
TrackSyncManager: File ID:     doc_5829104829103_92019481029481
TrackSyncManager: Doc ID:      5829104829103
TrackSyncManager: =====================================================
```
