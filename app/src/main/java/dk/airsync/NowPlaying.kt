package dk.airsync

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Kræves af Android for at måtte se andre apps' medieafspilning ("Adgang til notifikationer").
 * AirTooth bruger den kun til titlen på det, der spiller, og til at finde den afspiller, som
 * medienotifikationen i panelet styrer. Indholdet af andre notifikationer læses ikke.
 *
 * Android forbinder lytteren, så snart adgangen er givet; så begynder vi at følge med med det samme.
 */
class NowPlayingListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NowPlayingListener? = null
            private set
    }

    override fun onListenerConnected() {
        instance = this
        AppState.appContext = applicationContext
        NowPlaying.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        instance = null
        requestRebind(ComponentName(this, NowPlayingListener::class.java))
    }

    /** Afspillerne bag de medienotifikationer, der vises i panelet lige nu. */
    fun mediaNotificationControllers(): List<MediaController> {
        val list = try {
            activeNotifications
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return list.mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val token = if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            token?.let { MediaController(applicationContext, it) }
        }
    }
}

/** Finder titel og kunstner/kanal på det, der spiller lige nu (fx YouTube i Brave). */
object NowPlaying {
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var sessions: List<MediaController> = emptyList()
    private var lastSessionsLog = ""
    private var lastAccessLog: Boolean? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sessions = list ?: emptyList()
        logSessions()
        choose()
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            choose()
        }

        override fun onSessionDestroyed() {
            choose()
        }
    }

    private fun component(ctx: Context) = ComponentName(ctx, NowPlayingListener::class.java)

    fun hasAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(ctx))

    /** Begynder at følge med i, hvad der spiller. Kan kaldes mange gange; gør kun noget første gang. */
    fun start(ctx: Context) {
        main.post { startOnMain(ctx.applicationContext) }
    }

    private fun startOnMain(ctx: Context) {
        if (manager != null) return
        val access = hasAccess(ctx)
        if (access != lastAccessLog) {
            lastAccessLog = access
            AppState.log("Titel: adgang ${if (access) "ja" else "nej"}")
        }
        if (!access) return
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        try {
            sessions = m.getActiveSessions(component(ctx))
            m.addOnActiveSessionsChangedListener(sessionsListener, component(ctx), main)
            manager = m
            logSessions()
            choose()
        } catch (e: Exception) {
            // Typisk fordi lytteren ikke er forbundet endnu – onListenerConnected prøver igen.
            AppState.log("Titel: kunne ikke læse afspillere (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /**
     * Beder den app, der spiller (fx Brave), direkte om at gå til næste nummer.
     *
     * YouTube i browseren tilbyder ofte ikke "næste" uden en playliste. Så spoler vi i stedet til
     * lige før slutningen, og YouTube starter selv næste video via autoplay.
     * False, hvis vi ikke kan se nogen afspiller (fx uden "Adgang til notifikationer").
     */
    fun skipToNext(): Boolean {
        // Brug samme afspiller som medienotifikationen i panelet (den, hvor ⏭ virker). Brave kan have
        // flere afspillere åbne, og den første på listen er ikke nødvendigvis den, der spiller.
        val fromPanel = NowPlayingListener.instance?.mediaNotificationControllers().orEmpty()
        val c = fromPanel.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: fromPanel.firstOrNull()
            ?: controller
            ?: return false
        val actions = c.playbackState?.actions ?: 0L
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        when {
            actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L -> {
                c.transportControls.skipToNext()
                AppState.log("Næste nummer sendt til ${c.packageName}")
            }
            actions and PlaybackState.ACTION_SEEK_TO != 0L && duration > 3_000L -> {
                c.transportControls.seekTo(duration - 1_000L)
                AppState.log("Næste nummer: spoler til slutningen i ${c.packageName} (autoplay tager næste)")
            }
            else -> {
                // Vi ved ikke, hvad appen understøtter – prøv "næste" alligevel.
                c.transportControls.skipToNext()
                AppState.log("Næste nummer sendt til ${c.packageName} (appen melder ikke, om den kan)")
            }
        }
        return true
    }

    /** Stopper helt (ved Afslut). */

    fun stop() {
        main.post {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
            manager = null
            controller?.unregisterCallback(callback)
            controller = null
            sessions = emptyList()
            lastSessionsLog = ""
            if (AppState.nowTitle != null || AppState.nowArtist != null) {
                AppState.nowTitle = null
                AppState.nowArtist = null
                AppState.notifyChanged()
            }
        }
    }

    private fun logSessions() {
        val text = if (sessions.isEmpty()) {
            "ingen afspillere"
        } else {
            "${sessions.size} afspiller(e) – " + sessions.joinToString { c ->
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                c.packageName + if (playing) " (spiller)" else ""
            }
        }
        if (text == lastSessionsLog) return
        lastSessionsLog = text
        AppState.log("Titel: $text")
    }

    /** Vælger den app, der spiller lige nu (ellers den senest aktive). */
    private fun choose() {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val title = (md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        if (title == AppState.nowTitle && artist == AppState.nowArtist) return
        AppState.nowTitle = title
        AppState.nowArtist = artist
        AppState.log(
            when {
                title != null -> "Titel: ${AppState.nowPlayingLabel()}"
                c != null -> "Titel: ingen titel fra ${c.packageName}"
                else -> "Titel: intet spiller"
            }
        )
        AppState.notifyChanged()
    }
}
