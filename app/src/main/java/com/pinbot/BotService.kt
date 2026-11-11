package com.pinbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class BotService : Service() {
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val det = Detector()
    private var lastTap = 0L
    private var lastStartTap = 0L
    private var fullW = 0
    private var fullH = 0
    private var cfg: Cfg? = null
    private var cfgAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "PinBot", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "bot")
            .setContentTitle("PinBot capture active")
            .setContentText("Use the floating button to run / pause")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stopPi
                ).build()
            )
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(code, data)
        mp = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        fullW = b.width(); fullH = b.height()
        val w = fullW / 2
        val h = fullH / 2

        val t = HandlerThread("pinbot-cap").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage()
            if (img != null) {
                try { process(img, w, h) }
                catch (e: Throwable) { Bot.status = "error: " + e.message }
                finally { img.close() }
            }
        }, handler)
        vd = proj.createVirtualDisplay(
            "pinbot", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        Bot.capturing = true
        Bot.status = "capture on - press the floating button to run"
    }

    private fun tapAt(fx: Double, fy: Double): Boolean {
        val s = TapService.instance ?: run { Bot.status = "accessibility service is OFF"; return false }
        s.tap((fx * fullW).toFloat(), (fy * fullH).toFloat())
        return true
    }

    private fun process(img: Image, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        if (cfg == null || now - cfgAt > 500) { cfg = loadCfg(Bot.prefs(this)); cfgAt = now }
        val c = cfg!!
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val cx = w * c.cx / 100.0
        val cy = h * c.cy / 100.0
        val R = w * c.r / 100.0

        if (Bot.debugRequest) { Bot.debugRequest = false; saveDebug(buf, rs, w, h, cx, cy, R, c) }
        if (!Bot.running) { det.reset(); Bot.status = "paused"; return }

        if (!det.playing(buf, rs, w, h, cx, cy, R)) {
            det.reset()
            if (det.startButton(buf, rs, w, h) && now - lastStartTap > 2500) {
                lastStartTap = now
                if (tapAt(0.5, 0.73)) Bot.status = "tapped 'Tap to play'"
            } else if (now - lastStartTap > 2500) {
                Bot.status = "waiting - not on the game screen"
            }
            return
        }

        // pin is in flight / just landed: don't look at the ring now
        if (now - lastTap < c.latency + 60) return

        val occ = det.profile(buf, rs, w, h, cx, cy, R, c.thr)
        det.update(now, occ)
        if (now - lastTap >= c.cooldown && det.clear(occ, c.latency, c.hw)) {
            if (tapAt(0.5, c.tapY / 100.0)) { lastTap = now; Bot.taps++ }
        }
        Bot.status = "RUNNING  speed=%.0f deg/s  shots=%d".format(det.omega / 2.0, Bot.taps)
    }

    private fun saveDebug(buf: ByteBuffer, rs: Int, w: Int, h: Int, cx: Double, cy: Double, R: Double, c: Cfg) {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * rs + x * 4
            px[y * w + x] = (0xFF shl 24) or ((buf.get(i).toInt() and 255) shl 16) or
                    ((buf.get(i + 1).toInt() and 255) shl 8) or (buf.get(i + 2).toInt() and 255)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val cv = Canvas(bmp)
        val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
        p.color = Color.GREEN; cv.drawCircle(cx.toFloat(), cy.toFloat(), R.toFloat(), p)
        p.color = Color.BLUE
        for (k in doubleArrayOf(1.12, 1.36)) cv.drawCircle(cx.toFloat(), cy.toFloat(), (R * k).toFloat(), p)
        p.color = Color.RED; cv.drawCircle(cx.toFloat(), cy.toFloat(), (R * det.refR).toFloat(), p)
        // occupied bins
        val occ = det.profile(buf, rs, w, h, cx, cy, R, c.thr)
        p.style = Paint.Style.FILL; p.color = Color.YELLOW
        for (b in 0 until Detector.BINS) if (occ[b]) {
            val a = Math.toRadians(b * 360.0 / Detector.BINS)
            cv.drawCircle((cx + 1.24 * R * cos(a)).toFloat(), (cy + 1.24 * R * sin(a)).toFloat(), 2.5f, p)
        }
        // landing slot
        p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = 3f
        for (sgn in intArrayOf(-1, 1)) {
            val a = Math.toRadians(90.0 + sgn * c.hw)
            cv.drawLine(
                (cx + 1.0 * R * cos(a)).toFloat(), (cy + 1.0 * R * sin(a)).toFloat(),
                (cx + 1.62 * R * cos(a)).toFloat(), (cy + 1.62 * R * sin(a)).toFloat(), p
            )
        }
        val cvv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pinbot_debug_" + System.currentTimeMillis() + ".png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinBot")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvv) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Bot.status = "debug image saved to Pictures/PinBot"
    }

    override fun onDestroy() {
        Bot.running = false
        Bot.capturing = false
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { mp?.stop() } catch (_: Throwable) {}
        thread?.quitSafely()
        super.onDestroy()
    }
}
