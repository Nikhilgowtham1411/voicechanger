package com.nikhil.voicechanger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F0B14")
    private val card = Color.parseColor("#1B1524")
    private val accent = Color.parseColor("#E0457B")
    private val dim = Color.parseColor("#B8AEC4")

    private val ui = Handler(Looper.getMainLooper())
    private var updating = false

    private lateinit var powerBtn: Button
    private lateinit var fxBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var presetSp: Spinner
    private lateinit var pitchSeek: SeekBar
    private lateinit var pitchTv: TextView
    private lateinit var formSeek: SeekBar
    private lateinit var formTv: TextView
    private lateinit var micSeek: SeekBar
    private lateinit var micTv: TextView
    private lateinit var outSeek: SeekBar
    private lateinit var outTv: TextView
    private lateinit var devSp: Spinner
    private var deviceIds: List<Int> = listOf(-1)
    private var inIds: List<Int> = listOf(-1)
    private lateinit var micSp: Spinner
    private val recorder by lazy { VoiceRecorder(this) }
    private var rawPcm: ShortArray? = null
    private var procPcm: ShortArray? = null
    private var recRate = 16000
    private lateinit var recBtn: Button
    private lateinit var playBtn: Button
    private lateinit var origBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var recStatus: TextView
    private lateinit var sbFloatBtn: Button
    private lateinit var sbList: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 18): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun cardBox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = rounded(card)
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(12)
        l.layoutParams = lp
        return l
    }

    private fun seek(max: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = max
        s.progressTintList = ColorStateList.valueOf(accent)
        s.thumbTintList = ColorStateList.valueOf(accent)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && !updating) onChange(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Params.save(this@MainActivity)
            }
        })
        return s
    }

    private fun sw(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(this)
        s.text = label
        s.textSize = 15f
        s.setTextColor(Color.WHITE)
        s.isChecked = checked
        s.setPadding(0, dp(6), 0, dp(6))
        s.setOnCheckedChangeListener { _, v -> if (!updating) onChange(v) }
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Params.load(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(36), dp(18), dp(28))
        scroll.addView(root)

        root.addView(text("Voice Changer", 28f, Color.WHITE, true))
        root.addView(text("Clean, real female voice - live", 14f, dim))

        // power
        val pc = cardBox()
        statusTv = text("Stopped", 15f, dim)
        statusTv.gravity = Gravity.CENTER
        powerBtn = Button(this)
        powerBtn.textSize = 18f
        powerBtn.setTextColor(Color.WHITE)
        powerBtn.isAllCaps = false
        powerBtn.setOnClickListener { togglePower() }
        fxBtn = Button(this)
        fxBtn.isAllCaps = false
        fxBtn.setTextColor(Color.WHITE)
        fxBtn.setOnClickListener {
            Params.effectOn = !Params.effectOn
            if (VoiceService.running) startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_REFRESH))
            refresh()
        }
        pc.addView(powerBtn, LinearLayout.LayoutParams(-1, dp(58)))
        val fxLp = LinearLayout.LayoutParams(-1, dp(46))
        fxLp.topMargin = dp(8)
        pc.addView(fxBtn, fxLp)
        pc.addView(statusTv, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        root.addView(pc)

        // voice note recorder
        val rc = cardBox()
        rc.addView(text("Voice note (WhatsApp)", 16f, Color.WHITE, true))
        rc.addView(text("Record chey, marchina voice mundu vinu, tarvata WhatsApp ki share chey. (Live Stop lo undali)", 12f, dim))
        recBtn = Button(this)
        recBtn.isAllCaps = false
        recBtn.setTextColor(Color.WHITE)
        recBtn.setOnClickListener { onRecordClick() }
        rc.addView(recBtn, LinearLayout.LayoutParams(-1, dp(50)).also { it.topMargin = dp(8) })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        playBtn = smallBtn("Play changed") { playClip(true) }
        origBtn = smallBtn("Play original") { playClip(false) }
        row.addView(playBtn, LinearLayout.LayoutParams(0, dp(46), 1f).also { it.rightMargin = dp(6) })
        row.addView(origBtn, LinearLayout.LayoutParams(0, dp(46), 1f).also { it.leftMargin = dp(6) })
        rc.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        shareBtn = smallBtn("Share to WhatsApp / other apps") { shareClip() }
        rc.addView(shareBtn, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(8) })
        recStatus = text("", 12f, dim)
        rc.addView(recStatus)
        root.addView(rc)

        // voice
        val vc = cardBox()
        vc.addView(text("Voice", 16f, Color.WHITE, true))
        presetSp = Spinner(this)
        presetSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Params.presets.map { it.name })
        presetSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                Params.preset = pos
                if (pos != Params.CUSTOM) {
                    val pr = Params.presets[pos]
                    Params.semitones = pr.semitones
                    Params.formant = pr.formant
                    syncSliders()
                }
                Params.save(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        vc.addView(presetSp)

        pitchTv = text("", 14f, dim)
        pitchSeek = seek(26) { p ->
            Params.semitones = (p - 12).toFloat()
            setCustom()
        }
        formTv = text("", 14f, dim)
        formSeek = seek(70) { p ->
            Params.formant = 0.8f + p / 100f
            setCustom()
        }
        vc.addView(pitchTv); vc.addView(pitchSeek)
        vc.addView(formTv); vc.addView(formSeek)
        root.addView(vc)

        // levels
        val lc = cardBox()
        lc.addView(text("Levels", 16f, Color.WHITE, true))
        micTv = text("", 14f, dim)
        micSeek = seek(30) { p -> Params.micGain = p / 10f; syncSliders() }
        outTv = text("", 14f, dim)
        outSeek = seek(20) { p -> Params.outGain = p / 10f; syncSliders() }
        lc.addView(micTv); lc.addView(micSeek)
        lc.addView(outTv); lc.addView(outSeek)
        lc.addView(sw("Noise gate (cuts background noise)", Params.gate) { Params.gate = it; Params.save(this) })
        lc.addView(sw("Mic noise suppression (needs restart)", Params.noiseSuppress) { Params.noiseSuppress = it; Params.save(this) })
        lc.addView(sw("Floating on/off button", Params.overlay) { on -> onOverlayToggle(on) })
        root.addView(lc)

        // output
        val oc = cardBox()
        oc.addView(text("Mic", 16f, Color.WHITE, true))
        micSp = Spinner(this)
        micSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                Params.inputDeviceId = inIds.getOrElse(pos) { -1 }
                Params.save(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        oc.addView(micSp)
        oc.addView(text("Voice output to", 16f, Color.WHITE, true))
        devSp = Spinner(this)
        devSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updating) return
                Params.outputDeviceId = deviceIds.getOrElse(pos) { -1 }
                Params.save(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        oc.addView(devSp)
        oc.addView(text("Change output, then press Stop and Start again.", 12f, dim))
        root.addView(oc)

        // soundboard
        val sc = cardBox()
        sc.addView(text("Soundboard (nee audio clips)", 16f, Color.WHITE, true))
        sc.addView(
            text(
                "Audio files add chey. Tap chesthe phone speaker lo play avtayi, BGMI mic aa sound ni pattukovachu " +
                    "(acoustic ga veltundi, clear ga vinipinchakapovachu). Floating button tho game lo nunde tap cheyyochu.",
                12f, dim
            )
        )
        sc.addView(sw("Force loudspeaker (earphones pettukunna kuda)", Params.forceSpeaker) { Params.forceSpeaker = it; Params.save(this) })
        sc.addView(smallBtn("Add audio files") { pickAudio() }, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(8) })
        sbFloatBtn = smallBtn("Start floating soundboard") { toggleSoundboard() }
        sc.addView(sbFloatBtn, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(8) })
        sbList = LinearLayout(this)
        sbList.orientation = LinearLayout.VERTICAL
        sc.addView(sbList)
        root.addView(sc)

        // help
        val hc = cardBox()
        hc.addView(text("Ela vadali", 16f, Color.WHITE, true))
        hc.addView(
            text(
                "Live: earphones lagao > Start > matladu. Nee marchina voice earphones lo vinipistundi.\n\n" +
                "Voice note: Record > matladu > Stop > Play changed tho vinu (Play original tho compare) > Share to WhatsApp.\n\n" +
                "Voice tho nachakapote Pitch / Female tone sliders adjust chey.\n\n" +
                "Note: BGMI / call lo real-time ga direct work avvadu (Android limit). Voice note ki clear ga work avtundi.",
                13f, dim
            )
        )
        root.addView(hc)

        setContentView(scroll)
        syncSliders()
        loadDevices()
        refreshSoundboard()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
        refresh()
        ui.postDelayed(ticker, 700)
    }

    override fun onPause() {
        recorder.stop()
        recorder.stopPlay()
        ui.removeCallbacks(ticker)
        Params.save(this)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 700)
        }
    }

    private fun setCustom() {
        Params.preset = Params.CUSTOM
        syncSliders()
    }

    private fun syncSliders() {
        updating = true
        presetSp.setSelection(Params.preset.coerceIn(0, Params.CUSTOM))
        pitchSeek.progress = (Params.semitones.toInt() + 12).coerceIn(0, 26)
        formSeek.progress = ((Params.formant - 0.8f) * 100f).toInt().coerceIn(0, 70)
        micSeek.progress = (Params.micGain * 10f).toInt().coerceIn(0, 30)
        outSeek.progress = (Params.outGain * 10f).toInt().coerceIn(0, 20)
        pitchTv.text = String.format(Locale.US, "Pitch: %+d semitones", Params.semitones.toInt())
        formTv.text = String.format(Locale.US, "Female tone (formant): %.2fx", Params.formant)
        micTv.text = String.format(Locale.US, "Mic gain: %d%%", (Params.micGain * 100).toInt())
        outTv.text = String.format(Locale.US, "Output volume: %d%%", (Params.outGain * 100).toInt())
        updating = false
    }

    private fun loadDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val names = ArrayList<String>()
        val ids = ArrayList<Int>()
        names.add("Auto (system default)")
        ids.add(-1)
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val label = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset / aux cable"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call)"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
                else -> null
            }
            if (label != null) {
                val pn = d.productName?.toString().orEmpty()
                names.add(if (pn.isNotEmpty() && d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "$label ($pn)" else label)
                ids.add(d.id)
            }
        }
        deviceIds = ids
        updating = true
        devSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        devSp.setSelection(ids.indexOf(Params.outputDeviceId).let { if (it < 0) 0 else it })

        val inNames = ArrayList<String>()
        val inList = ArrayList<Int>()
        inNames.add("Auto (earphone mic if plugged)")
        inList.add(-1)
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val label = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Earphone mic (wired)"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
                else -> null
            }
            if (label != null) {
                inNames.add(label)
                inList.add(d.id)
            }
        }
        inIds = inList
        micSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, inNames)
        micSp.setSelection(inList.indexOf(Params.inputDeviceId).let { if (it < 0) 0 else it })
        updating = false
    }

    private fun refresh() {
        val run = VoiceService.running
        powerBtn.text = if (run) "Stop" else "Start voice changer"
        powerBtn.background = rounded(if (run) Color.parseColor("#C0392B") else accent, 29)
        fxBtn.text = if (Params.effectOn) "Voice effect: ON (tap for original voice)" else "Voice effect: OFF (tap to turn on)"
        fxBtn.background = rounded(if (Params.effectOn) Color.parseColor("#2E7D5B") else Color.parseColor("#555555"), 23)
        val err = VoiceService.lastError
        statusTv.text = when {
            run -> "LIVE - " + (if (Params.effectOn) "changing your voice" else "original voice")
            err != null -> err
            else -> "Stopped"
        }
        statusTv.setTextColor(if (run) Color.parseColor("#6EE7A8") else dim)

        sbFloatBtn.text = if (SoundboardService.running) "Stop floating soundboard" else "Start floating soundboard"
        val rec = recorder.recording
        val has = procPcm != null && !rec
        recBtn.text = if (rec) "Stop recording" else "Record voice note"
        recBtn.background = rounded(if (rec) Color.parseColor("#C0392B") else accent, 25)
        val pl = recorder.playing
        playBtn.text = if (pl) "Stop" else "Play changed"
        origBtn.text = if (pl) "Stop" else "Play original"
        for (b in listOf(playBtn, origBtn, shareBtn)) {
            b.isEnabled = has
            b.alpha = if (has) 1f else 0.4f
        }
    }

    private fun smallBtn(label: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(Color.WHITE)
        b.background = rounded(Color.parseColor("#2F2640"), 23)
        b.setOnClickListener { click() }
        return b
    }

    private fun onRecordClick() {
        if (recorder.recording) {
            recorder.stop()
            return
        }
        if (VoiceService.running) {
            Toast.makeText(this, "Mundu live Stop cheyyi", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        recorder.stopPlay()
        Params.save(this)
        recStatus.text = "Recording... matladu"
        recorder.start({ raw, proc, rate ->
            runOnUiThread {
                rawPcm = raw
                procPcm = proc
                recRate = rate
                recStatus.text = String.format(Locale.US, "Recorded %.1f sec. Play chesi vinu", proc.size / rate.toFloat())
                refresh()
            }
        }, { msg ->
            runOnUiThread {
                recStatus.text = msg
                refresh()
            }
        })
        refresh()
    }

    private fun playClip(changed: Boolean) {
        if (recorder.playing) {
            recorder.stopPlay()
            return
        }
        val pcm = (if (changed) procPcm else rawPcm) ?: return
        recorder.play(pcm, recRate) { runOnUiThread { refresh() } }
        refresh()
    }

    private fun shareClip() {
        val pcm = procPcm ?: return
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Android 10+ kavali", Toast.LENGTH_LONG).show()
            return
        }
        recStatus.text = "Preparing file..."
        Thread {
            val uri = recorder.saveM4a(pcm, recRate)
            runOnUiThread {
                if (uri == null) {
                    recStatus.text = "Save fail ayyindi"
                } else {
                    recStatus.text = "Saved in Music/VoiceChanger"
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "audio/mp4"
                    i.putExtra(Intent.EXTRA_STREAM, uri)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Voice note share"))
                }
            }
        }.start()
    }

    private fun togglePower() {
        if (VoiceService.running) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        Params.save(this)
        VoiceService.lastError = null
        startForegroundService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START))
        ui.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == 1) startVoice()
            else Toast.makeText(this, "Permission ichav, malli Record nokku", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Mic permission kavali", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, 5)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 5 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { for (k in 0 until it.itemCount) uris.add(it.getItemAt(k).uri) }
        data.data?.let { uris.add(it) }
        Thread {
            var n = 0
            for (u in uris) if (Soundboard.importClip(this, u)) n++
            runOnUiThread {
                Toast.makeText(this, "$n clip(s) add ayyayi", Toast.LENGTH_SHORT).show()
                refreshSoundboard()
            }
        }.start()
    }

    private fun refreshSoundboard() {
        sbList.removeAllViews()
        val clips = Soundboard.list(this)
        if (clips.isEmpty()) {
            sbList.addView(text("Clips inka ledu", 12f, dim))
            return
        }
        sbList.addView(text("Tap = play, long press = delete", 12f, dim))
        for (f in clips) {
            val b = smallBtn(Soundboard.label(f)) { Soundboard.play(this, f) }
            b.setOnLongClickListener {
                Soundboard.stop()
                f.delete()
                refreshSoundboard()
                true
            }
            sbList.addView(b, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(6) })
        }
        val stopB = smallBtn("Stop sound") { Soundboard.stop() }
        sbList.addView(stopB, LinearLayout.LayoutParams(-1, dp(46)).also { it.topMargin = dp(6) })
    }

    private fun toggleSoundboard() {
        if (SoundboardService.running) {
            startService(Intent(this, SoundboardService::class.java).setAction(SoundboardService.ACTION_STOP))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow chesi malli nokku", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Soundboard.list(this).isEmpty()) {
            Toast.makeText(this, "Mundu audio files add chey", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, SoundboardService::class.java))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun onOverlayToggle(on: Boolean) {
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow chesi malli ON cheyyi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            updating = true
            Params.overlay = false
            updating = false
            return
        }
        Params.overlay = on
        Params.save(this)
        if (VoiceService.running) startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_REFRESH))
    }
}
