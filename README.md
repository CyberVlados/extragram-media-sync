# Extragram Media Sync (Step 1)

Android media listener service that detects the currently playing audio track across all Android media players (Spotify, YouTube Music, local players, etc.) using `MediaSessionManager` and `NotificationListenerService`.

---

## Project Structure

```
├── .github/workflows/build.yml     # Cloud CI/CD workflow (builds APK on push)
├── app/
│   ├── build.gradle.kts            # Android module config (compileSdk 34, minSdk 26)
│   ├── src/main/
│   │   ├── AndroidManifest.xml     # Service with BIND_NOTIFICATION_LISTENER_SERVICE
│   │   ├── java/com/extragram/plugin/mediasync/
│   │   │   ├── MainActivity.kt                 # Simple UI to check/grant access
│   │   │   ├── MediaNotificationListenerService.kt # Core media session listener
│   │   │   ├── model/TrackInfo.kt              # Metadata data model
│   │   │   └── util/MediaPermissionHelper.kt   # Permission helper
│   │   └── res/                                # Layout, themes, and strings
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts                # Root build configuration
├── settings.gradle.kts             # Dependency & plugin repositories
└── gradle.properties
```

---

## How to Build the APK with GitHub Actions

1. **Initialize Git and push to GitHub:**
   ```bash
   git init
   git add .
   git commit -m "feat: implement Step 1 media listener and cloud build pipeline"
   git branch -M main
   git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO_NAME>.git
   git push -u origin main
   ```

2. **Download the APK:**
   - Open your GitHub repository in your browser.
   - Go to the **Actions** tab.
   - Click on the latest workflow run: **Build Android APK**.
   - Under **Artifacts**, download `ExtragramMediaSync-debug.zip`.
   - Extract the `.apk` and install it on your Android phone or emulator.

---

## Testing on Android

1. Open the **Extragram Media Sync** app.
2. Tap **Grant Notification Access** and enable the toggle for this app.
   *(On Android 13+, if the toggle is greyed out, go to App Info -> three dots in top-right -> "Allow restricted settings", then enable).*
3. Open Spotify, YouTube Music, or any music player and start playing a song.
4. View the extracted track information in Logcat:
   ```bash
   adb logcat -s ExtragramMediaSync
   ```
