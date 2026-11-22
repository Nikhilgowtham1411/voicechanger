package com.nikhil.voicechanger

import android.content.Context
import kotlin.math.pow

class Preset(val name: String, val semitones: Float, val formant: Float)

object Params {
    val presets = listOf(
        Preset("Female - Natural", 7f, 1.16f),
        Preset("Female - Soft", 5.5f, 1.10f),
        Preset("Female - High / Young", 9f, 1.24f),
        Preset("Girl", 11f, 1.32f),
        Preset("Male - Deep", -4f, 0.88f),
        Preset("Original (no change)", 0f, 1f),
        Preset("Custom", 0f, 1f)
    )
    const val CUSTOM = 6

    @Volatile var preset = 0
    @Volatile var semitones = 7f
    @Volatile var formant = 1.16f
    @Volatile var micGain = 1f
    @Volatile var outGain = 1f
    @Volatile var gate = true
    @Volatile var noiseSuppress = true
    @Volatile var overlay = false
    @Volatile var effectOn = true
    @Volatile var outputDeviceId = -1
    @Volatile var inputDeviceId = -1
    @Volatile var forceSpeaker = true

    fun ratio(): Float = 2.0.pow(semitones / 12.0).toFloat()

    fun load(c: Context) {
        val p = c.getSharedPreferences("vc", Context.MODE_PRIVATE)
        preset = p.getInt("preset", 0)
        semitones = p.getFloat("semi", 7f)
        formant = p.getFloat("formant", 1.16f)
        micGain = p.getFloat("mic", 1f)
        outGain = p.getFloat("out", 1f)
        gate = p.getBoolean("gate", true)
        noiseSuppress = p.getBoolean("ns", true)
        overlay = p.getBoolean("overlay", false)
        outputDeviceId = p.getInt("dev", -1)
        inputDeviceId = p.getInt("indev", -1)
        forceSpeaker = p.getBoolean("speaker", true)
    }

    fun save(c: Context) {
        c.getSharedPreferences("vc", Context.MODE_PRIVATE).edit()
            .putInt("preset", preset)
            .putFloat("semi", semitones)
            .putFloat("formant", formant)
            .putFloat("mic", micGain)
            .putFloat("out", outGain)
            .putBoolean("gate", gate)
            .putBoolean("ns", noiseSuppress)
            .putBoolean("overlay", overlay)
            .putInt("dev", outputDeviceId)
            .putInt("indev", inputDeviceId)
            .putBoolean("speaker", forceSpeaker)
            .apply()
    }
}
