package com.pinbot

import java.nio.ByteBuffer
import kotlin.math.*

class Snap(val t: Long, val occ: BooleanArray)

class ShotDecision(
    val canShoot: Boolean,
    val clearanceDeg: Double,
    val gapWidthDeg: Double,
    val distToCenterDeg: Double,
    val reason: String
)

class Detector {
    companion object {
        const val BINS = 720     // 0.5 degree per bin
        const val MAXS = 110     // max rotation shift searched (bins)
        const val DOWN = 180     // bin of 90 degrees = straight down (landing slot)
        const val MASK_LO = 490  // 245..295 degrees = top, where score number sits
        const val MASK_HI = 590
    }

    private val cosT = DoubleArray(BINS) { cos(Math.toRadians(it * 360.0 / BINS)) }
    private val sinT = DoubleArray(BINS) { sin(Math.toRadians(it * 360.0 / BINS)) }
    
    // 7 radii spanning the entire arrow structure (arrowhead -> shaft -> fletching)
    val radii = doubleArrayOf(1.06, 1.12, 1.18, 1.25, 1.33, 1.42, 1.52)
    val refR = 1.62 // clean background disc reference ring
    
    private val hist = ArrayList<Snap>()
    private val recent = ArrayList<Double>()
    private var haveOmega = false
    private var outliers = 0
    var omega = 0.0          // bins per second, signed
    var lastGoodMs = 0L

    // Auto-calibration results
    var calibCx = -1.0
    var calibCy = -1.0
    var calibR = -1.0

    fun reset() {
        hist.clear(); recent.clear(); omega = 0.0; haveOmega = false; outliers = 0; lastGoodMs = 0
    }
    fun clearHistory() { hist.clear() }
    fun masked(i: Int) = i in MASK_LO..MASK_HI

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

    /** Auto-calibrate coin center and radius from the gold coin circle. */
    fun calibrate(buf: ByteBuffer, rs: Int, w: Int, h: Int, defCx: Double, defCy: Double, defR: Double) {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var count = 0

        // Step by 4 pixels across middle region of screen
        val yStart = (h * 0.35).toInt(); val yEnd = (h * 0.60).toInt()
        val xStart = (w * 0.20).toInt(); val xEnd = (w * 0.80).toInt()
        for (y in yStart..yEnd step 4) {
            for (x in xStart..xEnd step 4) {
                val c = px(buf, rs, w, h, x.toDouble(), y.toDouble())
                if (c >= 0 && gold(c)) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    count++
                }
            }
        }
        if (count >= 50 && maxX > minX && maxY > minY) {
            val detectedR = (maxX - minX) / 2.0
            if (detectedR in (w * 0.20)..(w * 0.35)) {
                calibCx = (minX + maxX) / 2.0
                calibCy = (minY + maxY) / 2.0
                calibR = detectedR
                return
            }
        }
        // Fallback to config
        calibCx = defCx
        calibCy = defCy
        calibR = defR
    }

    /** Multi-radius occupancy scan. Detects arrowheads, shafts, and fletching reliably. */
    fun profile(buf: ByteBuffer, rs: Int, w: Int, h: Int, cx: Double, cy: Double, R: Double, thr: Int): BooleanArray {
        val raw = BooleanArray(BINS)
        val cthr = thr * 0.35

        for (b in 0 until BINS) {
            val ca = cosT[b]; val sa = sinT[b]
            val ref = px(buf, rs, w, h, cx + refR * R * ca, cy + refR * R * sa)
            if (ref < 0) continue

            var hits = 0
            var arrowheadHit = false

            for (idx in radii.indices) {
                val k = radii[idx]
                val p = px(buf, rs, w, h, cx + k * R * ca, cy + k * R * sa)
                if (p >= 0 && differs(p, ref, thr, cthr)) {
                    hits++
                    if (idx <= 1) arrowheadHit = true // Arrowhead near coin rim
                }
            }
            // Mark occupied if at least 2 hits OR a clear arrowhead hit at coin rim
            raw[b] = (hits >= 2) || (arrowheadHit && hits >= 1)
        }

        // Run-length filter: keep features that span >= 2 bins (1.0 degree), drop 1-bin noise
        val out = BooleanArray(BINS)
        var start = -1
        for (k in 0 until BINS) if (!raw[k]) { start = k; break }
        if (start < 0) { java.util.Arrays.fill(out, true); return out }

        var run = 0
        for (n in 1..BINS) {
            val k = (start + n) % BINS
            if (raw[k]) {
                run++
            } else {
                if (run >= 2) {
                    for (q in 1..run) out[(k - q + BINS) % BINS] = true
                }
                run = 0
            }
        }

        // Dilation by 1 bin on each side for safety cushion
        val d = BooleanArray(BINS)
        for (j in 0 until BINS) {
            d[j] = out[j] || out[(j + 1) % BINS] || out[(j + BINS - 1) % BINS]
        }
        return d
    }

    private fun gold(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r > 180 && g > 135 && b < 165 && (r - b) > 45
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

    /** Rotation speed from matching pin pattern against a ~200ms older frame. */
    fun update(tMs: Long, occ: BooleanArray) {
        hist.add(Snap(tMs, occ))
        while (hist.size > 1 && tMs - hist[0].t > 900) hist.removeAt(0)
        var ref: Snap? = null
        var bestGap = Long.MAX_VALUE
        for (s in hist) {
            val age = tMs - s.t
            if (age < 110 || age > 600) continue
            val g = abs(age - 200)
            if (g < bestGap) { bestGap = g; ref = s }
        }
        if (ref == null) return
        val dt = (tMs - ref.t) / 1000.0
        val idx = ArrayList<Int>()
        var cp = 0
        for (i in 0 until BINS) if (!masked(i)) { if (occ[i]) idx.add(i); if (ref.occ[i]) cp++ }
        val cn = idx.size
        if (cn < 4 || cp < 4) return
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
        if (bestSc < 0.5 * min(cn, cp)) return
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
        return mx - mn <= max(15.0, 0.25 * abs(mean))
    }

    /**
     * Evaluates the landing slot at arrival time.
     * Identifies the empty gap and evaluates clearance and distance to gap center.
     */
    fun evaluateShot(occ: BooleanArray, latencyMs: Double, marginDeg: Double): ShotDecision {
        val shift = omega * latencyMs / 1000.0
        val targetCoinBin = ((DOWN - shift) % BINS + BINS) % BINS
        val targetDeg = targetCoinBin * 0.5

        // If target landing point is blocked
        val targetBinIdx = targetCoinBin.roundToInt() % BINS
        if (occ[targetBinIdx]) {
            return ShotDecision(false, 0.0, 0.0, 999.0, "blocked")
        }

        // Measure distance to nearest blocked bin (ignoring top score mask)
        var leftDistBins = BINS / 2
        var rightDistBins = BINS / 2

        for (step in 1..(BINS / 2)) {
            val rightIdx = (targetBinIdx + step) % BINS
            if (!masked(rightIdx) && occ[rightIdx]) {
                rightDistBins = step
                break
            }
        }
        for (step in 1..(BINS / 2)) {
            val leftIdx = ((targetBinIdx - step) % BINS + BINS) % BINS
            if (!masked(leftIdx) && occ[leftIdx]) {
                leftDistBins = step
                break
            }
        }

        val leftClearDeg = leftDistBins * 0.5
        val rightClearDeg = rightDistBins * 0.5
        val clearanceDeg = min(leftClearDeg, rightClearDeg)
        val gapWidthDeg = (leftDistBins + rightDistBins) * 0.5
        val distToCenterDeg = abs(leftClearDeg - rightClearDeg) / 2.0

        // Minimum required gap for 40 pins: at least 2 * marginDeg (e.g. 8.0°)
        val minGapNeeded = marginDeg * 2.0
        val speedBuffer = abs(omega / 2.0) * 0.04
        val minClearance = marginDeg + speedBuffer

        // Safe condition:
        // 1. Clearance on both sides exceeds minClearance
        // 2. OR in tight gaps, target is well-centered in a valid gap
        val safe = (clearanceDeg >= minClearance) || (gapWidthDeg >= minGapNeeded && distToCenterDeg <= 1.5 && clearanceDeg >= marginDeg * 0.9)

        val reason = if (safe) "clear" else "clearance ${clearanceDeg.roundToInt()}° < ${minClearance.roundToInt()}°"
        return ShotDecision(safe, clearanceDeg, gapWidthDeg, distToCenterDeg, reason)
    }
}
