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
 */
class NowPlayingListener : NotificationListenerService()

/** Finder titel og kunstner/kanal på det, der spiller lige nu (fx YouTube i Brave). */
object NowPlaying {
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var sessions: List<MediaController> = emptyList()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sessions = list ?: emptyList()
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

    /** Begynder at følge med i, hvad der spiller. Gør ingenting uden adgang. */
    fun start(ctx: Context) {
        if (manager != null || !hasAccess(ctx)) return
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        try {
            sessions = m.getActiveSessions(component(ctx))
            m.addOnActiveSessionsChangedListener(sessionsListener, component(ctx), main)
            manager = m
            choose()
        } catch (e: SecurityException) {
            AppState.log("Ingen adgang til at se, hvad der spiller")
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        controller?.unregisterCallback(callback)
        controller = null
        sessions = emptyList()
        if (AppState.nowTitle != null || AppState.nowArtist != null) {
            AppState.nowTitle = null
            AppState.nowArtist = null
            AppState.notifyChanged()
        }
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
        val md = controller?.metadata
        val title = (md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        if (title == AppState.nowTitle && artist == AppState.nowArtist) return
        AppState.nowTitle = title
        AppState.nowArtist = artist
        AppState.notifyChanged()
    }
}
