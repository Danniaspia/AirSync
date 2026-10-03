package dk.airsync

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    /** Højttalerne i afspilningen. Kan ændres, mens der spilles. */
    @Volatile var selected: List<Speaker> = emptyList()

    /** Højttalere, der er ved at forbinde til en afspilning i gang. */
    val connecting: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Tag automatisk en højttaler tilbage, hvis en anden enhed (fx via Bluetooth) tager den. */
    @Volatile var autoReclaim: Boolean = true

    /** Højttalere, der er taget af en anden enhed, og som AirTooth er ved at tage tilbage. */
    val reclaiming: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized
    fun addSelected(sp: Speaker) {
        if (selected.none { it.id == sp.id }) selected = selected + sp
    }

    @Synchronized
    fun removeSelected(id: String) {
        selected = selected.filter { it.id != id }
    }

    @Synchronized
    fun keepSelected(ids: Set<String>) {
        selected = selected.filter { it.id in ids }
    }
    /** Så meget ét tryk på en lydknap ændrer højttalerne (procentpoint). */
    const val VOLUME_STEP = 5

    /** Stop er trykket, men godkendelsen holdes, så næste Start ikke viser Androids dialog. */
    @Volatile var standby: Boolean = false

    /** Samlet lydstyrke (lydknapperne og hovedskyderen). */
    @Volatile var volumePercent: Int = 60

    /** Hver højttalers eget niveau (0–100 %) i forhold til den samlede lydstyrke. */
    val speakerLevels = ConcurrentHashMap<String, Int>()

    fun speakerLevel(id: String): Int = speakerLevels[id] ?: 100

    /** Den lydstyrke, en bestemt højttaler faktisk skal have. */
    fun effectiveVolume(id: String): Int = volumePercent * speakerLevel(id) / 100

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
        Log.i("AirTooth", msg)
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
