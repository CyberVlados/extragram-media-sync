# Extragram Media Sync Plugin & Dashboard

Extragram plugin module that automatically syncs the user's currently playing audio track from any Android media player to their Telegram **Profile Audio Track / Music Widget** (the profile music player card) using MTProto global audio search (`messages.searchGlobal`).

---

## Key Features

1. **Status UI Dashboard:**
   - 🟢 **Notification Access:** Live indicator showing `🟢 Connected` or `🔴 Disabled` with a one-click settings shortcut.
   - 🟢 **Extragram Session:** Verifies active Telegram account session via internal `AccountInstance`.
   - 🎧 **Profile Audio Widget Card:** Displays currently synced Telegram audio document, title, artist, duration, and MTProto file ID.
   - 🔘 **Master Sync Toggle:** One-tap Enable/Disable control for background media syncing.
   - 🧹 **Action Buttons:** Clear Profile Audio Widget manually, or wipe the local track cache.

2. **Profile Audio Widget Sync (Audio Card, Not Text Bio):**
   - Extracts active playback from Android's `MediaSessionManager`.
   - Queries Telegram audio files via MTProto `messages.searchGlobal` (`inputMessagesFilterMusic`).
   - Filters candidate tracks within a $\pm3$ second duration margin.
   - Directly attaches the matched audio file to the user's Profile Music Widget.
   - Automatically pauses or clears the profile audio widget after a 25-second delay when media playback stops.

3. **Protection & Rate Limiting:**
   - Prevents duplicate searches for active tracks.
   - Thread-safe persistent local cache (`Artist_Title` $\to$ Document ID).
   - Strict `FLOOD_WAIT` cooldown handling with exponential backoff.

---

## Project Structure

```
├── .github/workflows/build.yml     # Cloud CI/CD workflow (builds APK on push)
├── app/
│   ├── build.gradle.kts            # Android module config (compileSdk 34, minSdk 26)
│   ├── src/main/
│   │   ├── AndroidManifest.xml     # Service with BIND_NOTIFICATION_LISTENER_SERVICE
│   │   ├── java/com/extragram/plugin/mediasync/
│   │   │   ├── MainActivity.kt                     # Status UI Dashboard
│   │   │   ├── MediaNotificationListenerService.kt # Media session listener
│   │   │   ├── cache/
│   │   │   │   └── TrackCacheManager.kt            # Persistent track cache
│   │   │   ├── manager/
│   │   │   │   └── TrackSyncManager.kt             # Search, cache, and widget coordinator
│   │   │   ├── model/
│   │   │   │   ├── TelegramAudioDocument.kt        # MTProto audio document model
│   │   │   │   └── TrackInfo.kt                    # Active player metadata model
│   │   │   ├── profile/
│   │   │   │   └── ProfileAudioWidgetManager.kt    # Profile Audio Widget manager
│   │   │   ├── search/
│   │   │   │   └── TelegramMusicSearchService.kt   # ±3s duration filtering & FLOOD_WAIT logic
│   │   │   ├── telegram/
│   │   │   │   ├── ExtragramBridgeProfileAudioClient.kt # AccountInstance profile audio bridge
│   │   │   │   ├── ExtragramBridgeSearchClient.kt  # MTProto messages.searchGlobal bridge
│   │   │   │   ├── ITelegramProfileAudioClient.kt  # Profile audio client interface
│   │   │   │   ├── ITelegramSearchClient.kt        # Search client interface
│   │   │   │   └── TelegramExceptions.kt           # FloodWaitException and error types
│   │   │   └── util/
│   │   │       └── MediaPermissionHelper.kt        # Permission verification helper
│   │   └── res/                                    # UI layout, colors, themes, strings
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts                # Root build configuration (AGP 8.3.2, Kotlin 1.9.23)
├── settings.gradle.kts             # Repositories & app module definition
└── gradle.properties
```

---

## Logcat Testing

Filter all plugin logs in Logcat:

```bash
adb logcat -s ExtragramMediaSync,TrackSyncManager,ProfileAudioWidget,MusicSearchService,TrackCacheManager
```
