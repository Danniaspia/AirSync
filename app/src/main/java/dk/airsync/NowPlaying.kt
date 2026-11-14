package dk.airsync

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Kræves af Android for at måtte se andre apps' medieafspilning ("Adgang til notifikationer").
 * AirTooth bruger den kun til at læse titlen på det, der spiller – den læser ikke notifikationer.
 *
 * Android forbinder lytteren, så snart adgangen er givet; så begynder vi at følge med med det samme.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AppState.appContext = applicationContext
        NowPlaying.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, NowPlayingListener::class.java))
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
