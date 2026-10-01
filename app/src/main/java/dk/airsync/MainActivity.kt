package dk.airsync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_PERMISSIONS = 2
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    private lateinit var discovery: Discovery
    private val speakers = ArrayList<Speaker>()
    private val checked = HashSet<String>()

    private lateinit var status: TextView
    private lateinit var speakerList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var volume: SeekBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.selected.forEach { checked.add(it.id) }
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

    override fun onDestroy() {
        discovery.stop()
        AppState.listener = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "AirSync"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(status)

        root.addView(label("Højttalere"))
        speakerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(speakerList)

        root.addView(label("Lydstyrke"))
        volume = SeekBar(this).apply {
            max = 100
            progress = AppState.volumePercent
        }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppState.volumePercent = seekBar.progress
                AppState.streamer?.setVolume(seekBar.progress)
            }
        })
        root.addView(volume)
        root.addView(CheckBox(this).apply {
            text = "Telefonens lydknapper styrer højttalerne"
            isChecked = AppState.followPhoneVolume
            setOnCheckedChangeListener { _, isOn -> AppState.followPhoneVolume = isOn }
        })

        startButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { onStartStop() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })

        root.addView(label("Log"))
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun renderSpeakers() {
        speakerList.removeAllViews()
        if (speakers.isEmpty()) {
            speakerList.addView(TextView(this).apply {
                text = "Søger… (telefonen skal være på samme WiFi som højttalerne)"
            })
            return
        }
        for (sp in speakers) {
            speakerList.addView(CheckBox(this).apply {
                text = "${sp.name}   ${sp.host}"
                textSize = 16f
                isChecked = sp.id in checked
                isEnabled = !AppState.running
                setOnCheckedChangeListener { _, isOn ->
                    if (isOn) checked.add(sp.id) else checked.remove(sp.id)
                }
            })
        }
    }

    private fun refresh() {
        val running = AppState.running
        status.text = if (running) {
            "Spiller på: " + AppState.selected.joinToString { it.name }
        } else {
            "Vælg højttalere og tryk Start"
        }
        startButton.text = if (running) "Stop" else "Start"
        if (volume.progress != AppState.volumePercent) volume.progress = AppState.volumePercent
        for (i in 0 until speakerList.childCount) speakerList.getChildAt(i).isEnabled = !running
        logView.text = AppState.logText()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onStartStop() {
        if (AppState.running) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Appen skal have lov til at optage lyd", Toast.LENGTH_LONG).show()
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
