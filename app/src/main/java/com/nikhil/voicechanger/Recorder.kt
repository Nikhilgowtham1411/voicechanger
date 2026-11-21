package com.nikhil.voicechanger

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.FileDescriptor
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun audioDevice(ctx: Context, id: Int, flag: Int): AudioDeviceInfo? {
    if (id < 0) return null
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return am.getDevices(flag).firstOrNull { it.id == id }
}

fun softShort(x: Float): Short {
    var v = x
    val a = abs(v)
    if (a > 0.9f) {
        val over = a - 0.9f
        val soft = 0.9f + 0.1f * (over / (over + 0.1f))
        v = if (v < 0) -soft else soft
    }
    return (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}

class VoiceRecorder(private val ctx: Context) {

    @Volatile var recording = false
    @Volatile var playing = false

    fun start(onFinished: (ShortArray, ShortArray, Int) -> Unit, onError: (String) -> Unit) {
        recording = true
        Thread({ record(onFinished, onError) }, "voice-rec").start()
    }

    fun stop() {
        recording = false
    }

    private fun record(onFinished: (ShortArray, ShortArray, Int) -> Unit, onError: (String) -> Unit) {
        var rate = 0
        for (r in intArrayOf(16000, 44100, 48000)) {
            if (AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
                rate = r
                break
            }
        }
        if (rate == 0) {
            recording = false
            onError("Mic not supported")
            return
        }
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minIn, rate / 5 * 2)
            )
        } catch (e: Exception) {
            recording = false
            onError("Mic permission error")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            recording = false
            onError("Mic busy. Close other apps using mic")
            return
        }
        audioDevice(ctx, Params.inputDeviceId, AudioManager.GET_DEVICES_INPUTS)?.let { rec.setPreferredDevice(it) }

        val dsp = VoiceDsp(rate)
        val frame = rate / 50
        val maxSamples = rate * 180
        var raw = ShortArray(rate * 30)
        var proc = ShortArray(rate * 30)
        var rl = 0
        var pl = 0
        val inS = ShortArray(frame)
        val f = FloatArray(frame)
        val outF = FloatArray(frame + 8192)

        rec.startRecording()
        var flush = 0
        while (true) {
            val n: Int
            if (recording && rl < maxSamples) {
                n = rec.read(inS, 0, frame)
                if (n < 0) break
                if (n == 0) continue
                for (i in 0 until n) f[i] = inS[i] / 32768f
            } else {
                if (flush >= 6) break
                flush++
                n = frame
                for (i in 0 until n) { f[i] = 0f; inS[i] = 0 }
            }
            dsp.ratio = Params.ratio()
            dsp.formant = Params.formant
            dsp.gateOn = Params.gate
            dsp.inGain = Params.micGain
            val m = dsp.process(f, n, outF)
            if (flush == 0) {
                if (rl + n > raw.size) raw = raw.copyOf(max(raw.size * 2, rl + n))
                for (i in 0 until n) raw[rl + i] = inS[i]
                rl += n
            }
            if (pl + m > proc.size) proc = proc.copyOf(max(proc.size * 2, pl + m))
            val og = Params.outGain
            for (i in 0 until m) proc[pl + i] = softShort(outF[i] * og)
            pl += m
        }
        try { rec.stop() } catch (_: Exception) { }
        rec.release()
        recording = false
        onFinished(raw.copyOf(rl), proc.copyOf(pl), rate)
    }

    fun stopPlay() {
        playing = false
    }

    fun play(pcm: ShortArray, rate: Int, onDone: () -> Unit) {
        playing = true
        Thread({
            val minB = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minB, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioDevice(ctx, Params.outputDeviceId, AudioManager.GET_DEVICES_OUTPUTS)?.let { t.setPreferredDevice(it) }
            t.play()
            var pos = 0
            while (playing && pos < pcm.size) {
                val n = min(2048, pcm.size - pos)
                t.write(pcm, pos, n)
                pos += n
            }
            if (playing) {
                try { Thread.sleep(300) } catch (_: Exception) { }
            } else {
                try { t.pause(); t.flush() } catch (_: Exception) { }
            }
            try { t.stop() } catch (_: Exception) { }
            t.release()
            playing = false
            onDone()
        }, "voice-play").start()
    }

    /** Saves AAC .m4a into Music/VoiceChanger and returns its Uri (Android 10+). */
    fun saveM4a(pcm: ShortArray, rate: Int): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val name = "VoiceNote_" + System.currentTimeMillis() + ".m4a"
            val values = ContentValues()
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VoiceChanger")
            values.put(MediaStore.Audio.Media.IS_PENDING, 1)
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return null
            encode(pcm, rate, pfd.fileDescriptor)
            pfd.close()
            val done = ContentValues()
            done.put(MediaStore.Audio.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(pcm: ShortArray, rate: Int, fd: FileDescriptor) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    ib.clear()
                    ib.order(ByteOrder.nativeOrder())
                    val n = min(pcm.size - pos, ib.capacity() / 2)
                    val pts = pos * 1_000_000L / rate
                    if (n <= 0) {
                        codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        ib.asShortBuffer().put(pcm, pos, n)
                        codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                        pos += n
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                started = true
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    muxer.writeSampleData(track, ob, info)
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }
}
