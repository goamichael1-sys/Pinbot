package com.pinbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val fields = LinkedHashMap<String, EditText>()
    private val labels = mapOf(
        "cx" to "Coin centre X (% of screen width)",
        "cy" to "Coin centre Y (% of screen height)",
        "r" to "Coin radius (% of screen width)",
        "latency" to "Tap-to-landing latency (ms)",
        "hw" to "Safety half-width (degrees)",
        "cooldown" to "Min time between shots (ms)",
        "thr" to "Pin detect threshold (lower = more sensitive)",
        "tapY" to "Tap position Y (% of screen height)"
    )

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val p = Bot.prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 60) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "PinBot"; textSize = 26f })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 20, 0, 20) }
        root.addView(status)

        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2. Start screen capture") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
        })
        root.addView(button("Stop capture") {
            startService(Intent(this, BotService::class.java).setAction("stop"))
        })
        root.addView(button("Save debug screenshot (Pictures/PinBot)") { Bot.debugRequest = true })

        root.addView(TextView(this).apply { text = "\nSettings"; textSize = 18f })
        for ((k, d) in Defaults.map) {
            root.addView(TextView(this).apply { text = labels[k] })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(p.getFloat(k, d).toString())
            }
            fields[k] = e
            root.addView(e)
        }
        root.addView(button("Apply settings") {
            val ed = Bot.prefs(this).edit()
            for ((k, e) in fields) e.text.toString().toFloatOrNull()?.let { ed.putFloat(k, it) }
            ed.apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Reset to defaults") {
            Bot.prefs(this).edit().clear().apply()
            for ((k, e) in fields) e.setText(Defaults.map[k].toString())
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, BotService::class.java).putExtra("code", res).putExtra("data", data)
            )
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            status.text = "Accessibility: " + (if (TapService.instance != null) "ON" else "OFF") +
                    "\nCapture: " + (if (Bot.capturing) "ON" else "OFF") +
                    "\nBot: " + (if (Bot.running) "RUNNING" else "paused") +
                    "\n" + Bot.status
            ui.postDelayed(this, 500)
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
