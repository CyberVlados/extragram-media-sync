package com.extragram.plugin.mediasync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.extragram.plugin.mediasync.manager.TrackSyncManager
import com.extragram.plugin.mediasync.util.MediaPermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnOpenSettings: Button
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvCacheCount: TextView
    private lateinit var btnClearCache: Button

    private val syncManager by lazy { TrackSyncManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvCacheCount = findViewById(R.id.tvCacheCount)
        btnClearCache = findViewById(R.id.btnClearCache)

        btnOpenSettings.setOnClickListener {
            startActivity(MediaPermissionHelper.createNotificationAccessSettingsIntent())
        }

        btnClearCache.setOnClickListener {
            syncManager.cacheManager.clear()
            updateCacheDisplay()
            tvSyncStatus.text = "Local cache cleared."
        }

        // Live status updates from TrackSyncManager
        syncManager.onSyncStatusChanged = { statusMessage ->
            runOnUiThread {
                tvSyncStatus.text = statusMessage
                updateCacheDisplay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateCacheDisplay()
    }

    private fun updatePermissionStatus() {
        val isGranted = MediaPermissionHelper.isNotificationAccessGranted(this)
        if (isGranted) {
            tvPermissionStatus.setText(R.string.permission_granted)
            btnOpenSettings.setText(R.string.open_settings)
        } else {
            tvPermissionStatus.setText(R.string.permission_not_granted)
            btnOpenSettings.setText(R.string.grant_permission)
        }
    }

    private fun updateCacheDisplay() {
        val count = syncManager.cacheManager.size()
        tvCacheCount.text = "Cached Tracks: $count"
    }

    override fun onDestroy() {
        super.onDestroy()
        syncManager.onSyncStatusChanged = null
    }
}
