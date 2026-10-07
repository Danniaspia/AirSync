package dk.airsync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
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
        private const val REQ_WIFI = 3
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
    private lateinit var nowPlayingText: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var accessCard: LinearLayout
    private lateinit var volumeNumber: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeHint: TextView
    private lateinit var speakerList: LinearLayout
    private lateinit var playButton: FrameLayout
    private lateinit var playIcon: ImageView
    private lateinit var playCaption: TextView
    private lateinit var exitButton: TextView
    private var dialogLog: TextView? = null
    private lateinit var wifiCard: LinearLayout
    private lateinit var wifiTitle: TextView
    private lateinit var wifiButton: TextView
    private var wifiPanelOpen = false
    private var wifiConnected = true
    private var needRediscover = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.appContext = applicationContext
        if (!AppState.running) {
            AppState.volumePercent = AppState.START_VOLUME
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
        watchWifi()
        renderSpeakers()
        refresh()
        // Er WiFi slukket, kommer Androids WiFi-panel frem med det samme – kun én gang pr. åbning.
        if (savedInstanceState == null && !isWifiOn()) openWifiPanel()
    }

    override fun onResume() {
        super.onResume()
        // Er adgangen lige givet i Indstillinger, begynder titlen at vises med det samme.
        if (AppState.running) NowPlaying.start(applicationContext)
        refresh()
    }

    /** Åbner Androids side for "Adgang til notifikationer" direkte på AirTooth, hvis muligt. */
    private fun openNotificationAccess() {
        val component = ComponentName(this, NowPlayingListener::class.java)
        try {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
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
        networkCallback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        AppState.listener = null
        super.onDestroy()
    }

    // ---------- WiFi ----------

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private fun isWifiOn(): Boolean =
        (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled

    private fun isWifiConnected(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Apps må ikke selv tænde WiFi (Android 10+), men gerne vise Androids eget WiFi-panel oven på appen. */
    private fun openWifiPanel() {
        try {
            startActivityForResult(Intent(Settings.Panel.ACTION_WIFI), REQ_WIFI)
            wifiPanelOpen = true
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    /** Når WiFi kommer, lukkes panelet, og der søges forfra efter højttalerne. */
    private fun watchWifi() {
        wifiConnected = isWifiConnected()
        needRediscover = !wifiConnected
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    wifiConnected = true
                    if (wifiPanelOpen) {
                        finishActivity(REQ_WIFI)
                        wifiPanelOpen = false
                    }
                    if (needRediscover) {
                        needRediscover = false
                        AppState.log("WiFi forbundet – søger efter højttalere")
                        discovery.restart()
                    }
                    refresh()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    wifiConnected = isWifiConnected()
                    if (!wifiConnected) needRediscover = true
                    refresh()
                }
            }
        }
        connectivity.registerNetworkCallback(request, cb)
        networkCallback = cb
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
        header.addView(text("AirTooth", 22f, TEXT, bold = true).apply { setPadding(dp(10), 0, 0, 0) },
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

        // Det, der spiller lige nu (fx YouTube i Brave)
        nowPlayingText = text("", 17f, TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(topMargin(nowPlayingText, 12))
        nowPlayingArtist = text("", 13f, MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(topMargin(nowPlayingArtist, 2))

        // Kort, der beder om adgang til at se titlen – vises kun, indtil adgangen er givet eller skjult
        accessCard = card()
        accessCard.addView(text("Vis hvad der spiller", 15f, TEXT, bold = true))
        accessCard.addView(topMargin(text(
            "AirTooth kan vise titlen på nummeret, hvis appen får \"Adgang til notifikationer\". " +
                "Den bruges kun til at læse titlen.", 12f, MUTED
        ), 4))
        val accessButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        accessButtons.addView(text("Giv adgang", 14f, BG, bold = true).apply {
            background = rounded(COPPER, 18)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            setOnClickListener { openNotificationAccess() }
        })
        accessButtons.addView(text("Ikke nu", 14f, MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            setOnClickListener {
                prefs.edit().putBoolean("hide_now_playing_card", true).apply()
                refresh()
            }
        })
        accessCard.addView(topMargin(accessButtons, 10))
        root.addView(topMargin(accessCard, 12))

        // WiFi-kort: vises kun, når telefonen ikke er på WiFi
        wifiCard = card()
        val wifiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wifiTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiTitle = text("", 15f, TEXT, bold = true)
        wifiTexts.addView(wifiTitle)
        wifiTexts.addView(text("Højttalerne findes over WiFi.", 12f, MUTED))
        wifiRow.addView(wifiTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        wifiButton = text("", 14f, BG, bold = true).apply {
            background = rounded(COPPER, 18)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            setOnClickListener { openWifiPanel() }
        }
        wifiRow.addView(wifiButton)
        wifiCard.addView(wifiRow)
        root.addView(topMargin(wifiCard, 12))

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
        exitButton = text("Afslut", 14f, COPPER, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            contentDescription = "Afslut AirTooth"
            setOnClickListener { exitApp() }
        }
        root.addView(exitButton, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(6)
            gravity = Gravity.CENTER_HORIZONTAL
        })

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
                AppState.notifyChanged()
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
        } else if (AppState.standby) {
            statusPill.background = rounded(CARD, 14)
            statusDot.background = oval(COPPER)
            statusText.setTextColor(TEXT)
            statusText.text = "Klar · ingen godkendelse nødvendig"
        } else {
            statusPill.background = rounded(CARD, 14)
            statusDot.background = oval(MUTED)
            statusText.setTextColor(MUTED)
            statusText.text = "Klar · vælg højttalere"
        }

        // Nummeret, der spiller – kun under afspilning, og kun hvis vi kan se det
        val title = if (running) AppState.nowTitle else null
        nowPlayingText.text = title ?: ""
        nowPlayingText.visibility = if (title != null) View.VISIBLE else View.GONE
        val artist = if (title != null) AppState.nowArtist else null
        nowPlayingArtist.text = artist ?: ""
        nowPlayingArtist.visibility = if (artist != null) View.VISIBLE else View.GONE
        val hideCard = prefs.getBoolean("hide_now_playing_card", false) || NowPlaying.hasAccess(this)
        accessCard.visibility = if (hideCard) View.GONE else View.VISIBLE

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
        playCaption.text = when {
            running -> "Tryk for at stoppe"
            AppState.standby -> "Tryk for at spille"
            else -> "Tryk for at starte"
        }
        exitButton.visibility = if (running || AppState.standby) View.VISIBLE else View.GONE
        dialogLog?.text = AppState.logText()

        wifiCard.visibility = if (wifiConnected) View.GONE else View.VISIBLE
        if (isWifiOn()) {
            wifiTitle.text = "Ikke forbundet til WiFi"
            wifiButton.text = "Vælg netværk"
        } else {
            wifiTitle.text = "WiFi er slukket"
            wifiButton.text = "Tænd WiFi"
        }
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
        box.addView(topMargin(text("AirTooth v$version", 12f, MUTED), 12))

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
            Toast.makeText(this, "AirTooth skal have adgang til lyd for at kunne sende den", Toast.LENGTH_LONG).show()
            askPermissions()
            return
        }
        val selected = speakers.filter { it.id in checked }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Vælg mindst én højttaler", Toast.LENGTH_SHORT).show()
            return
        }
        AppState.selected = selected
        if (AppState.standby) {
            // Godkendelsen er der allerede – spil med det samme uden Androids dialog.
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_PLAY))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: bed direkte om hele skærmen, så valget "En app" ikke vises. AirTooth bruger kun lyden.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_CAPTURE)
    }

    private fun exitApp() {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_EXIT))
    }

    /**
     * Mens AirTooth selv er åben, fanges lydknapperne her, før Android ser dem.
     * Så ændres telefonens lyd slet ikke, og der kommer ingen lyd fra telefonen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val setter = AppState.volumeSetter
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && AppState.running && AppState.volumeKeysControlSpeakers && setter != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AppState.VOLUME_STEP else -AppState.VOLUME_STEP
                setter(AppState.volumePercent + step)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WIFI) {
            wifiPanelOpen = false
            refresh()
            return
        }
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
