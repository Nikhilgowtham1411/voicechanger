package com.nikhil.voicechanger

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Saved audio clips (copied into the app) and one shared player for them. */
object Soundboard {
    private var mp: MediaPlayer? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "sb").also { it.mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun label(f: File): String = f.nameWithoutExtension

    fun importClip(ctx: Context, uri: Uri): Boolean {
        return try {
            var name = "clip"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            var out = File(dir(ctx), safe)
            var n = 1
            while (out.exists()) {
                out = File(dir(ctx), "${n}_$safe")
                n++
            }
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun play(ctx: Context, f: File) {
        stop()
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            m.setDataSource(f.absolutePath)
            if (Params.forceSpeaker) {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { m.setPreferredDevice(it) }
            }
            m.setOnCompletionListener { stop() }
            m.prepare()
            m.start()
            mp = m
        } catch (e: Exception) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        mp?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        mp = null
    }
}
