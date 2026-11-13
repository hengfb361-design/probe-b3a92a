package com.example.aiphone

import android.app.NotificationChannel
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/** The panel subscribes here; every agent line shows in the overlay. */
object AgentLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun emit(line: String) {
        sink?.invoke(line)
    }
}

/** The conversation the model sees, kept between commands. */
object AgentChat {
    private val turns = mutableListOf<Pair<String, String>>()

    fun clear() {
        synchronized(turns) { turns.clear() }
    }

    fun add(role: String, text: String) {
        if (text.isBlank()) return
        synchronized(turns) {
            turns.add(Pair(role, text))
            while (turns.size > 16) turns.removeAt(0)
        }
    }

    fun snapshot(): List<Pair<String, String>> = synchronized(turns) { turns.toList() }
}

data class Step(val action: String, val target: String, val count: Int)

private class Plan(val say: String, val steps: List<Step>)

/** Chat with the model and carry out the actions it picks. */
object AgentRunner {
    @Volatile
    private var busy = false
    @Volatile
    private var lastScreenshot: String? = null

    private const val MAX_ROUNDS = 8
    private const val CHANNEL = "ai_phone_agent"

    private val TIKTOK = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go"
    )

    private val ACTION_LIST =
        "open: ឈ្មោះ app ឬ package · url: តំណ http · tap: អត្ថបទលើអេក្រង់ · " +
            "tapxy: \"x,y\" · type: អត្ថបទត្រូវវាយ · enter: ចុចស្វែងរក/ផ្ញើ · " +
            "scroll: down ឬ up (count) · back · home · recents · notifications · " +
            "quick: ផ្ទាំងកំណត់រហ័ស · read: អានអេក្រង់ + ថតរូបអេក្រង់ · wait: វិនាទី · " +
            "notify: សារជូនដំណឹង · volume: up/down/mute (count) · " +
            "settings: wifi/bluetooth/data/sound/display/battery/apps/accessibility/location · " +
            "flash: on/off · dnd: on/off · ring: silent/vibrate/normal · brightness: 0-255/up/down · " +
            "lock · panel: wifi/bt/internet · info (ថ្ម wifi bt storage ram app) · " +
            "call: លេខ · sms: \"លេខ|សារ\" · search: ពាក្យ · alarm: \"HH:MM\" · " +
            "map: ទីកន្លែង · camera"

    private val SYSTEM_PROMPT =
        "អ្នកគឺជាជំនួយការ AI នៅក្នុងទូរស័ព្ទ Android។ អ្នកសន្ទនាជាភាសាខ្មែរ ហើយបញ្ជាទូរស័ព្ទ " +
            "ជំនួសអ្នកប្រើ។ ឆ្លើយជា JSON តែមួយប៉ុណ្ណោះ គ្មាន Markdown គ្មានអត្ថបទក្រៅ JSON:\n" +
            "{\"say\":\"ចម្លើយខ្លីជាខ្មែរ\",\"steps\":[{\"action\":\"open\",\"target\":\"tiktok\"}, " +
            "{\"action\":\"scroll\",\"target\":\"down\",\"count\":20}]}\n" +
            "say = អ្វីដែលអ្នកនិយាយទៅអ្នកប្រើ។ steps = សកម្មភាពតាមលំដាប់ 0–8។ " +
            "បើគ្រាន់តែឆ្លើយសំណួរ ទុក steps ទទេ។\n" +
            "សកម្មភាពដែលមាន — " + ACTION_LIST + "\n" +
            "ច្បាប់:\n" +
            "1. បើមិនដឹងអ្វីកំពុងបង្ហាញលើអេក្រង់ ដាក់ {\"action\":\"read\"} ជាជំហានដំបូង រួចរង់ចាំ OBSERVATION។\n" +
            "2. កុំប្រឌិតកូអរដោណេ — ប្រើ tap ជាមួយអត្ថបទដែលឃើញ។\n" +
            "2b. tapxy: \"x,y\" ប្រើพิกัดพิกเซล (screen W×H បានផ្តល់ក្នុង OBSERVATION) ដើម្បីចុច " +
                "ត្រង់កន្លែងដែលឃើញក្នុង screenshot ពេលគ្មានអត្ថបទច្បាស់។\n" +
            "3. ឧទាហរណ៍ «បើក tiktok អូស 20» → open tiktok + scroll down 20។\n" +
            "4. បើសិទ្ធិខ្វះ ឬធ្វើមិនបាន ប្រាប់ត្រង់ៗក្នុង say។\n" +
            "5. បើត្រូវមើលស្ថានភាពទូរស័ព្ទ (ថ្ម, wifi, bluetooth, storage, ram, app បច្ចុប្បន្ន) ដាក់ {\"action\":\"info\"}។\n" +
            "6. បើត្រូវប្តូរ brightness/flash/dnd/ring ឬ lock អេក្រង់ ឬបើក panel ប្រព័ន្ធ ប្រើ action ពិសេស flash/dnd/ring/brightness/lock/panel."

    fun run(context: Context, command: String, log: (String) -> Unit) {
        if (busy) {
            log("⏳ ការងារមួយកំពុងរត់ — រង់ចាំវាចប់សិន")
            return
        }
        busy = true
        val app = context.applicationContext
        Thread {
            try {
                execute(app, command.trim(), log)
            } catch (e: Exception) {
                log("✖ " + (e.message ?: "បរាជ័យ"))
            } finally {
                busy = false
            }
        }.start()
    }

    private fun execute(context: Context, command: String, log: (String) -> Unit) {
        val service = AgentAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility មិនទាន់បើក — Settings › Accessibility")
        val size = service.screenSize()
        log("📐 " + size.first + "×" + size.second + " px")
        AgentChat.add("user", command)

        if (looksLikeTokens(command)) {
            val steps = parseTokens(command)
            log("⚡ បញ្ជាបន្ទាត់ " + steps.size + " ជំហាន")
            play(context, service, steps, log)
            log("✔ ចប់")
            return
        }

        var observation = ""
        var round = 0
        while (round < MAX_ROUNDS) {
            round += 1
            log(if (round == 1) "🧠 កំពុងគិត…" else "🔁 មើលអេក្រង់បន្ថែម…")
            val messages = mutableListOf(Pair("system", SYSTEM_PROMPT))
            messages.addAll(AgentChat.snapshot())
            if (observation.isNotEmpty()) {
                messages.add(Pair("user", "OBSERVATION (អេក្រង់, screen=" + size.first + "x" + size.second + "): " + observation +
                    "\nបន្តបើនៅសល់ ឬឆ្លើយខ្លីៗបើរួចហើយ។ (screenshot attached)"))
            }
            val vision = lastScreenshot
            lastScreenshot = null
            val reply = GroqClient.chat(context, messages, log, vision)
            if (!reply.ok) {
                log("✖ AI: " + reply.text)
                return
            }
            val plan = parsePlan(reply.text)
            if (plan.say.isNotEmpty()) {
                log("🤖 " + plan.say)
            } else if (plan.steps.isEmpty()) {
                log("🤖 " + reply.text.replace("\n", " ").take(400))
            }
            AgentChat.add("assistant", plan.say.ifEmpty { reply.text.take(400) })
            if (plan.steps.isEmpty()) {
                log("✔ ចប់")
                return
            }
            observation = play(context, service, plan.steps, log)
            if (observation.isEmpty()) {
                log("✔ ចប់")
                return
            }
        }
        log("✔ ចប់ (អតិបរមា " + MAX_ROUNDS + " ជុំ)")
    }

    /** Runs the steps; the screen text is returned when one of them reads the screen. */
    private fun play(context: Context, service: AgentAccessibilityService, steps: List<Step>, log: (String) -> Unit): String {
        var seen = ""
        for (step in steps) {
            when (step.action) {
                "open" -> {
                    val pkg = resolve(context, step.target)
                    if (pkg == null) return fail(log, "រកមិនឃើញ app: " + step.target)
                    if (!service.openApp(pkg)) return fail(log, "បើក " + pkg + " មិនបាន")
                    log("📱 បើក " + friendly(context, pkg))
                    Thread.sleep(2600)
                }
                "url" -> {
                    open(context, Intent(Intent.ACTION_VIEW, Uri.parse(step.target)), log)
                    Thread.sleep(2000)
                }
                "tap" -> {
                    if (service.tapText(step.target)) log("👆 " + step.target)
                    else log("✖ រកមិនឃើញ \"" + step.target + "\" លើអេក្រង់")
                    Thread.sleep(1200)
                }
                "tapxy" -> {
                    val parts = step.target.split(",")
                    val x = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val y = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (x == null || y == null) log("✖ កូអរដោណេខុស: " + step.target)
                    else {
                        service.tapAt(x, y)
                        log("👆 " + x + ", " + y)
                        Thread.sleep(1200)
                    }
                }
                "type" -> {
                    if (service.typeText(step.target)) log("⌨ វាយ " + step.target)
                    else log("✖ គ្មានប្រអប់វាយនៅលើអេក្រង់")
                    Thread.sleep(900)
                }
                "enter" -> {
                    if (!service.pressEnter()) log("✖ ចុច enter មិនបាន")
                    Thread.sleep(1200)
                }
                "scroll" -> {
                    val total = step.count.coerceIn(1, 200)
                    val up = step.target.contains("up") || step.target.contains("ឡើង")
                    for (i in 1..total) {
                        val moved = if (up) service.swipeDown() else service.swipeUp()
                        if (!moved) {
                            log("✖ អូសមិនបាន — សិទ្ធិ gesture ត្រូវបានបដិសេធ")
                            break
                        }
                        log((if (up) "⬇ " else "⬆ ") + i + "/" + total)
                        Thread.sleep(1600)
                    }
                }
                "back" -> { service.back(); Thread.sleep(800) }
                "home" -> { service.home(); Thread.sleep(800) }
                "recents" -> { service.recents(); Thread.sleep(800) }
                "notifications" -> { service.notifications(); Thread.sleep(800) }
                "quick" -> { service.quickSettings(); Thread.sleep(800) }
                "read" -> {
                    seen = service.screenText()
                    lastScreenshot = service.screenshotBase64()
                    log("👀 " + (if (seen.isEmpty()) "អេក្រង់ទទេ" else seen.take(200)))
                    if (lastScreenshot != null) log("📸 ថតរូបអេក្រង់")
                }
                "info" -> {
                    seen = systemInfo(context, service)
                    log("ℹ " + seen.take(240))
                }
                "brightness" -> brightness(context, step.target, log)
                "flash" -> flash(context, step.target, log)
                "dnd" -> dnd(context, step.target, log)
                "ring" -> ring(context, step.target, log)
                "lock" -> {
                    if (service.lockScreen()) log("🔒 ចាក់អេក្រង់") else log("✖ lock មិនបាន")
                    Thread.sleep(600)
                }
                "panel" -> panel(context, step.target, log)
                "wait" -> Thread.sleep((step.count.coerceIn(1, 60) * 1000).toLong())
                "notify" -> notifyUser(context, step.target.ifEmpty { "រួចរាល់" })
                "volume" -> volume(context, step.target, step.count)
                "settings" -> open(context, Intent(settingsAction(step.target)), log)
                "call" -> open(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(step.target))), log)
                "sms" -> {
                    val parts = step.target.split("|")
                    val number = digits(parts.getOrNull(0).orEmpty())
                    val body = parts.getOrNull(1)?.trim().orEmpty()
                    open(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number))
                        .putExtra("sms_body", body), log)
                }
                "search" -> {
                    val query = step.target.trim()
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
                    if (!open(context, intent, log)) {
                        open(context, Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))), log)
                    }
                }
                "alarm" -> {
                    val parts = step.target.split(":")
                    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 7
                    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    open(context, Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true), log)
                }
                "map" -> open(context, Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(step.target))), log)
                "camera" -> open(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE), log)
                else -> log("✖ សកម្មភាពមិនស្គាល់: " + step.action)
            }
        }
        // Always feed the model a fresh view of the screen after acting, so it can
        // keep clicking on its own without an explicit "read" step (live control).
        if (seen.isEmpty()) {
            Thread.sleep(700)
            seen = service.screenText()
            if (lastScreenshot == null) lastScreenshot = service.screenshotBase64()
            if (seen.isNotEmpty()) log("👀 " + seen.take(120))
            if (lastScreenshot != null) log("📸 ថតរូបអេក្រង់")
        }
        return seen
    }

    private fun fail(log: (String) -> Unit, message: String): String {
        log("✖ " + message)
        return ""
    }

    private fun open(context: Context, intent: Intent, log: (String) -> Unit): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            log("✖ បើកមិនបាន: " + (e.message ?: "គ្មាន app គាំទ្រ"))
            false
        }
    }

    private fun settingsAction(target: String): String {
        val low = target.lowercase()
        return when {
            low.contains("wifi") || low.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            low.contains("blue") -> Settings.ACTION_BLUETOOTH_SETTINGS
            low.contains("data") -> Settings.ACTION_DATA_ROAMING_SETTINGS
            low.contains("sound") -> Settings.ACTION_SOUND_SETTINGS
            low.contains("display") -> Settings.ACTION_DISPLAY_SETTINGS
            low.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            low.contains("app") -> Settings.ACTION_APPLICATION_SETTINGS
            low.contains("access") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            low.contains("location") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
    }

    @Suppress("DEPRECATION")
    private fun volume(context: Context, target: String, count: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val low = target.lowercase()
        if (low.contains("mute")) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return
        }
        val direction =
            if (low.contains("down") || low.contains("lower")) AudioManager.ADJUST_LOWER
            else AudioManager.ADJUST_RAISE
        for (i in 0 until count.coerceIn(1, 30)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun notifyUser(context: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(CHANNEL, "AI Phone", NotificationManager.IMPORTANCE_DEFAULT)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("AI Phone")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(91, note)
        } catch (e: Exception) {
            AgentLog.emit("✖ ជូនដំណឹងមិនបាន")
        }
    }

    private fun digits(value: String): String = value.filter { it.isDigit() || it == '+' }

    private fun looksLikeTokens(command: String): Boolean =
        Regex("""(?i)\b(OPEN|SCROLL|TAP|TAPXY|TYPE|URL|WAIT|BACK|HOME|READ)\s*:""").containsMatchIn(command)

    private fun parseTokens(command: String): List<Step> {
        val steps = mutableListOf<Step>()
        for (token in command.split(Regex("""\s+"""))) {
            val at = token.indexOf(':')
            if (at <= 0) continue
            val key = token.substring(0, at).uppercase()
            val value = token.substring(at + 1)
            when (key) {
                "OPEN" -> steps.add(Step("open", value, 0))
                "URL" -> steps.add(Step("url", value, 0))
                "TAP" -> steps.add(Step("tap", value, 0))
                "TAPXY" -> steps.add(Step("tapxy", value, 0))
                "TYPE" -> steps.add(Step("type", value, 0))
                "WAIT" -> steps.add(Step("wait", "", value.toIntOrNull() ?: 2))
                "SCROLL" -> steps.add(Step("scroll", "down", value.toIntOrNull() ?: 10))
                "BACK" -> steps.add(Step("back", "", 0))
                "HOME" -> steps.add(Step("home", "", 0))
                "READ" -> steps.add(Step("read", "", 0))
                "INFO" -> steps.add(Step("info", "", 0))
                "BRIGHTNESS" -> steps.add(Step("brightness", value, 0))
                "FLASH" -> steps.add(Step("flash", value, 0))
                "DND" -> steps.add(Step("dnd", value, 0))
                "RING" -> steps.add(Step("ring", value, 0))
                "LOCK" -> steps.add(Step("lock", "", 0))
                "PANEL" -> steps.add(Step("panel", value, 0))
            }
        }
        return steps
    }

    private fun parsePlan(raw: String): Plan {
        var text = raw.trim()
        if (text.contains("```")) {
            text = text.replace("```json", "").replace("```", "").trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Plan(text.take(400), emptyList())
        return try {
            val obj = JSONObject(text.substring(start, end + 1))
            val array = obj.optJSONArray("steps")
            val steps = mutableListOf<Step>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val action = item.optString("action").trim().lowercase()
                    if (action.isEmpty()) continue
                    steps.add(Step(action, item.optString("target"), item.optInt("count", 0)))
                }
            }
            Plan(obj.optString("say").trim(), steps)
        } catch (e: Exception) {
            Plan(text.take(400), emptyList())
        }
    }

    private fun brightness(context: Context, target: String, log: (String) -> Unit) {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (e: Exception) { }
            log("✖ ត្រូវបើកសិទ្ធិ Write settings សិន (system)")
            return
        }
        val resolver = context.contentResolver
        val cur = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val low = target.lowercase()
        val next = when {
            low.contains("max") -> 255
            low.contains("min") -> 10
            low.contains("up") -> (cur + 50).coerceAtMost(255)
            low.contains("down") -> (cur - 50).coerceAtLeast(10)
            else -> target.toIntOrNull()?.coerceIn(0, 255) ?: cur
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        log("🔆 brightness = " + next + "/255")
    }

    private fun flash(context: Context, target: String, log: (String) -> Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (cameraId == null) { log("✖ គ្មាន flash"); return }
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("✖ ត្រូវបើកសិទ្ធិ Camera សិន (Settings › Apps)")
            return
        }
        val on = target.lowercase().contains("on") || target == "1" || target.lowercase().contains("បើក")
        try {
            cm.setTorchMode(cameraId, on)
            log(if (on) "🔦 flash បើក" else "🔦 flash បិទ")
        } catch (e: Exception) {
            log("✖ flash: " + (e.message ?: "បរាជ័យ"))
        }
    }

    private fun dnd(context: Context, target: String, log: (String) -> Unit) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (e: Exception) { }
            log("✖ ត្រូវបើកសិទ្ធិ DND សិន")
            return
        }
        val on = target.lowercase().contains("on") || target.lowercase().contains("បើក")
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
        log(if (on) "🔕 DND បើក" else "🔔 DND បិទ")
    }

    private fun ring(context: Context, target: String, log: (String) -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val low = target.lowercase()
        val mode = when {
            low.contains("silent") || low.contains("mute") -> AudioManager.RINGER_MODE_SILENT
            low.contains("vibr") -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        am.ringerMode = mode
        log("🔔 ringer = " + mode)
    }

    private fun panel(context: Context, target: String, log: (String) -> Unit) {
        val low = target.lowercase()
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29) {
            when {
                low.contains("wifi") -> Intent(Settings.Panel.ACTION_WIFI)
                low.contains("internet") || low.contains("data") -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                low.contains("bt") || low.contains("blue") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                low.contains("nfc") -> Intent(Settings.Panel.ACTION_NFC)
                else -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            }
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent); log("⚙ បើក panel") }
        catch (e: Exception) { log("✖ panel: " + (e.message ?: "បរាជ័យ")) }
    }

    private fun systemInfo(context: Context, service: AgentAccessibilityService): String {
        val parts = mutableListOf<String>()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        parts.add("ថ្ម=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%")
        parts.add(if (bm.isCharging) "កំពុងសាក" else "មិនសាក")
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        parts.add("brightness=" + brightness)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        parts.add("volume=" + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        parts.add("ringer=" + am.ringerMode)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        parts.add("wifi=" + (if (wm.isWifiEnabled) "on" else "off") + " ssid=" + (wm.connectionInfo?.ssid ?: "?"))
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        parts.add("bt=" + (if (bt?.isEnabled == true) "on" else "off"))
        val stat = StatFs(Environment.getDataDirectory().path)
        parts.add("storage=" + (stat.availableBytes / (1024*1024*1024)) + "GB free/" + (stat.totalBytes / (1024*1024*1024)) + "GB")
        val act = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        act.getMemoryInfo(mem)
        parts.add("ram=" + (mem.availMem / (1024*1024)) + "MB free")
        parts.add("app=" + service.currentPackage())
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        parts.add("screen=" + (if (pm.isInteractive) "on" else "off"))
        return parts.joinToString(" · ")
    }
    private fun resolve(context: Context, target: String): String? {
        val query = target.trim().lowercase()
        if (query.isEmpty()) return null
        if (query.contains("tiktok") || query.contains("tik tok")) {
            for (pkg in TIKTOK) if (installed(context, pkg)) return pkg
        }
        if (installed(context, query)) return query
        val pm = context.packageManager
        var best: String? = null
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.packageName == context.packageName) continue
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == query) return app.packageName
            if (best == null && label.contains(query)) best = app.packageName
        }
        return best
    }

    private fun friendly(context: Context, pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: Exception) {
        false
    }
}
