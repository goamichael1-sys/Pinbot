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
        "cx" to 50f,         // coin centre X, % of width
        "cy" to 45.6f,       // coin centre Y, % of height
        "r" to 26.8f,        // coin radius, % of width
        "latency" to 200f,   // starting guess: ms from decision to pin landing (auto-learned)
        "margin" to 6f,      // required free gap around landing spot, degrees
        "minDelay" to 3000f, // min ms between shots
        "jitter" to 800f,    // random extra ms added to each delay
        "thr" to 45f,        // pin detect threshold
        "tapY" to 66f        // where to tap, % of height
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
