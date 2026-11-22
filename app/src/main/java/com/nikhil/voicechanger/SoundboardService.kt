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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/** Floating note button over any app (BGMI). Tap it to open a list of your audio clips and play them. */
class SoundboardService : Service() {

    companion object {
        const val ACTION_STOP = "com.nikhil.voicechanger.SB_STOP"
        private const val CHANNEL = "soundboard"
        private const val NOTIF_ID = 8

        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        if (bubble == null) addBubble()
        running = true
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Soundboard", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 5, Intent(this, SoundboardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Soundboard floating button ON")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun addBubble() {
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 22f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.text = "♫"
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#7C3AED"))
        bg.setStroke(dp(2), Color.WHITE)
        tv.background = bg
        tv.alpha = 0.92f

        val lp = WindowManager.LayoutParams(
            dp(54), dp(54),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(320)
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
                        removePanel()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        try {
            wm.addView(tv, lp)
            bubble = tv
            bubbleLp = lp
        } catch (_: Exception) {
            shutdown()
        }
    }

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun clipButton(label: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.setTextColor(Color.WHITE)
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(12).toFloat()
        b.background = g
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, dp(46))
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    private fun showPanel() {
        val clips = Soundboard.list(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(4), dp(8), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F01B1524"))
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg
        box.addView(clipButton("Stop sound", Color.parseColor("#C0392B")) { Soundboard.stop() })
        if (clips.isEmpty()) {
            val t = TextView(this)
            t.text = "Clips ledu. App lo Add audio files nokku."
            t.setTextColor(Color.WHITE)
            t.textSize = 12f
            t.setPadding(0, dp(8), 0, 0)
            box.addView(t)
        }
        for (f in clips) {
            box.addView(clipButton(Soundboard.label(f), Color.parseColor("#E0457B")) { Soundboard.play(this, f) })
        }
        val scroll = ScrollView(this)
        scroll.addView(box)
        val h = min(dp(380), (clips.size + 1) * dp(52) + dp(30))
        val lp = WindowManager.LayoutParams(
            dp(190), h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val bl = bubbleLp ?: return
        val screenW = resources.displayMetrics.widthPixels
        lp.x = if (bl.x + dp(60) + dp(190) < screenW) bl.x + dp(60) else (bl.x - dp(196)).coerceAtLeast(0)
        lp.y = bl.y
        try {
            wm.addView(scroll, lp)
            panel = scroll
        } catch (_: Exception) {
        }
    }

    private fun removePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
    }

    private fun cleanup() {
        removePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble = null
        Soundboard.stop()
        running = false
    }

    private fun shutdown() {
        if (this::wm.isInitialized) cleanup()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (this::wm.isInitialized) cleanup()
        running = false
        super.onDestroy()
    }
}
