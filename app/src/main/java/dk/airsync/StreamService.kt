package dk.airsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
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
        private const val PRIORITY_BUMP_MS = 2_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var streamer: Streamer? = null
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
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
        if (AppState.volumeKeysControlSpeakers) startVolumeSession()
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
        stopVolumeSession()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Sætter højttalernes lydstyrke (0–100) fra knapper, skyder eller lydpanelet. Kaldes på main-tråden. */
    private fun applyVolume(value: Int) {
        val p = value.coerceIn(0, 100)
        AppState.volumePercent = p
        volumeProvider?.currentVolume = p
        streamer?.setVolume(p)
        AppState.notifyChanged()
    }

    /**
     * Melder appen som "afspilning på ekstern enhed" (som Chromecast/Spotify Connect). Så sender Android
     * telefonens lydknapper hertil i stedet for at ændre telefonens egen lydstyrke – også med slukket skærm.
     */
    private fun startVolumeSession() {
        val provider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, AppState.volumePercent) {
            override fun onAdjustVolume(direction: Int) {
                when (direction) {
                    AudioManager.ADJUST_RAISE -> applyVolume(AppState.volumePercent + VOLUME_STEP)
                    AudioManager.ADJUST_LOWER -> applyVolume(AppState.volumePercent - VOLUME_STEP)
                }
            }

            override fun onSetVolumeTo(volume: Int) {
                applyVolume(volume)
            }
        }
        val s = MediaSession(this, "AirSync")
        s.setPlaybackToRemote(provider)
        s.setPlaybackState(playbackState(PlaybackState.STATE_PLAYING))
        s.isActive = true
        mediaSession = s
        volumeProvider = provider
        main.postDelayed(bumpPriority, PRIORITY_BUMP_MS)
    }

    /**
     * Android giver lydknapperne til den session, der senest er gået i gang med at spille. Når browseren
     * starter en ny video, tager den førstepladsen – derfor melder vi os jævnligt som "startet igen".
     */
    private val bumpPriority: Runnable = object : Runnable {
        override fun run() {
            val s = mediaSession ?: return
            s.setPlaybackState(playbackState(PlaybackState.STATE_PAUSED))
            s.setPlaybackState(playbackState(PlaybackState.STATE_PLAYING))
            main.postDelayed(this, PRIORITY_BUMP_MS)
        }
    }

    private fun playbackState(state: Int): PlaybackState =
        PlaybackState.Builder().setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()

    private fun stopVolumeSession() {
        main.removeCallbacks(bumpPriority)
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        volumeProvider = null
        AppState.volumeSetter = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirSync:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSync:stream").apply {
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
            .setContentTitle("AirSync streamer")
            .setContentText(AppState.selected.joinToString { it.name })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop
                ).build()
            )
            .build()
    }
}
