package dk.airsync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_PERMISSIONS = 2
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Grafit, kort, kobber og varm hvid.
        private val BG = 0xFF111113.toInt()
        private val CARD = 0xFF1C1C20.toInt()
        private val TILE = 0xFF2A2A2E.toInt()
        private val TRACK = 0xFF34343A.toInt()
        private val COPPER = 0xFFD08A4E.toInt()
        private val TEXT = 0xFFF2EFEA.toInt()
        private val MUTED = 0xFF8A8A90.toInt()
        private val PILL_ON = 0xFF2A2118.toInt()
        private val PILL_ON_TEXT = 0xFFE8B48A.toInt()
    }

    private lateinit var discovery: Discovery
    private val speakers = ArrayList<Speaker>()
    private val checked = HashSet<String>()
    private val speakerSwitches = LinkedHashMap<String, Switch>()
    private var syncingSwitches = false
    private val speakerSubtitles = HashMap<String, TextView>()
    private val prefs by lazy { getSharedPreferences("airsync", MODE_PRIVATE) }

    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var volumeNumber: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeHint: TextView
    private lateinit var speakerList: LinearLayout
    private lateinit var playButton: FrameLayout
    private lateinit var playIcon: ImageView
    private lateinit var playCaption: TextView
    private var dialogLog: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppState.running) {
            AppState.volumePercent = prefs.getInt("volume", AppState.volumePercent)
            AppState.volumeKeysControlSpeakers = prefs.getBoolean("keys", true)
            AppState.muteLocalSpeaker = prefs.getBoolean("mute", true)
        }
        AppState.selected.forEach { checked.add(it.id) }
        prefs.getStringSet("selected", emptySet())?.let { checked.addAll(it) }
        buildUi()
        askPermissions()
        discovery = Discovery(this) { found ->
            speakers.clear()
            speakers.addAll(found)
            renderSpeakers()
        }
        discovery.start()
        AppState.listener = { refresh() }
        renderSpeakers()
        refresh()
    }

    override fun onPause() {
        prefs.edit()
            .putInt("volume", AppState.volumePercent)
            .putStringSet("selected", HashSet(checked))
            .apply()
        super.onPause()
    }

    override fun onDestroy() {
        discovery.stop()
        AppState.listener = null
        super.onDestroy()
    }

    // ---------- Små byggeklodser ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun iconTile(icon: Int, sizeDp: Int, bg: Int, tint: Int, iconDp: Int) = FrameLayout(this).apply {
        background = rounded(bg, sizeDp / 4)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
        }, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun styleSeekBar(bar: SeekBar) {
        bar.progressTintList = ColorStateList.valueOf(COPPER)
        bar.progressBackgroundTintList = ColorStateList.valueOf(TRACK)
        bar.thumbTintList = ColorStateList.valueOf(TEXT)
        bar.splitTrack = false
    }

    private fun styleSwitch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(TEXT, MUTED))
        sw.trackTintList = ColorStateList(states, intArrayOf(COPPER, TRACK))
    }

    private fun topMargin(view: View, dpValue: Int, width: Int = MATCH) =
        view.apply { layoutParams = LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(dpValue) } }

    // ---------- Skærmen ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        // Top: mærke, navn og indstillinger
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconTile(R.drawable.ic_airsync, 32, COPPER, BG, 20))
        header.addView(text("AirSync", 22f, TEXT, bold = true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(MUTED)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Indstillinger"
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        // Statusfelt
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
        }
        statusDot = View(this)
        statusPill.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
        statusText = text("", 13f, MUTED)
        statusPill.addView(statusText)
        root.addView(statusPill, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })

        // Samlet lydstyrke
        val volumeCard = card()
        val volumeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        volumeRow.addView(text("Samlet lydstyrke", 13f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        volumeNumber = text("", 30f, TEXT, bold = true)
        volumeRow.addView(volumeNumber)
        volumeRow.addView(text(" %", 15f, MUTED))
        volumeCard.addView(volumeRow)
        volumeBar = SeekBar(this).apply {
            max = 100
            progress = AppState.volumePercent
        }
        styleSeekBar(volumeBar)
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                volumeNumber.text = progress.toString()
                val setter = AppState.volumeSetter
                if (setter != null) setter(progress) else AppState.volumePercent = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        volumeCard.addView(topMargin(volumeBar, 10))
        volumeHint = text("Styres også med telefonens lydknapper", 12f, MUTED)
        volumeCard.addView(topMargin(volumeHint, 8))
        root.addView(topMargin(volumeCard, 16))

        // Højttalere
        root.addView(topMargin(text("Højttalere", 13f, MUTED).apply { setPadding(dp(4), 0, 0, 0) }, 22))
        speakerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(speakerList)

        // Start/stop
        playButton = FrameLayout(this).apply {
            background = oval(COPPER)
            isClickable = true
            contentDescription = "Start eller stop"
            setOnClickListener { onStartStop() }
        }
        playIcon = ImageView(this).apply { imageTintList = ColorStateList.valueOf(BG) }
        playButton.addView(playIcon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        root.addView(playButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            topMargin = dp(28)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        playCaption = text("", 13f, MUTED).apply { gravity = Gravity.CENTER }
        root.addView(topMargin(playCaption, 10))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(root)
        })
    }

    private fun renderSpeakers() {
        speakerList.removeAllViews()
        speakerSwitches.clear()
        speakerSubtitles.clear()
        if (speakers.isEmpty()) {
            val empty = card()
            empty.addView(text("Søger efter højttalere…", 15f, TEXT, bold = true))
            empty.addView(topMargin(text("Telefonen skal være på samme WiFi som højttalerne.", 13f, MUTED), 4))
            speakerList.addView(topMargin(empty, 10))
            return
        }
        for (sp in speakers) speakerList.addView(topMargin(speakerCard(sp), 10))
        refresh()
    }

    private fun speakerCard(sp: Speaker): LinearLayout {
        if (!AppState.speakerLevels.containsKey(sp.id)) {
            AppState.speakerLevels[sp.id] = prefs.getInt(levelKey(sp.id), 100)
        }
        val c = card()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconTile(R.drawable.ic_speaker, 40, TILE, COPPER, 22))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(text(sp.name, 16f, TEXT, bold = true))
        val subtitle = text(sp.host, 12f, MUTED)
        texts.addView(subtitle)
        speakerSubtitles[sp.id] = subtitle
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val sw = Switch(this).apply {
            isChecked = sp.id in checked
            contentDescription = "Brug ${sp.name}"
            setOnCheckedChangeListener { _, isOn ->
                if (syncingSwitches) return@setOnCheckedChangeListener
                if (isOn) checked.add(sp.id) else checked.remove(sp.id)
                if (AppState.running) toggleWhilePlaying(sp, isOn)
            }
        }
        styleSwitch(sw)
        speakerSwitches[sp.id] = sw
        row.addView(sw)
        c.addView(row)

        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = text("${AppState.speakerLevel(sp.id)} %", 12f, MUTED).apply {
            gravity = Gravity.END
            minWidth = dp(44)
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = AppState.speakerLevel(sp.id)
            contentDescription = "Niveau for ${sp.name}"
        }
        styleSeekBar(bar)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                AppState.speakerLevels[sp.id] = progress
                value.text = "$progress %"
                AppState.streamer?.updateVolumes()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.edit().putInt(levelKey(sp.id), seekBar.progress).apply()
            }
        })
        levelRow.addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f))
        levelRow.addView(value)
        c.addView(topMargin(levelRow, 8))
        return c
    }

    private fun levelKey(id: String) = "level_$id"

    /** Tilføjer eller fjerner en højttaler, uden at afspilningen på de andre afbrydes. */
    private fun toggleWhilePlaying(sp: Speaker, on: Boolean) {
        val streamer = AppState.streamer ?: return
        if (on) {
            AppState.addSelected(sp)
            if (!streamer.addSpeaker(sp)) {
                AppState.removeSelected(sp.id)
                Toast.makeText(this, "Vent et øjeblik, til afspilningen er i gang", Toast.LENGTH_SHORT).show()
            }
        } else if (AppState.selected.count { it.id != sp.id } == 0) {
            // Den sidste højttaler slås fra: så stopper afspilningen.
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        } else {
            AppState.removeSelected(sp.id)
            streamer.removeSpeaker(sp.id)
        }
        refresh()
    }

    private fun refresh() {
        val running = AppState.running
        val playing = AppState.selected.map { it.id }.toSet()

        if (running) {
            statusPill.background = rounded(PILL_ON, 14)
            statusDot.background = oval(COPPER)
            statusText.setTextColor(PILL_ON_TEXT)
            val n = AppState.selected.size
            statusText.text = if (n == 1) "Spiller · 1 højttaler" else "Spiller · $n højttalere i synk"
        } else {
            statusPill.background = rounded(CARD, 14)
            statusDot.background = oval(MUTED)
            statusText.setTextColor(MUTED)
            statusText.text = "Klar · vælg højttalere"
        }

        volumeNumber.text = AppState.volumePercent.toString()
        if (volumeBar.progress != AppState.volumePercent) volumeBar.progress = AppState.volumePercent
        volumeHint.visibility = if (AppState.volumeKeysControlSpeakers) View.VISIBLE else View.GONE

        // Under afspilning viser kontakterne, hvilke højttalere der faktisk spiller med.
        if (running) {
            syncingSwitches = true
            for ((id, sw) in speakerSwitches) {
                val on = id in playing
                if (sw.isChecked != on) sw.isChecked = on
                if (on) checked.add(id) else checked.remove(id)
            }
            syncingSwitches = false
        }
        for (sp in speakers) {
            speakerSubtitles[sp.id]?.text = when {
                running && sp.id in AppState.connecting -> "Forbinder…"
                running && sp.id in playing -> "Spiller · ${sp.host}"
                else -> sp.host
            }
        }

        playIcon.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        playCaption.text = if (running) "Tryk for at stoppe" else "Tryk for at starte"
        dialogLog?.text = AppState.logText()
    }

    // ---------- Indstillinger og diagnose ----------

    private fun openSettings() {
        val running = AppState.running
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }

        fun option(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(TEXT)
            isChecked = value
            isEnabled = !running
            setPadding(0, dp(10), 0, dp(10))
            styleSwitch(this)
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }

        box.addView(option("Lydknapperne styrer kun højttalerne", AppState.volumeKeysControlSpeakers) {
            AppState.volumeKeysControlSpeakers = it
            prefs.edit().putBoolean("keys", it).apply()
            refresh()
        })
        box.addView(option("Telefonens højttaler er slukket under afspilning", AppState.muteLocalSpeaker) {
            AppState.muteLocalSpeaker = it
            prefs.edit().putBoolean("mute", it).apply()
        })
        if (running) box.addView(text("Kan ændres, når der ikke afspilles.", 12f, MUTED))

        box.addView(topMargin(text("Diagnose", 13f, MUTED), 18))
        val log = text(AppState.logText(), 11f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        dialogLog = log
        val logScroll = ScrollView(this).apply {
            background = rounded(BG, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(log)
        }
        box.addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(220)).apply { topMargin = dp(8) })
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        box.addView(topMargin(text("AirSync v$version", 12f, MUTED), 12))

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Indstillinger")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Luk", null)
            .setOnDismissListener { dialogLog = null }
            .show()
    }

    // ---------- Start og stop ----------

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun onStartStop() {
        if (AppState.running) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "AirSync skal have adgang til lyd for at kunne sende den", Toast.LENGTH_LONG).show()
            askPermissions()
            return
        }
        val selected = speakers.filter { it.id in checked }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Vælg mindst én højttaler", Toast.LENGTH_SHORT).show()
            return
        }
        AppState.selected = selected
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            AppState.log("Adgang til lyden blev afvist.")
            return
        }
        startForegroundService(
            Intent(this, StreamService::class.java)
                .putExtra(StreamService.EXTRA_CODE, resultCode)
                .putExtra(StreamService.EXTRA_DATA, data)
        )
    }
}
