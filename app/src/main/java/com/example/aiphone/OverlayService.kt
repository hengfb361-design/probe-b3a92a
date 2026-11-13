package com.example.aiphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat

/** Floating AI bubble that opens a chat panel above every app. */
class OverlayService : Service() {
    private var manager: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var logView: TextView? = null
    private var logScrollView: ScrollView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val density: Float get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        AgentLog.sink = { line -> append(line) }
        if (bubble == null && panel == null) showBubble()
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI Phone", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Phone")
            .setContentText("ចុចប៊ូប AI ដើម្បីសន្ទនា ឬបញ្ជា")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun params(focusable: Boolean): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (!focusable) p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(8)
        p.y = dp(96)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        return p
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        manager = wm
        val view = TextView(this).apply {
            text = "AI"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#08131F"))
            setBackgroundColor(Color.parseColor("#FFC24B"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { openPanel() }
        }
        wm.addView(view, params(false))
        bubble = view
    }

    private fun openPanel() {
        val wm = manager ?: return
        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2081118"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.layoutParams = LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AI Phone · Groq"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFD37A"))
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val clear = TextView(this).apply {
            text = "🗑"
            textSize = 13f
            setTextColor(Color.parseColor("#9FB3C8"))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                AgentChat.clear()
                logView?.text = ""
                append("✨ សន្ទនាថ្មី")
            }
        }
        header.addView(clear)

        val close = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#E6EDF3"))
            setPadding(dp(10), 0, dp(2), 0)
            setOnClickListener { closePanel() }
        }
        header.addView(close)
        root.addView(header)

        val log = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CDE7FF"))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#08131F"))
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250))
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "សួរ AI ឬបញ្ជា… ឧ. បើក tiktok អូស 20"
            textSize = 13f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(Color.parseColor("#E6EDF3"))
            setHintTextColor(Color.parseColor("#7A889B"))
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val send = Button(this).apply { text = "➤" }
        row.addView(send)
        root.addView(row)

        wm.addView(root, params(true))
        panel = root
        logView = log
        logScrollView = logScroll

        send.setOnClickListener { submit(input, log) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(input, log)
                true
            } else {
                false
            }
        }
    }

    private fun submit(input: EditText, log: TextView) {
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        input.setText("")
        if (log.text.isEmpty()) log.text = "👤 " + command else log.append("\n👤 " + command)
        scrollSoon()
        AgentRunner.run(applicationContext, command) { line -> append(line) }
    }

    private fun closePanel() {
        panel?.let { view -> manager?.let { runCatching { it.removeView(view) } } }
        panel = null
        logView = null
        logScrollView = null
        showBubble()
    }

    private fun scrollSoon() {
        ui.post { logScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun append(line: String) {
        ui.post {
            val view = logView ?: return@post
            if (view.text.isEmpty()) view.text = line else view.append("\n" + line)
            scrollSoon()
        }
    }

    override fun onDestroy() {
        AgentLog.sink = null
        bubble?.let { view -> manager?.let { runCatching { it.removeView(view) } } }
        panel?.let { view -> manager?.let { runCatching { it.removeView(view) } } }
        bubble = null
        panel = null
        logView = null
        logScrollView = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ai_phone"
        private const val NOTIFICATION_ID = 77
    }
}
