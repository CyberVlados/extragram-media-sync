package com.extragram.plugin.mediasync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.extragram.plugin.mediasync.manager.TrackSyncManager
import com.extragram.plugin.mediasync.util.MediaPermissionHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnOpenSettings: Button
    private lateinit var btnToggleSync: Button
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvBioStatus: TextView
    private lateinit var btnRestoreBio: Button
    private lateinit var tvCacheCount: TextView
    private lateinit var btnClearCache: Button

    private val syncManager by lazy { TrackSyncManager.getInstance(this) }
    private val profileManager by lazy { syncManager.profileManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnToggleSync = findViewById(R.id.btnToggleSync)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvBioStatus = findViewById(R.id.tvBioStatus)
        btnRestoreBio = findViewById(R.id.btnRestoreBio)
        tvCacheCount = findViewById(R.id.tvCacheCount)
        btnClearCache = findViewById(R.id.btnClearCache)

        btnOpenSettings.setOnClickListener {
            startActivity(MediaPermissionHelper.createNotificationAccessSettingsIntent())
        }

        btnToggleSync.setOnClickListener {
            val newState = !profileManager.isSyncEnabled
            profileManager.isSyncEnabled = newState
            updateSyncToggleButton()
        }

        btnRestoreBio.setOnClickListener {
            lifecycleScope.launch {
                val success = profileManager.restoreOriginalBio()
                if (success) {
                    tvBioStatus.text = "Original bio restored."
                }
            }
        }

        btnClearCache.setOnClickListener {
            syncManager.cacheManager.clear()
            updateCacheDisplay()
            tvSyncStatus.text = "Local cache cleared."
        }

        // Live track & search updates
        syncManager.onSyncStatusChanged = { statusMessage ->
            runOnUiThread {
                tvSyncStatus.text = statusMessage
                updateCacheDisplay()
            }
        }

        // Live profile bio updates
        profileManager.onProfileStatusChanged = { bioMessage ->
            runOnUiThread {
                tvBioStatus.text = bioMessage
            }
        }

        updateSyncToggleButton()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateCacheDisplay()
        updateSyncToggleButton()
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

    private fun updateSyncToggleButton() {
        val enabled = profileManager.isSyncEnabled
        btnToggleSync.text = if (enabled) "Bio Sync: ENABLED" else "Bio Sync: DISABLED"
    }

    private fun updateCacheDisplay() {
        val count = syncManager.cacheManager.size()
        tvCacheCount.text = "Cached Tracks: $count"
    }

    override fun onDestroy() {
        super.onDestroy()
        syncManager.onSyncStatusChanged = null
        profileManager.onProfileStatusChanged = null
    }
}
