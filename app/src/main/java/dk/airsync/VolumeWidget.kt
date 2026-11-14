package dk.airsync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Widget til startskærmen: samlet lydstyrke og − / + for hver højttaler, der spiller med,
 * plus spil/stop. Viser AppState, så den følger appen, lydknapperne og notifikationen.
 */
class VolumeWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_MASTER_UP = "dk.airsync.widget.MASTER_UP"
        private const val ACTION_MASTER_DOWN = "dk.airsync.widget.MASTER_DOWN"
        private const val ACTION_SPEAKER_UP = "dk.airsync.widget.SPEAKER_UP"
        private const val ACTION_SPEAKER_DOWN = "dk.airsync.widget.SPEAKER_DOWN"
        private const val ACTION_PLAY_STOP = "dk.airsync.widget.PLAY_STOP"
        private const val EXTRA_ID = "speaker"

        private val ROWS = intArrayOf(R.id.row_s0, R.id.row_s1, R.id.row_s2, R.id.row_s3)
        private val NAMES = intArrayOf(R.id.s0_name, R.id.s1_name, R.id.s2_name, R.id.s3_name)
        private val VALUES = intArrayOf(R.id.s0_value, R.id.s1_value, R.id.s2_value, R.id.s3_value)
        private val BARS = intArrayOf(R.id.s0_bar, R.id.s1_bar, R.id.s2_bar, R.id.s3_bar)
        private val MINUS = intArrayOf(R.id.s0_minus, R.id.s1_minus, R.id.s2_minus, R.id.s3_minus)
        private val PLUS = intArrayOf(R.id.s0_plus, R.id.s1_plus, R.id.s2_plus, R.id.s3_plus)

        private val DARK = 0xFF111113.toInt()
        private val LIGHT = 0xFFF2EFEA.toInt()

        /** Tegner alle AirTooth-widgets på startskærmen forfra ud fra den aktuelle tilstand. */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, VolumeWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, build(context))
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_volume)
            val running = AppState.running
            val standby = AppState.standby
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.header_area, open)

            v.setTextViewText(
                R.id.status,
                when {
                    running -> "· spiller"
                    standby -> "· klar"
                    else -> "· slukket"
                }
            )

            // Spil/stop: uden godkendelse kan der kun startes fra appen, så knappen åbner den.
            v.setImageViewResource(R.id.btn_play, if (running) R.drawable.ic_stop else R.drawable.ic_play)
            val active = running || standby
            v.setInt(R.id.btn_play, "setBackgroundResource", if (active) R.drawable.widget_button_accent else R.drawable.widget_button)
            v.setInt(R.id.btn_play, "setColorFilter", if (active) DARK else LIGHT)
            v.setOnClickPendingIntent(R.id.btn_play, if (active) broadcast(ctx, ACTION_PLAY_STOP, 1) else open)

            if (!running) {
                v.setViewVisibility(R.id.info, View.VISIBLE)
                v.setTextViewText(
                    R.id.info,
                    if (standby) {
                        AppState.selected.joinToString(" og ") { it.name }.ifEmpty { "Klar" } + " · tryk for at spille"
                    } else {
                        "Tryk for at åbne AirTooth"
                    }
                )
                v.setViewVisibility(R.id.row_master, View.GONE)
                v.setViewVisibility(R.id.divider, View.GONE)
                for (row in ROWS) v.setViewVisibility(row, View.GONE)
                return v
            }

            v.setViewVisibility(R.id.info, View.GONE)
            v.setViewVisibility(R.id.row_master, View.VISIBLE)
            v.setTextViewText(R.id.master_value, "${AppState.volumePercent} %")
            v.setProgressBar(R.id.master_bar, 100, AppState.volumePercent, false)
            v.setOnClickPendingIntent(R.id.master_minus, broadcast(ctx, ACTION_MASTER_DOWN, 2))
            v.setOnClickPendingIntent(R.id.master_plus, broadcast(ctx, ACTION_MASTER_UP, 3))

            val speakers = AppState.selected.take(ROWS.size)
            v.setViewVisibility(R.id.divider, if (speakers.isEmpty()) View.GONE else View.VISIBLE)
            for (i in ROWS.indices) {
                val sp = speakers.getOrNull(i)
                if (sp == null) {
                    v.setViewVisibility(ROWS[i], View.GONE)
                    continue
                }
                val level = AppState.speakerLevel(sp.id)
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(NAMES[i], sp.name)
                v.setTextViewText(VALUES[i], "$level %")
                v.setProgressBar(BARS[i], 100, level, false)
                v.setOnClickPendingIntent(MINUS[i], broadcast(ctx, ACTION_SPEAKER_DOWN, 10 + i * 2, sp.id))
                v.setOnClickPendingIntent(PLUS[i], broadcast(ctx, ACTION_SPEAKER_UP, 11 + i * 2, sp.id))
            }
            return v
        }

        private fun broadcast(ctx: Context, action: String, requestCode: Int, speakerId: String? = null): PendingIntent {
            val intent = Intent(ctx, VolumeWidget::class.java).setAction(action)
            if (speakerId != null) intent.putExtra(EXTRA_ID, speakerId)
            return PendingIntent.getBroadcast(
                ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        AppState.appContext = context.applicationContext
        appWidgetManager.updateAppWidget(appWidgetIds, build(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        AppState.appContext = context.applicationContext
        val prefs = context.getSharedPreferences("airsync", Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_MASTER_UP -> changeMaster(prefs, AppState.VOLUME_STEP)
            ACTION_MASTER_DOWN -> changeMaster(prefs, -AppState.VOLUME_STEP)
            ACTION_SPEAKER_UP, ACTION_SPEAKER_DOWN -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val step = if (intent.action == ACTION_SPEAKER_UP) AppState.VOLUME_STEP else -AppState.VOLUME_STEP
                val current = AppState.speakerLevels.getOrPut(id) { prefs.getInt("level_$id", 100) }
                val level = (current + step).coerceIn(0, 100)
                AppState.speakerLevels[id] = level
                AppState.streamer?.updateVolumes()
                prefs.edit().putInt("level_$id", level).apply()
                AppState.notifyChanged()
            }
            ACTION_PLAY_STOP -> {
                val action = when {
                    AppState.running -> StreamService.ACTION_STOP
                    AppState.standby -> StreamService.ACTION_PLAY
                    else -> null
                }
                if (action != null) {
                    context.startService(Intent(context, StreamService::class.java).setAction(action))
                }
            }
            else -> return
        }
        updateAll(context)
    }

    /** Samme vej som telefonens lydknapper, så appen og højttalerne følger med. */
    private fun changeMaster(prefs: android.content.SharedPreferences, step: Int) {
        val value = (AppState.volumePercent + step).coerceIn(0, 100)
        val setter = AppState.volumeSetter
        if (setter != null) setter(value) else AppState.volumePercent = value
        prefs.edit().putInt("volume", value).apply()
        AppState.notifyChanged()
    }
}
