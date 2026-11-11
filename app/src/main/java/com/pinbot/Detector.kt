package com.pinbot

import java.nio.ByteBuffer
import kotlin.math.*

class Snap(val t: Long, val occ: BooleanArray)

/**
 * Reads a ring of pixels around the coin (where stuck pins sit), tracks how fast
 * it rotates, and decides whether the landing slot (6 o'clock) will be clear
 * when a pin fired now arrives.
 */
class Detector {
    companion object {
        const val BINS = 720   // 0.5 degree per bin
        const val MAXS = 100   // max shift searched (bins)
        const val DOWN = 180   // bin index of 90 degrees = straight down on screen
    }

    private val cosT = DoubleArray(BINS) { cos(Math.toRadians(it * 360.0 / BINS)) }
    private val sinT = DoubleArray(BINS) { sin(Math.toRadians(it * 360.0 / BINS)) }
    private val radii = doubleArrayOf(1.12, 1.24, 1.36) // x coin radius, where pins stick out
    val refR = 1.62                                      // background reference ring
    private val hist = ArrayList<Snap>()
    var omega = 0.0 // bins per second (signed)
    private var haveOmega = false

    fun reset() { hist.clear(); omega = 0.0; haveOmega = false }

    fun px(buf: ByteBuffer, rs: Int, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x = fx.roundToInt(); val y = fy.roundToInt()
        if (x < 0 || y < 0 || x >= w || y >= h) return -1
        val i = y * rs + x * 4
        return ((buf.get(i).toInt() and 255) shl 16) or
                ((buf.get(i + 1).toInt() and 255) shl 8) or
                (buf.get(i + 2).toInt() and 255)
    }

    private fun chroma(c: Int): Int {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return g - (r + b) / 2
    }

    private fun differs(p: Int, ref: Int, thr: Int, cthr: Double): Boolean {
        val dr = abs(((p shr 16) and 255) - ((ref shr 16) and 255))
        val dg = abs(((p shr 8) and 255) - ((ref shr 8) and 255))
        val db = abs((p and 255) - (ref and 255))
        if (dr + dg + db > thr) return true
        return abs(chroma(p) - chroma(ref)) > cthr
    }

    fun profile(buf: ByteBuffer, rs: Int, w: Int, h: Int, cx: Double, cy: Double, R: Double, thr: Int): BooleanArray {
        val occ = BooleanArray(BINS)
        val cthr = thr * 0.36
        for (b in 0 until BINS) {
            val ca = cosT[b]; val sa = sinT[b]
            val ref = px(buf, rs, w, h, cx + refR * R * ca, cy + refR * R * sa)
            if (ref < 0) continue
            for (k in radii) {
                val p = px(buf, rs, w, h, cx + k * R * ca, cy + k * R * sa)
                if (p < 0) continue
                if (differs(p, ref, thr, cthr)) { occ[b] = true; break }
            }
        }
        return occ
    }

    private fun gold(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r > 190 && g > 140 && b < 170 && r - b > 60
    }

    fun playing(buf: ByteBuffer, rs: Int, w: Int, h: Int, cx: Double, cy: Double, R: Double): Boolean {
        var n = 0
        for (k in 0 until 8) {
            val a = k * PI / 4
            val c = px(buf, rs, w, h, cx + 0.8 * R * cos(a), cy + 0.8 * R * sin(a))
            if (c >= 0 && gold(c)) n++
        }
        return n >= 5
    }

    fun startButton(buf: ByteBuffer, rs: Int, w: Int, h: Int): Boolean {
        fun purple(c: Int): Boolean {
            if (c < 0) return false
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r in 90..175 && g in 55..135 && b > 190
        }
        return purple(px(buf, rs, w, h, w * 0.33, h * 0.73)) && purple(px(buf, rs, w, h, w * 0.67, h * 0.73))
    }

    /** Track rotation speed by matching the pin pattern against a ~100ms-old frame. */
    fun update(t: Long, occ: BooleanArray) {
        hist.add(Snap(t, occ))
        while (hist.size > 1 && t - hist[0].t > 600) hist.removeAt(0)
        var ref: Snap? = null
        for (i in hist.indices.reversed()) { if (t - hist[i].t >= 90) { ref = hist[i]; break } }
        if (ref == null) return
        val dt = (t - ref.t) / 1000.0
        var cn = 0; var cp = 0
        for (i in 0..360) { if (occ[i]) cn++; if (ref.occ[i]) cp++ }
        if (cn < 3 || cp < 3) return
        val expected = (omega * dt).roundToInt()
        var best = Int.MIN_VALUE; var bestS = 0; var bestSc = 0
        for (s in -MAXS..MAXS) {
            var sc = 0
            for (i in 0..360) {
                val j = i - s
                if (j < 0 || j >= BINS) continue
                if (occ[i] && ref.occ[j]) sc++
            }
            val adj = sc * 10 - abs(s - expected)
            if (adj > best) { best = adj; bestS = s; bestSc = sc }
        }
        if (bestSc >= 0.5 * min(cn, cp)) {
            val w = bestS / dt
            omega = if (haveOmega) 0.6 * omega + 0.4 * w else w
            haveOmega = true
        }
    }

    /** True if no stuck pin will be inside the landing slot when a pin fired now arrives. */
    fun clear(occ: BooleanArray, latencyMs: Double, hwDeg: Double): Boolean {
        val shift = omega * latencyMs / 1000.0
        val m = abs(omega) * 0.04 + 1.0
        val hwB = hwDeg * 2.0
        val c = DOWN - shift
        val lo = floor(c - hwB - m).toInt()
        val hi = ceil(c + hwB + m).toInt()
        for (j in lo..hi) {
            val jj = ((j % BINS) + BINS) % BINS
            if (occ[jj]) return false
        }
        return true
    }
}
