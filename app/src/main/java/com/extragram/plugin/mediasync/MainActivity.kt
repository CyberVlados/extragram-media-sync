package com.extragram.plugin.mediasync

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.extragram.plugin.mediasync.manager.TrackSyncManager
import com.extragram.plugin.mediasync.util.MediaPermissionHelper
import kotlinx.coroutines.launch

/**
 * Status UI Dashboard for the Extragram Media Sync Plugin.
 */
class MainActivity : AppCompatActivity() {

    // Notification Access
    private lateinit var tvNotificationStatusBadge: TextView
    private lateinit var btnGrantNotification: Button

    // Extragram Session
    private lateinit var tvExtragramSessionBadge: TextView
    private lateinit var tvExtragramSessionDetails: TextView

    // Master Switch
    private lateinit var btnMasterSyncToggle: Button

    // Profile Audio Widget Status
    private lateinit var tvWidgetStatusBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvTrackArtist: TextView
    private lateinit var tvTrackDocDetails: TextView
    private lateinit var btnClearWidget: Button

    // Local Cache
    private lateinit var tvCacheCount: TextView
    private lateinit var btnClearCache: Button

    private val syncManager by lazy { TrackSyncManager.getInstance(this) }
    private val widgetManager by lazy { syncManager.widgetManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        tvNotificationStatusBadge = findViewById(R.id.tvNotificationStatusBadge)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)

        tvExtragramSessionBadge = findViewById(R.id.tvExtragramSessionBadge)
        tvExtragramSessionDetails = findViewById(R.id.tvExtragramSessionDetails)

        btnMasterSyncToggle = findViewById(R.id.btnMasterSyncToggle)

        tvWidgetStatusBadge = findViewById(R.id.tvWidgetStatusBadge)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvTrackArtist = findViewById(R.id.tvTrackArtist)
        tvTrackDocDetails = findViewById(R.id.tvTrackDocDetails)
        btnClearWidget = findViewById(R.id.btnClearWidget)

        tvCacheCount = findViewById(R.id.tvCacheCount)
        btnClearCache = findViewById(R.id.btnClearCache)
    }

    private fun setupListeners() {
        btnGrantNotification.setOnClickListener {
            startActivity(MediaPermissionHelper.createNotificationAccessSettingsIntent())
        }

        btnMasterSyncToggle.setOnClickListener {
            val newState = !widgetManager.isSyncEnabled
            widgetManager.isSyncEnabled = newState
            updateDashboard()
        }

        btnClearWidget.setOnClickListener {
            lifecycleScope.launch {
                btnClearWidget.isEnabled = false
                widgetManager.clearProfileWidgetNow()
                btnClearWidget.isEnabled = true
                updateDashboard()
            }
        }

        btnClearCache.setOnClickListener {
            syncManager.cacheManager.clear()
            updateDashboard()
        }

        // Live widget state callback
        widgetManager.onWidgetStateChanged = {
            runOnUiThread {
                updateDashboard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun updateDashboard() {
        // 1. Notification Access Indicator
        val hasNotificationAccess = MediaPermissionHelper.isNotificationAccessGranted(this)
        if (hasNotificationAccess) {
            tvNotificationStatusBadge.text = "🟢 Connected"
            tvNotificationStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
            btnGrantNotification.text = "Settings"
        } else {
            tvNotificationStatusBadge.text = "🔴 Disabled"
            tvNotificationStatusBadge.setTextColor(Color.parseColor("#C62828"))
            btnGrantNotification.text = "Grant Access"
        }

        // 2. Extragram Session Indicator
        val isExtragramLinked = widgetManager.profileClient.isSessionAvailable()
        if (isExtragramLinked) {
            tvExtragramSessionBadge.text = "🟢 Linked to Active Account"
            tvExtragramSessionBadge.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvExtragramSessionBadge.text = "🟡 Standalone / Demo Mode"
            tvExtragramSessionBadge.setTextColor(Color.parseColor("#F57F17"))
        }
        tvExtragramSessionDetails.text = widgetManager.profileClient.getActiveAccountLabel()

        // 3. Master Toggle Switch
        val isSyncOn = widgetManager.isSyncEnabled
        btnMasterSyncToggle.text = if (isSyncOn) "ENABLED" else "DISABLED"

        // 4. Current Profile Audio Widget Track Details
        val activeDoc = widgetManager.currentActiveDocument
        val playingTrack = widgetManager.currentPlayingTrack

        if (activeDoc != null) {
            tvWidgetStatusBadge.text = "🟢 Synced & Active"
            tvWidgetStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
            tvTrackTitle.text = activeDoc.title
            tvTrackArtist.text = activeDoc.performer
            tvTrackDocDetails.text = "Duration: ${activeDoc.durationSeconds}s | File ID: ${activeDoc.fileIdFormatted}"
            btnClearWidget.isEnabled = true
        } else if (playingTrack != null && playingTrack.isPlaying) {
            tvWidgetStatusBadge.text = "🔍 Searching Telegram..."
            tvWidgetStatusBadge.setTextColor(Color.parseColor("#1976D2"))
            tvTrackTitle.text = playingTrack.title
            tvTrackArtist.text = playingTrack.artist
            tvTrackDocDetails.text = "Duration: ${playingTrack.durationSeconds}s | App: ${playingTrack.packageName ?: "Media Player"}"
            btnClearWidget.isEnabled = false
        } else {
            tvWidgetStatusBadge.text = "⚪ Idle"
            tvWidgetStatusBadge.setTextColor(Color.parseColor("#757575"))
            tvTrackTitle.text = "No Active Audio Track"
            tvTrackArtist.text = widgetManager.lastSyncStatus
            tvTrackDocDetails.text = "Play music in Spotify, YouTube Music, or any media app"
            btnClearWidget.isEnabled = false
        }

        // 5. Local Cache Statistics
        val cacheSize = syncManager.cacheManager.size()
        tvCacheCount.text = "$cacheSize tracks cached"
    }

    override fun onDestroy() {
        super.onDestroy()
        widgetManager.onWidgetStateChanged = null
        syncManager.onSyncStatusChanged = null
    }
}
