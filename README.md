# Extragram Media Sync (Full Architecture: Steps 1, 2, and 3)

Android media listener, Telegram global music search & local cache, and real-time profile bio/status updater plugin/app for Extragram.

---

## Complete Pipeline Architecture

```
[Any Android Music Player] (Spotify, YouTube Music, local player)
           │
           ▼
[MediaNotificationListenerService] (Step 1)
   Extracts: Title, Artist, Duration (s), PlaybackState
           │
           ▼ (Debounce 1.5s)
[TrackSyncManager]
   ┌───────┴────────────────────────┐
   ▼                                ▼
[TrackCacheManager] (Step 2)      [ProfileSyncManager] (Step 3)
   - Cache HIT -> Returns doc        - 70-char limit formatting: "🎧 Artist - Title"
   - Cache MISS -> MTProto Search    - Persistently backs up user's original bio
     (messages.searchGlobal)         - Sets active track into Telegram bio
     (±3s duration tolerance)        - Reverts to original bio on pause (25s delay)
     (FLOOD_WAIT handling)           - Rate-limiting (min 15s between updates)
```

---

## Implemented Steps

- [x] **Step 1: Android Media Listener**
  - `NotificationListenerService` & `MediaSessionManager` callbacks
  - Active controller metadata & playback state extraction
- [x] **Step 2: Telegram Global Search & Local Caching**
  - MTProto `messages.searchGlobal` with `inputMessagesFilterMusic`
  - Candidate duration matching ($\pm3$ seconds tolerance)
  - Persistent thread-safe cache (`Artist_Title` -> Telegram Document / `file_id`)
  - Rate limiting & `FLOOD_WAIT` cooldown handling
- [x] **Step 3: Profile Status/Bio Update & MTProto Integration**
  - MTProto `account.updateProfile` with `about`
  - Automatic formatting within Telegram's strict 70-character limit
  - Original bio persistence & automatic 25s delayed restoration on pause
  - Rate-limit protection (min 15s interval) and `FLOOD_WAIT` avoidance
  - UI controls to toggle bio sync and manually restore original bio

---

## Logcat Testing

Filter all pipeline events in Logcat:

```bash
adb logcat -s ExtragramMediaSync,TrackSyncManager,MusicSearchService,TrackCacheManager,ProfileSyncManager
```

### Complete End-to-End Logcat Trace

**1. Music Starts Playing:**
```text
ExtragramMediaSync: ==================== TRACK UPDATE ====================
ExtragramMediaSync: App:      com.spotify.music
ExtragramMediaSync: Title:    Blinding Lights
ExtragramMediaSync: Artist:   The Weeknd
ExtragramMediaSync: Duration: 200s
ExtragramMediaSync: Status:   PLAYING
ExtragramMediaSync: ======================================================
TrackCacheManager: Cache HIT for 'The Weeknd - Blinding Lights' -> Document ID: 5829104829103
TrackSyncManager: ==================== [CACHE HIT] ====================
TrackSyncManager: Track:       The Weeknd - Blinding Lights
TrackSyncManager: Cached Doc:  Blinding Lights (200s)
TrackSyncManager: =====================================================
ProfileSyncManager: Setting profile bio to: "🎧 The Weeknd - Blinding Lights"
ProfileSyncManager: Backed up original bio: "Software Engineer & Music Lover"
ProfileSyncManager: ==================== BIO UPDATED ====================
ProfileSyncManager: Bio: 🎧 The Weeknd - Blinding Lights
ProfileSyncManager: =====================================================
```

**2. Music Pauses:**
```text
ExtragramMediaSync: ==================== TRACK UPDATE ====================
ExtragramMediaSync: Status:   PAUSED/STOPPED
ExtragramMediaSync: ======================================================
ProfileSyncManager: Playback paused/stopped. Scheduling bio revert in 25s...
... (25 seconds pass) ...
ProfileSyncManager: Restoring original bio: "Software Engineer & Music Lover"
ProfileSyncManager: Successfully restored original bio: "Software Engineer & Music Lover"
```
