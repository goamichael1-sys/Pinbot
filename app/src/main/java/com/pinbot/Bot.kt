package com.pinbot

import android.content.Context
import android.content.SharedPreferences

object Bot {
    @Volatile var running = false     // shooting on/off (floating button)
    @Volatile var capturing = false   // master switch: capture + overlay active
    @Volatile var debugRequest = false
    @Volatile var status = "off"
    @Volatile var fired = 0
    @Volatile var landed = 0
    fun debugStatusHold(): Boolean = status.startsWith("debug image")
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
}

object Defaults {
    val map = linkedMapOf(
        "cx" to 50f,         // coin centre X, % of width (auto-calibrated)
        "cy" to 46.8f,       // coin centre Y, % of height (auto-calibrated)
        "r" to 27f,          // coin radius, % of width (auto-calibrated)
        "latency" to 180f,   // starting guess: ms from tap to pin landing (auto-learned)
        "margin" to 4.0f,    // required safety gap half-width in degrees (4.0° = 8.0° full gap, fits 40 pins)
        "minDelay" to 450f,  // min ms between shots (fast human pacing)
        "jitter" to 250f,    // random extra ms added per shot
        "thr" to 40f,        // pin detect threshold (lower = more sensitive)
        "tapY" to 78f        // where to tap, % of height (play area below coin)
    )
}

class Cfg(
    val cx: Double, val cy: Double, val r: Double,
    val latency: Double, val hw: Double,
    val minDelay: Double, val jitter: Double,
    val thr: Int, val tapY: Double
)

fun loadCfg(p: SharedPreferences): Cfg {
    fun g(k: String) = p.getFloat(k, Defaults.map[k]!!).toDouble()
    return Cfg(g("cx"), g("cy"), g("r"), g("latency"), g("margin"), g("minDelay"), g("jitter"), g("thr").toInt(), g("tapY"))
}
