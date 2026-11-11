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
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class BotService : Service() {
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val det = Detector()
    private val rnd = Random()

    private var fullW = 0
    private var fullH = 0
    private var cfg: Cfg? = null
    private var cfgAt = 0L

    // shooting state
    private var wasRunning = false
    private var lastTap = 0L
    private var lastStartTap = 0L
    private var nextDelay = 1500.0
    private var effLat = -1.0
    private var lastCfgLat = -1.0
    private var okFrames = 0
    private var unverified = 0

    // landing watch
    private var watching = false
    private var tTap = 0L
    private var preOcc: BooleanArray? = null
    private var preOmega = 0.0
    private var hitFrames = 0
    private var firstHit = 0L

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
            .setContentTitle("PinBot is ON")
            .setContentText("Floating button = run / pause. Tap Stop to switch off.")
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
        Bot.running = false
        Bot.fired = 0; Bot.landed = 0
        Bot.status = "ON - press the floating button to start"
        TapService.instance?.showButton()
    }

    private fun tapAt(fx: Double, fy: Double, dur: Long): Boolean {
        val s = TapService.instance ?: run { Bot.status = "accessibility service is OFF"; return false }
        s.tap((fx * fullW).toFloat(), (fy * fullH).toFloat(), dur)
        return true
    }

    private fun wb(j: Int) = ((j % Detector.BINS) + Detector.BINS) % Detector.BINS

    private fun resetState(now: Long) {
        det.reset()
        watching = false
        okFrames = 0
        lastTap = now
        nextDelay = 1500.0
    }

    private fun process(img: Image, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        val ft = if (img.timestamp > 0) img.timestamp / 1_000_000L else now
        if (cfg == null || now - cfgAt > 500) { cfg = loadCfg(Bot.prefs(this)); cfgAt = now }
        val c = cfg!!
        if (c.latency != lastCfgLat) { effLat = c.latency; lastCfgLat = c.latency }

        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val cx = w * c.cx / 100.0
        val cy = h * c.cy / 100.0
        val R = w * c.r / 100.0

        if (Bot.debugRequest) { Bot.debugRequest = false; saveDebug(buf, rs, w, h, cx, cy, R, c) }

        if (!Bot.running) {
            wasRunning = false
            resetState(now)
            if (!Bot.debugStatusHold()) Bot.status = "ON - paused (press the floating button)"
            return
        }
        if (!wasRunning) { wasRunning = true; unverified = 0; Bot.fired = 0; Bot.landed = 0; resetState(now) }

        if (!det.playing(buf, rs, w, h, cx, cy, R)) {
            resetState(now)
            if (det.startButton(buf, rs, w, h) && now - lastStartTap > 2500) {
                lastStartTap = now
                val jx = (rnd.nextDouble() - 0.5) * 0.06
                if (tapAt(0.5 + jx, 0.73, 40L + rnd.nextInt(40))) Bot.status = "tapped 'Tap to play'"
            } else if (now - lastStartTap > 2500) {
                Bot.status = "waiting - not on the game screen"
            }
            return
        }

        val occ = det.profile(buf, rs, w, h, cx, cy, R, c.thr)

        if (watching) { watchLanding(occ, now); return }

        det.update(ft, occ)

        val since = now - lastTap
        val clr = det.clearance(occ, effLat)
        val need = c.hw + abs(det.omega / 2.0) * 0.05
        val pins = det.pinBins(occ)
        val estOk = pins < 6 || (det.stable() && ft - det.lastGoodMs < 600)
        val good = clr >= need && estOk
        okFrames = if (good) okFrames + 1 else 0

        val patient = since > nextDelay + 20000 && clr >= need  // fallback if speed never looks stable
        if (since >= nextDelay && (okFrames >= 2 || patient)) {
            val jx = (rnd.nextDouble() - 0.5) * 0.08
            val jy = (rnd.nextDouble() - 0.5) * 0.04
            if (tapAt(0.5 + jx, c.tapY / 100.0 + jy, 35L + rnd.nextInt(45))) {
                lastTap = now
                Bot.fired++
                nextDelay = c.minDelay + rnd.nextDouble() * c.jitter
                watching = true
                tTap = now
                preOcc = occ.copyOf()
                preOmega = det.omega
                hitFrames = 0
                okFrames = 0
            }
            return
        }

        val info = "speed %.0f deg/s  lat %.0f ms  shots %d/%d".format(det.omega / 2.0, effLat, Bot.landed, Bot.fired)
        Bot.status = if (since < nextDelay)
            "next shot in %.1fs | %s".format((nextDelay - since) / 1000.0, info)
        else
            "waiting for a safe gap (%.0f deg, need %.0f%s) | %s".format(clr, need, if (estOk) "" else ", speed unsure", info)
    }

    /** After firing: look for the new pin near the landing slot, learn the real latency. */
    private fun watchLanding(occ: BooleanArray, now: Long) {
        val el = now - tTap
        val pre = preOcc ?: run { watching = false; return }
        val shift = (preOmega * el / 1000.0).roundToInt()
        var cnt = 0
        for (j in Detector.DOWN - 24..Detector.DOWN + 24) {
            if (!occ[wb(j)]) continue
            var expected = false
            for (d in -6..6) if (pre[wb(j - shift + d)]) { expected = true; break }
            if (!expected) cnt++
        }
        if (el > 50 && cnt >= 4) { if (hitFrames == 0) firstHit = now; hitFrames++ } else hitFrames = 0

        if (hitFrames >= 2) {
            val meas = (firstHit - tTap) + 15.0
            if (meas in 80.0..700.0) effLat = 0.6 * effLat + 0.4 * meas
            Bot.landed++
            unverified = 0
            watching = false
            det.clearHistory()
        } else if (el > 900) {
            watching = false
            unverified++
            det.clearHistory()
            if (unverified >= 3) {
                Bot.running = false
                Bot.status = "paused: 3 shots in a row not seen landing"
                TapService.instance?.sync()
            }
        }
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
        val occ = det.profile(buf, rs, w, h, cx, cy, R, c.thr)
        p.style = Paint.Style.FILL; p.color = Color.YELLOW
        for (b in 0 until Detector.BINS) if (occ[b]) {
            val a = Math.toRadians(b * 360.0 / Detector.BINS)
            cv.drawCircle((cx + 1.24 * R * cos(a)).toFloat(), (cy + 1.24 * R * sin(a)).toFloat(), 2.5f, p)
        }
        p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = 3f
        for (sgn in intArrayOf(-1, 1)) {
            val a = Math.toRadians(90.0 + sgn * c.hw)
            cv.drawLine(
                (cx + 1.0 * R * cos(a)).toFloat(), (cy + 1.0 * R * sin(a)).toFloat(),
                (cx + 1.62 * R * cos(a)).toFloat(), (cy + 1.62 * R * sin(a)).toFloat(), p
            )
        }
        val tp = Paint().apply { color = Color.BLACK; textSize = 22f }
        cv.drawText("speed=" + (det.omega / 2.0).roundToInt() + " deg/s  lat=" + effLat.roundToInt() + " ms", 10f, 30f, tp)
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
        Bot.status = "off"
        TapService.instance?.hideButton()
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { mp?.stop() } catch (_: Throwable) {}
        thread?.quitSafely()
        super.onDestroy()
    }
}
