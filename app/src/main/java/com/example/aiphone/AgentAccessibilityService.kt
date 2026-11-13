package com.example.aiphone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The hands of the agent: open a package, tap by text, type, swipe, read
    the screen, and the global actions. */
class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    fun openApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    fun swipeUp(): Boolean = swipe(0.78f, 0.22f)

    fun swipeDown(): Boolean = swipe(0.25f, 0.75f)

    private fun swipe(from: Float, to: Float): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * from)
            lineTo(x, metrics.heightPixels * to)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Clicks the node whose text or description contains the words. */
    fun tapText(query: String): Boolean {
        val wanted = query.trim().lowercase()
        if (wanted.isEmpty()) return false
        val node = find(wanted) ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tapAt(rect.centerX(), rect.centerY())
    }

    fun typeText(text: String): Boolean {
        val frame = rootInActiveWindow ?: return false
        var node = frame.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) node = firstEditable(frame, 0)
        if (node == null) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(): Boolean {
        val frame = rootInActiveWindow ?: return false
        val focused = frame.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && Build.VERSION.SDK_INT >= 30) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }
        return tapText("search") || tapText("ស្វែងរក")
    }

    /** What the user sees: visible texts of the other windows only. */
    fun screenText(): String {
        val frame = rootInActiveWindow ?: return ""
        val parts = mutableListOf<String>()
        walk(frame, 0) { node ->
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.length > 1 && parts.size < 90 && !parts.contains(text)) parts.add(text)
        }
        return parts.joinToString(" · ").take(1200)
    }

    private fun find(wanted: String): AccessibilityNodeInfo? {
        val frame = rootInActiveWindow ?: return null
        var found: AccessibilityNodeInfo? = null
        walk(frame, 0) { node ->
            if (found != null) return@walk
            val text = (node.text ?: node.contentDescription)?.toString()?.lowercase().orEmpty()
            if (text.isNotEmpty() && text.contains(wanted)) found = node
        }
        return found
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = firstEditable(child, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        if (node.packageName?.toString() != packageName) visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun quickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** Captures the current screen as a small JPEG, base64-encoded, for vision. */
    fun screenshotBase64(maxWidth: Int = 720): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var result: String? = null
        try {
            takeScreenshot(0, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        if (bitmap != null) {
                            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            buffer.close()
                            val scaled = if (software.width > maxWidth) {
                                val ratio = maxWidth.toFloat() / software.width
                                Bitmap.createScaledBitmap(software, maxWidth, (software.height * ratio).toInt(), true)
                            } else {
                                software
                            }
                            val stream = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                            if (scaled !== software) scaled.recycle()
                            software.recycle()
                            result = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        } else {
                            buffer.close()
                        }
                    } catch (e: Exception) {
                        result = null
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
