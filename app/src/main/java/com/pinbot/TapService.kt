package com.pinbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class TapService : AccessibilityService() {
    companion object { @Volatile var instance: TapService? = null }

    private var wm: WindowManager? = null
    private var btn: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideButton()
        return super.onUnbind(intent)
    }

    /** Taps only work while the master switch (capture) is ON. */
    fun tap(x: Float, y: Float, durMs: Long = 40) {
        if (!Bot.capturing) return
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durMs))
            .build()
        dispatchGesture(g, null, null)
    }

    fun showButton() = main.post {
        if (btn == null) addButton()
        btn?.let { paint(it, Bot.running) }
    }

    fun hideButton() = main.post {
        try { btn?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        btn = null
    }

    fun sync() = main.post { btn?.let { paint(it, Bot.running) } }

    private fun paint(tv: TextView, on: Boolean) {
        tv.text = if (on) "■" else "▶"
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xCCD32F2F.toInt() else 0xCC2E7D32.toInt())
        }
    }

    private fun toggle(tv: TextView) {
        if (!Bot.capturing) {
            Bot.running = false
            paint(tv, false)
            Toast.makeText(this, "Switch the bot ON in the PinBot app first", Toast.LENGTH_SHORT).show()
            return
        }
        Bot.running = !Bot.running
        paint(tv, Bot.running)
    }

    private fun addButton() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
        }
        paint(tv, false)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 300 }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); w.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggle(tv) }
            }
            true
        }
        w.addView(tv, lp)
        btn = tv
    }
}
