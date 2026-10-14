package com.extragram.plugin.mediasync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.extragram.plugin.mediasync.util.MediaPermissionHelper

class MainActivity : AppCompatActivity() {

    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnOpenSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        btnOpenSettings.setOnClickListener {
            startActivity(MediaPermissionHelper.createNotificationAccessSettingsIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
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
}
