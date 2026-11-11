package com.pinbot

import java.nio.ByteBuffer
import kotlin.math.*

class Snap(val t: Long, val occ: BooleanArray)

class Detector {
    companion object {
        const val BINS = 720     // 0.5 degree per bin
        const val MAXS = 110     // max rotation shift searched (bins)
        const val DOWN = 180     // bin of 90 degrees = straight down (landing slot)
        const val MASK_LO = 480  // 240..300 degrees = top, where score number / text sits
        const val MASK_HI = 600
    }

    private val cosT = DoubleArray(BINS) { cos(Math.toRadians(it * 360.0 / BINS)) }
    private val sinT = DoubleArray(BINS) { sin(Math.toRadians(it * 360.0 / BINS)) }
    private val radii = doubleArrayOf(1.12, 1.24, 1.36)
    val refR = 1.62
    private val hist = ArrayList<Snap>()
    private val recent = ArrayList<Double>()
    private var haveOmega = false
    private var outliers = 0
    var omega = 0.0          // bins per second, signed
    var lastGoodMs = 0L

    fun reset() { hist.clear(); recent.clear(); omega = 0.0; haveOmega = false; outliers = 0; lastGoodMs = 0 }
    fun clearHistory() { hist.clear() }
    private fun masked(i: Int) = i in MASK_LO..MASK_HI

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

    /** Occupancy of the pin ring. A bin counts only if >=2 of 3 radii differ from the background
     *  ring, and only runs >= 3 bins wide survive (kills confetti and sparkles). */
    fun profile(buf: ByteBuffer, rs: Int, w: Int, h: Int, cx: Double, cy: Double, R: Double, thr: Int): BooleanArray {
        val raw = BooleanArray(BINS)
        val cthr = thr * 0.36
        for (b in 0 until BINS) {
            val ca = cosT[b]; val sa = sinT[b]
            val ref = px(buf, rs, w, h, cx + refR * R * ca, cy + refR * R * sa)
            if (ref < 0) continue
            var hits = 0
            for (k in radii) {
                val p = px(buf, rs, w, h, cx + k * R * ca, cy + k * R * sa)
                if (p >= 0 && differs(p, ref, thr, cthr)) hits++
            }
            raw[b] = hits >= 2
        }
        // run-length filter (circular)
        val out = BooleanArray(BINS)
        var start = -1
        for (k in 0 until BINS) if (!raw[k]) { start = k; break }
        if (start < 0) { java.util.Arrays.fill(out, true); return out }
        var run = 0
        for (n in 1..BINS) {
            val k = (start + n) % BINS
            if (raw[k]) run++ else {
                if (run >= 3) for (q in 1..run) out[(k - q + BINS) % BINS] = true
                run = 0
            }
        }
        // dilate by one bin
        val d = BooleanArray(BINS)
        for (j in 0 until BINS) d[j] = out[j] || out[(j + 1) % BINS] || out[(j + BINS - 1) % BINS]
        return d
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

    fun pinBins(occ: BooleanArray): Int {
        var n = 0
        for (i in 0 until BINS) if (!masked(i) && occ[i]) n++
        return n
    }

    /** Rotation speed from matching the pin pattern against a ~220 ms older frame. */
    fun update(tMs: Long, occ: BooleanArray) {
        hist.add(Snap(tMs, occ))
        while (hist.size > 1 && tMs - hist[0].t > 900) hist.removeAt(0)
        var ref: Snap? = null
        var bestGap = Long.MAX_VALUE
        for (s in hist) {
            val age = tMs - s.t
            if (age < 110 || age > 600) continue
            val g = abs(age - 220)
            if (g < bestGap) { bestGap = g; ref = s }
        }
        if (ref == null) return
        val dt = (tMs - ref.t) / 1000.0
        val idx = ArrayList<Int>()
        var cp = 0
        for (i in 0 until BINS) if (!masked(i)) { if (occ[i]) idx.add(i); if (ref.occ[i]) cp++ }
        val cn = idx.size
        if (cn < 6 || cp < 6) return
        val expected = (omega * dt).roundToInt()
        var best = Int.MIN_VALUE; var bestS = 0; var bestSc = 0
        for (s in -MAXS..MAXS) {
            var sc = 0
            for (i in idx) {
                val j = ((i - s) % BINS + BINS) % BINS
                if (!masked(j) && ref.occ[j]) sc++
            }
            val adj = sc * 10 - abs(s - expected)
            if (adj > best) { best = adj; bestS = s; bestSc = sc }
        }
        if (bestSc < 0.6 * min(cn, cp)) return
        val w = bestS / dt
        if (haveOmega && abs(w - omega) > max(60.0, 0.5 * abs(omega))) {
            outliers++
            if (outliers < 3) return
            recent.clear(); omega = w
        } else {
            omega = if (haveOmega) 0.7 * omega + 0.3 * w else w
        }
        outliers = 0; haveOmega = true
        recent.add(w); if (recent.size > 6) recent.removeAt(0)
        lastGoodMs = tMs
    }

    fun stable(): Boolean {
        if (recent.size < 3) return false
        val mn = recent.min(); val mx = recent.max(); val mean = recent.average()
        return mx - mn <= max(12.0, 0.2 * abs(mean))
    }

    /** Degrees between the landing slot (at arrival time) and the nearest stuck pin. */
    fun clearance(occ: BooleanArray, latencyMs: Double): Double {
        val c = DOWN - omega * latencyMs / 1000.0
        var best = BINS.toDouble()
        var any = false
        for (j in 0 until BINS) {
            if (!occ[j]) continue
            any = true
            var d = abs(j - c) % BINS
            if (d > BINS / 2.0) d = BINS - d
            if (d < best) best = d
        }
        return if (any) best / 2.0 else 180.0
    }
}
