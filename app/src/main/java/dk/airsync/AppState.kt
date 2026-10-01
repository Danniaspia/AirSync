package dk.airsync

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Speaker(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
) {
    val encryptionTypes: List<Int>
        get() = txt["et"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
}

/** Delt tilstand mellem skærm og service (samme proces). */
object AppState {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    var listener: (() -> Unit)? = null

    @Volatile var selected: List<Speaker> = emptyList()
    @Volatile var volumePercent: Int = 60

    /** Telefonens lydknapper styrer kun højttalerne (ikke telefonens egen lydstyrke). */
    @Volatile var volumeKeysControlSpeakers: Boolean = true

    /** Telefonens egen højttaler er lydløs, mens der streames – ligesom med Bluetooth. */
    @Volatile var muteLocalSpeaker: Boolean = true

    /** Sat af servicen mens der streames, så skyderen og lydknapperne holdes i takt. */
    @Volatile var volumeSetter: ((Int) -> Unit)? = null
    @Volatile var streamer: Streamer? = null

    @Volatile var running: Boolean = false
        set(value) {
            field = value
            notifyChanged()
        }

    fun log(msg: String) {
        Log.i("AirSync", msg)
        synchronized(lines) {
            lines.addLast("${timeFormat.format(Date())}  $msg")
            while (lines.size > 300) lines.removeFirst()
        }
        notifyChanged()
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun notifyChanged() {
        main.post { listener?.invoke() }
    }
}
