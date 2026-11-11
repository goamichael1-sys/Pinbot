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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var sw: Switch
    private var suppress = false
    private var startRequested = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val fields = LinkedHashMap<String, EditText>()
    private val labels = mapOf(
        "cx" to "Coin centre X (% of screen width)",
        "cy" to "Coin centre Y (% of screen height)",
        "r" to "Coin radius (% of screen width)",
        "latency" to "Starting latency guess, tap to landing (ms, auto-learned)",
        "margin" to "Required free gap around landing spot (degrees, bigger = safer)",
        "minDelay" to "Minimum delay between shots (ms)",
        "jitter" to "Random extra delay added per shot (ms)",
        "thr" to "Pin detect threshold (lower = more sensitive)",
        "tapY" to "Tap position Y (% of screen height)"
    )

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    private fun applySettings() {
        val ed = Bot.prefs(this).edit()
        for ((k, e) in fields) e.text.toString().toFloatOrNull()?.let { ed.putFloat(k, it) }
        ed.apply()
    }

    private fun setSwitch(on: Boolean) { suppress = true; sw.isChecked = on; suppress = false }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val p = Bot.prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 60) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "PinBot"; textSize = 26f })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 20, 0, 20) }
        root.addView(status)

        root.addView(button("Step 1: enable accessibility service (once)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        sw = Switch(this).apply {
            text = "Bot ON / OFF (nothing runs while OFF)"
            textSize = 18f
            setPadding(0, 30, 0, 30)
        }
        sw.setOnCheckedChangeListener { _, on ->
            if (suppress) return@setOnCheckedChangeListener
            if (on) {
                if (TapService.instance == null) {
                    Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    setSwitch(false)
                } else {
                    applySettings()
                    startRequested = System.currentTimeMillis()
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    startActivityForResult(mpm.createScreenCaptureIntent(), 42)
                }
            } else {
                stopService(Intent(this, BotService::class.java))
            }
        }
        root.addView(sw)

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
            applySettings()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Reset to defaults") {
            Bot.prefs(this).edit().clear().apply()
            for ((k, e) in fields) e.setText(Defaults.map[k].toString())
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 42) return
        if (res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, BotService::class.java).putExtra("code", res).putExtra("data", data)
            )
        } else {
            setSwitch(false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            // keep the switch honest (e.g. stopped from the notification)
            if (sw.isChecked != Bot.capturing &&
                (!sw.isChecked || System.currentTimeMillis() - startRequested > 8000)) setSwitch(Bot.capturing)
            status.text = "Accessibility: " + (if (TapService.instance != null) "ON" else "OFF") +
                    "\nBot: " + (if (!Bot.capturing) "OFF" else if (Bot.running) "RUNNING" else "ON, paused") +
                    "\n" + Bot.status
            ui.postDelayed(this, 500)
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
