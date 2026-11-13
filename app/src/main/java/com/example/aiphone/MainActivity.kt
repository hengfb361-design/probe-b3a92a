package com.example.aiphone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Setup screen: the key, the two system toggles, and the agent service. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var keyField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)
        status = findViewById(R.id.status)
        keyField = findViewById(R.id.groqKey)
        keyField.setText(Prefs.key(this))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            Prefs.setKey(this, keyField.text.toString().trim())
            show("API key បានរក្សាទុក ✓")
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            show("AI overlay ដំណើរការ — ចុចប៊ូប AI")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            show("បានបញ្ឈប់ overlay")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        show(stateLine())
    }

    private fun stateLine(): String =
        "overlay: " + Settings.canDrawOverlays(this) +
            " · accessibility: " + (AgentAccessibilityService.instance != null) +
            " · key: " + Prefs.key(this).isNotEmpty()

    private fun show(text: String) {
        status.text = text
    }
}
