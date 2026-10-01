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

/** Forgrundsservice, så lyden fortsætter når skærmen slukkes eller du skifter til browseren. */
class StreamService : Service() {

    companion object {
        const val ACTION_STOP = "dk.airsync.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "stream"
        private const val VOLUME_STEP = 5
        private const val VOLUME_POLL_MS = 100L

        // Systemets (skjulte) broadcast, når en lydstyrke ændres.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }

    private val main = Handler(Looper.getMainLooper())
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
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }
        if (streamer != null) return START_NOT_STICKY
        stopping = false
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (data == null) {
            stopStreaming()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            AppState.log("Kunne ikke få adgang til lyden: ${e.message}")
            null
        }
        if (projection == null) {
            stopStreaming()
            return START_NOT_STICKY
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopStreaming() }
            }
        }, main)

        acquireLocks()
        val s = Streamer(projection, AppState.selected)
        streamer = s
        AppState.streamer = s
        AppState.volumeSetter = { applyVolume(it) }
        AppState.running = true
        startPhoneVolumeControl()
        thread(name = "start") {
            if (!s.start()) main.post { stopStreaming() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun stopStreaming() {
        if (stopping) return
        stopping = true
        val s = streamer
        streamer = null
        AppState.streamer = null
        s?.stop()
        AppState.running = false
        stopPhoneVolumeControl()
        releaseLocks()
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
     * Samsung sender lydknapperne til browseren, ikke hertil. Derfor lytter vi efter, at telefonens
     * medielydstyrke ændres, sætter den straks tilbage og bruger ændringen på højttalerne i stedet.
     * Virker også med slukket skærm, så længe der spiller lyd.
     */
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
        audioManager = am

        if (volumeKeys) {
            // Udgangspunktet må ikke ligge helt i top eller bund, ellers giver et tryk ingen ændring at opfange.
            // Er telefonen lydløs, bruges laveste trin: Android ophæver selv lydløs et øjeblik ved "op",
            // og så høres det glimt næsten ikke.
            val base = if (muteLocal) 1 else originalVolume.coerceIn(1, (max - 1).coerceAtLeast(1))
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
                    if (volumeKeys && index >= 0) handlePhoneIndex(index)
                    if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) muteMusic()
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
        main.postDelayed(volumePoll, VOLUME_POLL_MS)
    }

    private val volumePoll: Runnable = object : Runnable {
        override fun run() {
            val am = audioManager ?: return
            if (muteLocal) {
                // Et tryk på "op" ophæver lydløs. Lydstyrken tælles via broadcasten; her sikrer vi kun lydløs.
                if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    if (volumeKeys && !gotVolumeBroadcast) handlePhoneIndex(am.getStreamVolume(AudioManager.STREAM_MUSIC))
                    muteMusic()
                }
            } else if (volumeKeys) {
                handlePhoneIndex(am.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
            main.postDelayed(this, VOLUME_POLL_MS)
        }
    }

    /** Har telefonens lydstyrke flyttet sig, sættes den tilbage, og ændringen bruges på højttalerne. */
    private fun handlePhoneIndex(index: Int) {
        val am = audioManager ?: return
        val steps = index - baseVolume
        if (steps == 0) return
        am.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0)
        if (muteLocal) muteMusic()
        applyVolume(AppState.volumePercent + steps * VOLUME_STEP)
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

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AirTooth spiller")
            .setContentText(AppState.selected.joinToString { it.name })
            .setSmallIcon(R.drawable.ic_airsync)
            .setColor(getColor(R.color.copper))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop
                ).build()
            )
            .build()
    }
}
