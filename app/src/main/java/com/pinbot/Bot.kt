package com.pinbot

import android.content.Context
import android.content.SharedPreferences

object Bot {
    @Volatile var running = false
    @Volatile var capturing = false
    @Volatile var debugRequest = false
    @Volatile var status = "idle"
    @Volatile var taps = 0
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
}

object Defaults {
    val map = linkedMapOf(
        "cx" to 50f,        // coin centre X, % of width
        "cy" to 45.6f,      // coin centre Y, % of height
        "r" to 26.8f,       // coin radius, % of width
        "latency" to 200f,  // ms from tap to pin landing
        "hw" to 4f,         // safety half-width in degrees
        "cooldown" to 250f, // min ms between shots
        "thr" to 45f,       // colour difference threshold
        "tapY" to 66f       // where to tap, % of height
    )
}

class Cfg(
    val cx: Double, val cy: Double, val r: Double,
    val latency: Double, val hw: Double, val cooldown: Double,
    val thr: Int, val tapY: Double
)

fun loadCfg(p: SharedPreferences): Cfg {
    fun g(k: String) = p.getFloat(k, Defaults.map[k]!!).toDouble()
    return Cfg(g("cx"), g("cy"), g("r"), g("latency"), g("hw"), g("cooldown"), g("thr").toInt(), g("tapY"))
}
