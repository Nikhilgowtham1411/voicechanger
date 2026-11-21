package com.nikhil.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.nikhil.voicechanger.START"
        const val ACTION_STOP = "com.nikhil.voicechanger.STOP"
        const val ACTION_FX = "com.nikhil.voicechanger.FX"
        const val ACTION_REFRESH = "com.nikhil.voicechanger.REFRESH"
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 7

        @Volatile var running = false
        @Volatile var lastError: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var alive = false
    private var thread: Thread? = null
    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_FX -> {
                Params.effectOn = !Params.effectOn
                refreshUi()
            }
            ACTION_REFRESH -> refreshUi()
            else -> {
                Params.load(this)
                startForegroundCompat()
                if (!alive) startAudio()
                refreshUi()
            }
        }
        return START_NOT_STICKY
    }

    private fun refreshUi() {
        updateNotification()
        main.post { updateOverlay() }
    }

    // ------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fx = PendingIntent.getService(
            this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_FX),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Changer " + if (Params.effectOn) "ON" else "OFF (original voice)")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (Params.effectOn) "Effect off" else "Effect on", fx).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice changer", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    // ------------------------------------------------------------ audio

    private fun startAudio() {
        alive = true
        running = true
        lastError = null
        thread = Thread({ audioLoop() }, "voice-dsp").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun findDevice(id: Int): AudioDeviceInfo? {
        if (id < 0) return null
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
    }

    private fun fail(msg: String) {
        lastError = msg
        main.post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            stopAll()
        }
    }

    private fun audioLoop() {
        var rate = 0
        for (r in intArrayOf(16000, 44100, 48000)) {
            if (AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0) {
                rate = r
                break
            }
        }
        if (rate == 0) {
            fail("Mic not supported")
            return
        }

        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minIn, rate / 5 * 2)
            )
        } catch (e: Exception) {
            fail("Mic permission / start error")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Mic busy. Close other apps using mic")
            return
        }
        var ns: NoiseSuppressor? = null
        if (Params.noiseSuppress && NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(rec.audioSessionId)
                ns?.enabled = true
            } catch (_: Exception) {
            }
        }

        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(max(minOut, rate / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        findDevice(Params.outputDeviceId)?.let { track.setPreferredDevice(it) }

        val dsp = VoiceDsp(rate)
        val frame = rate / 50
        val inS = ShortArray(frame)
        val f = FloatArray(frame)
        val outF = FloatArray(frame + 8192)
        val outS = ShortArray(outF.size)

        // small prefill avoids start-up underruns
        track.write(ShortArray(rate / 25), 0, rate / 25)
        track.play()
        rec.startRecording()

        while (alive) {
            val n = rec.read(inS, 0, frame)
            if (n < 0) break
            if (n == 0) continue
            for (i in 0 until n) f[i] = inS[i] / 32768f

            dsp.ratio = Params.ratio()
            dsp.formant = Params.formant
            dsp.gateOn = Params.gate
            dsp.inGain = Params.micGain
            val m = dsp.process(f, n, outF)

            val og = Params.outGain
            val fx = Params.effectOn
            val count = if (fx) m else n
            for (i in 0 until count) {
                var v = (if (fx) outF[i] else f[i] * Params.micGain) * og
                val a = abs(v)
                if (a > 0.9f) {
                    val over = a - 0.9f
                    val soft = 0.9f + 0.1f * (over / (over + 0.1f))
                    v = if (v < 0) -soft else soft
                }
                outS[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (count > 0) track.write(outS, 0, count)
        }

        try { rec.stop() } catch (_: Exception) { }
        rec.release()
        try { ns?.release() } catch (_: Exception) { }
        try { track.stop() } catch (_: Exception) { }
        track.release()
    }

    // ------------------------------------------------------------ overlay button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val want = Params.overlay && alive && Settings.canDrawOverlays(this)
        if (!want) {
            overlayView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            overlayView = null
            return
        }
        if (overlayView == null) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            tv.textSize = 20f
            tv.typeface = Typeface.DEFAULT_BOLD
            val lp = WindowManager.LayoutParams(
                dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = dp(8)
            lp.y = dp(220)
            var dx = 0f
            var dy = 0f
            var sx = 0
            var sy = 0
            var moved = false
            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val mx = (e.rawX - dx).toInt()
                        val my = (e.rawY - dy).toInt()
                        if (abs(mx) > 12 || abs(my) > 12) moved = true
                        if (moved) {
                            lp.x = sx + mx
                            lp.y = sy + my
                            try { wm.updateViewLayout(tv, lp) } catch (_: Exception) { }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            Params.effectOn = !Params.effectOn
                            refreshUi()
                        }
                    }
                }
                true
            }
            overlayParams = lp
            try {
                wm.addView(tv, lp)
                overlayView = tv
            } catch (_: Exception) {
                return
            }
        }
        overlayView?.let {
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (Params.effectOn) Color.parseColor("#E0457B") else Color.parseColor("#555555"))
            bg.setStroke(dp(2), Color.WHITE)
            it.background = bg
            it.text = if (Params.effectOn) "♀" else "OFF"
            it.alpha = 0.9f
        }
    }

    // ------------------------------------------------------------ stop

    private fun stopAll() {
        alive = false
        running = false
        overlayView?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
        thread?.let { try { it.join(800) } catch (_: Exception) { } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        running = false
        overlayView?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
        super.onDestroy()
    }
}
