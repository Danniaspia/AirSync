package dk.airsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Forgrundsservice, så lyden fortsætter når skærmen slukkes eller du skifter til browseren.
 *
 * Tilstande:
 *  - Spiller: der streames til højttalerne.
 *  - Klar: Stop er trykket, men godkendelsen (MediaProjection) holdes, så næste Start ikke kræver dialog.
 *  - Afsluttet: servicen er stoppet; næste Start viser Androids dialog igen.
 */
class StreamService : Service() {

    companion object {
        /** Stop afspilningen, men bliv klar (ingen ny godkendelse ved næste Start). */
        const val ACTION_STOP = "dk.airsync.STOP"
        /** Spil igen med den godkendelse, der allerede er givet. */
        const val ACTION_PLAY = "dk.airsync.PLAY"
        /** Afslut helt og slip godkendelsen. */
        const val ACTION_EXIT = "dk.airsync.EXIT"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "stream"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_POLL_MS = 100L

        // Når telefonens højttaler skal være tavs, tjekkes lydløs ofte, så et glimt bliver for kort til at høres.
        private const val MUTE_GUARD_MS = 15L

        // Systemets (skjulte) broadcast, når en lydstyrke ændres.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var streamer: Streamer? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var baseVolume = 0
    private var audioManager: AudioManager? = null
    private var muteLocal = false
    private var volumeKeys = false
    private var originalVolume = 0
    private var originalMuted = false
    private var gotVolumeBroadcast = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var exiting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                goStandby()
                return START_NOT_STICKY
            }
            ACTION_EXIT -> {
                exit()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                if (projection == null) exit() else play()
                return START_NOT_STICKY
            }
        }

        // Ny godkendelse fra Androids dialog.
        if (projection != null) {
            play()
            return START_NOT_STICKY
        }
        exiting = false
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (data == null) {
            exit()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            AppState.log("Kunne ikke få adgang til lyden: ${e.message}")
            null
        }
        if (p == null) {
            exit()
            return START_NOT_STICKY
        }
        // Afbryder Android selv delingen (fx via statuslinjen), afsluttes AirTooth helt.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { exit() }
            }
        }, main)
        projection = p
        play()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exit()
        super.onDestroy()
    }

    /** Starter afspilning med den godkendelse, servicen allerede har. */
    private fun play() {
        if (streamer != null) return
        val p = projection ?: return
        if (AppState.selected.isEmpty()) return
        acquireLocks()
        val s = Streamer(p, AppState.selected)
        streamer = s
        AppState.streamer = s
        AppState.volumeSetter = { applyVolume(it) }
        AppState.standby = false
        AppState.running = true
        startPhoneVolumeControl()
        updateNotification()
        thread(name = "start") {
            if (!s.start()) main.post { if (streamer === s) goStandby() }
        }
    }

    /** Stop: højttalerne afbrydes og telefonens lyd gendannes, men godkendelsen beholdes. */
    private fun goStandby() {
        val s = streamer
        streamer = null
        AppState.streamer = null
        s?.stop()
        stopPhoneVolumeControl()
        releaseLocks()
        AppState.running = false
        if (projection == null) {
            exit()
            return
        }
        AppState.standby = true
        AppState.notifyChanged()
        updateNotification()
    }

    /** Afslut: alt stoppes, og godkendelsen slippes. */
    private fun exit() {
        if (exiting) return
        exiting = true
        val s = streamer
        streamer = null
        AppState.streamer = null
        s?.stop()
        stopPhoneVolumeControl()
        releaseLocks()
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (_: Exception) {
        }
        AppState.running = false
        AppState.standby = false
        AppState.notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Sætter højttalernes lydstyrke (0–100) fra knapper, skyder eller lydpanelet. Kaldes på main-tråden. */
    private fun applyVolume(value: Int) {
        val p = value.coerceIn(0, 100)
        AppState.volumePercent = p
        streamer?.updateVolumes()
        AppState.notifyChanged()
    }

    /**
     * Styrer telefonens egen medielyd, mens der streames:
     *  - [muteLocal]: telefonens højttaler er lydløs (det opfangede signal tages altid med fuld styrke,
     *    så højttalerne mister ikke lyden).
     *  - [volumeKeys]: lydknapperne flytter telefonens lydstyrke; vi sætter den straks tilbage og
     *    bruger ændringen på højttalerne i stedet. Virker også med slukket skærm.
     */
    private fun startPhoneVolumeControl() {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        muteLocal = AppState.muteLocalSpeaker
        volumeKeys = AppState.volumeKeysControlSpeakers
        originalMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
        originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        gotVolumeBroadcast = false
        audioManager = am

        if (volumeKeys) {
            // Udgangspunktet må ikke ligge helt i top eller bund, ellers giver et tryk ingen ændring at opfange.
            // Er telefonen lydløs, bruges laveste trin: Android ophæver selv lydløs et øjeblik ved "op",
            // og så høres det glimt næsten ikke.
            val base = if (muteLocal) 1 else originalVolume.coerceIn(1, (max - 1).coerceAtLeast(1))
            if (muteLocal) muteMusic()
            if (base != originalVolume) am.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
            baseVolume = base
            AppState.log("Lydknapper styrer højttalerne")
        }
        if (muteLocal) {
            muteMusic()
            AppState.log("Telefonens højttaler er slået fra")
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                if (!gotVolumeBroadcast) {
                    gotVolumeBroadcast = true
                    AppState.log("Signal fra lydknapperne modtaget")
                }
                // Når telefonen er lydløs, svarer getStreamVolume altid 0 – men broadcasten har det rigtige trin.
                val index = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                if (muteLocal) {
                    if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) muteMusic()
                    if (volumeKeys && index >= 0) handlePhoneIndex(index)
                } else if (volumeKeys) {
                    handlePhoneIndex(am.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        volumeReceiver = receiver
        // Ikke alle telefoner sender broadcasten pålideligt, så vi tjekker også selv jævnligt.
        main.postDelayed(volumePoll, pollInterval())
    }

    private fun pollInterval() = if (muteLocal) MUTE_GUARD_MS else VOLUME_POLL_MS

    private val volumePoll: Runnable = object : Runnable {
        override fun run() {
            val am = audioManager ?: return
            if (muteLocal) {
                // Et tryk på "op" ophæver lydløs. Slå den straks til igen; trykket tælles via broadcasten.
                if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    muteMusic()
                    if (volumeKeys && !gotVolumeBroadcast) handlePhoneIndex(index)
                }
            } else if (volumeKeys) {
                handlePhoneIndex(am.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
            main.postDelayed(this, pollInterval())
        }
    }

    /** Har telefonens lydstyrke flyttet sig, sættes den tilbage, og ændringen bruges på højttalerne. */
    private fun handlePhoneIndex(index: Int) {
        val am = audioManager ?: return
        val steps = index - baseVolume
        if (steps == 0) return
        // Lydløs før og efter, så telefonen ikke når at spille, mens trinnet sættes tilbage.
        if (muteLocal) muteMusic()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0)
        if (muteLocal) muteMusic()
        applyVolume(AppState.volumePercent + steps * AppState.VOLUME_STEP)
        AppState.log("Lydknap: højttalere ${AppState.volumePercent} %")
    }

    private fun muteMusic() {
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    /** Sætter telefonens medielyd tilbage, som den var før Start. */
    private fun stopPhoneVolumeControl() {
        main.removeCallbacks(volumePoll)
        volumeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        volumeReceiver = null
        AppState.volumeSetter = null
        val am = audioManager ?: return
        audioManager = null
        try {
            if (volumeKeys && originalVolume > 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            val flag = if (originalMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, 0)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirTooth:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirTooth:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.release()
        } catch (_: Exception) {
        }
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        wakeLock = null
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, StreamService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification() {
        if (exiting) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val playing = streamer != null
        val mainAction = if (playing) {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop), "Stop", serviceIntent(ACTION_STOP, 1)
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_play), "Spil", serviceIntent(ACTION_PLAY, 2)
            ).build()
        }
        val exitAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop), "Afslut", serviceIntent(ACTION_EXIT, 3)
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (playing) "AirTooth spiller" else "AirTooth er klar")
            .setContentText(
                if (playing) AppState.selected.joinToString { it.name } else "Tryk Spil – ingen ny godkendelse"
            )
            .setSmallIcon(R.drawable.ic_airsync)
            .setColor(getColor(R.color.copper))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(mainAction)
            .addAction(exitAction)
            .build()
    }
}
