package dk.airsync

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Opfanger telefonens lyd og sender den til alle valgte højttalere.
 *
 * Synkronisering: alle højttalere får præcis de samme RTP-pakker og sync-pakker, og de stiller
 * deres ure efter telefonens ur via timing-porten. Hver pakke afspilles LATENCY_FRAMES efter sin
 * planlagte afsendelsestid, så højttalerne spiller i takt.
 */
class Streamer(private val projection: MediaProjection, private val speakers: List<Speaker>) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FRAMES_PER_PACKET = 352
        const val BYTES_PER_PACKET = FRAMES_PER_PACKET * 4

        /** Fælles forsinkelse (2 s), som giver plads til at gensende tabte pakker over WiFi. */
        const val LATENCY_FRAMES = 88200L

        private const val HISTORY = 1024
        private const val CUSHION_BYTES = SAMPLE_RATE / 10 * 4
        private const val MAX_FILL_BYTES = SAMPLE_RATE / 2 * 4
        private const val MASK32 = 0xFFFFFFFFL

        /** Så længe uden tidsforespørgsel, før en højttaler regnes for overtaget (de spørger ca. hvert 3. sek.). */
        private const val TIMING_SILENCE_MS = 10_000L

        /** Pause mellem forsøg på at tage en overtaget højttaler tilbage. */
        private const val RECLAIM_DELAY_MS = 3_000L
    }

    @Volatile private var running = false
    private val sessions = CopyOnWriteArrayList<RaopSession>()
    private val ring = PcmRing(SAMPLE_RATE * 4)
    private val history = arrayOfNulls<ByteArray>(HISTORY)
    private val random = SecureRandom()
    private val seq0 = random.nextInt(0x10000)
    private val rtp0 = random.nextInt().toLong() and MASK32
    private val ssrc = random.nextInt().toLong() and MASK32
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    @Volatile private var underruns = 0
    @Volatile private var streaming = false
    @Volatile private var streamT0 = 0L
    @Volatile private var nextPacket = 0L
    private val volumeExecutor = Executors.newSingleThreadExecutor()
    private val volumePending = AtomicBoolean(false)

    /** Seneste tidsforespørgsel fra hver højttaler (IP → ms). Stilhed betyder, at den er taget af en anden. */
    private val lastTiming = ConcurrentHashMap<String, Long>()

    /** Blokerer mens der forbindes. Kør på en baggrundstråd. */
    fun start(): Boolean {
        running = true
        try {
            val timing = DatagramSocket().also { timingSocket = it }
            val control = DatagramSocket().also { controlSocket = it }
            dataSocket = DatagramSocket()
            thread(name = "timing") { timingLoop(timing) }
            thread(name = "control") { controlLoop(control) }

            speakers.map { sp ->
                thread(name = "connect") {
                    val s = RaopSession(sp, control.localPort, timing.localPort)
                    if (s.connect(seq0, rtp0, AppState.effectiveVolume(sp.id))) {
                        sessions.add(s)
                        if (!running && sessions.remove(s)) s.close()
                    }
                }
            }.forEach { it.join() }

            if (!running) return false
            AppState.keepSelected(sessions.map { it.speaker.id }.toSet())
            AppState.notifyChanged()
            if (sessions.isEmpty()) {
                AppState.log("Ingen højttalere kunne forbindes.")
                stop()
                return false
            }
            startCapture()
            thread(name = "sender", priority = Thread.MAX_PRIORITY) { senderLoop() }
            thread(name = "keepalive") { keepAliveLoop() }
            thread(name = "monitor") { monitorLoop() }
            AppState.log("Streamer til ${sessions.size} højttaler(e). Lyden kommer ca. 2 sek. forsinket.")
            return true
        } catch (e: Exception) {
            AppState.log("Kunne ikke starte: ${e.message ?: e.javaClass.simpleName}")
            stop()
            return false
        }
    }

    /**
     * Tager en højttaler med i en afspilning, der allerede kører. Den får de samme pakker og samme ur
     * som de andre fra den aktuelle position, så den spiller i takt med dem fra første tone.
     */
    fun addSpeaker(sp: Speaker): Boolean {
        if (!running || !streaming || controlSocket == null || timingSocket == null) return false
        if (sessions.any { it.speaker.id == sp.id } || !AppState.connecting.add(sp.id)) return true
        AppState.notifyChanged()
        thread(name = "add") {
            if (connectLive(sp)) {
                AppState.log("${sp.name} spiller nu med")
            } else {
                AppState.removeSelected(sp.id)
            }
            AppState.connecting.remove(sp.id)
            AppState.notifyChanged()
        }
        return true
    }

    /**
     * Forbinder en højttaler til den kørende strøm fra den aktuelle position og giver den straks
     * en sync-pakke. Blokerer; kør på en baggrundstråd. Returnerer true, hvis den nu spiller med.
     */
    private fun connectLive(sp: Speaker): Boolean {
        val control = controlSocket ?: return false
        val timing = timingSocket ?: return false
        // Tabte pakker mellem dette punkt og forbindelsen gensendes fra historikken.
        val n = nextPacket
        val seq = ((seq0 + n) and 0xFFFF).toInt()
        val ts = (rtp0 + n * FRAMES_PER_PACKET) and MASK32
        val s = RaopSession(sp, control.localPort, timing.localPort)
        val ok = s.connect(seq, ts, AppState.effectiveVolume(sp.id))
        if (ok && running && AppState.selected.any { it.id == sp.id }) {
            lastTiming.remove(s.address.hostAddress)
            sessions.add(s)
            val next = nextPacket
            sendSync(dueTime(streamT0, next), (rtp0 + next * FRAMES_PER_PACKET) and MASK32, first = true, only = s)
            return true
        }
        if (ok) s.close()
        return false
    }

    /**
     * Holder øje med, om en højttaler er blevet taget af en anden enhed (fx via Bluetooth):
     * forbindelsen er lukket, eller højttaleren er holdt op med at synkronisere sit ur med os.
     */
    private fun monitorLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            for (s in sessions) {
                // Tidstjekket bruges kun, hvis højttaleren har bedt om tid mindst én gang.
                val heard = lastTiming[s.address.hostAddress]
                val silent = heard != null && now - heard > TIMING_SILENCE_MS
                if (s.lost || silent) onSpeakerLost(s)
            }
        }
    }

    private fun onSpeakerLost(s: RaopSession) {
        if (!sessions.remove(s)) return
        thread(name = "drop") { s.close() }
        val sp = s.speaker
        AppState.log("${sp.name} blev overtaget af en anden enhed")
        if (!AppState.autoReclaim) {
            AppState.removeSelected(sp.id)
            AppState.notifyChanged()
            return
        }
        if (!AppState.reclaiming.add(sp.id)) return
        AppState.notifyChanged()
        thread(name = "reclaim") {
            var attempt = 0
            while (running && AppState.autoReclaim && AppState.selected.any { it.id == sp.id }) {
                try {
                    Thread.sleep(RECLAIM_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                attempt++
                if (connectLive(sp)) {
                    AppState.log("${sp.name} er taget tilbage")
                    break
                }
                if (attempt % 10 == 0) AppState.log("${sp.name}: prøver stadig at tage den tilbage…")
            }
            AppState.reclaiming.remove(sp.id)
            AppState.notifyChanged()
        }
    }

    /** Fjerner en højttaler fra afspilningen. De andre spiller videre. */
    fun removeSpeaker(id: String) {
        val s = sessions.firstOrNull { it.speaker.id == id } ?: return
        sessions.remove(s)
        thread(name = "remove") { s.close() }
        AppState.log("${s.speaker.name} er stoppet")
    }

    /**
     * Sender den aktuelle lydstyrke (samlet × højttalerens eget niveau) til hver højttaler.
     * Mange hurtige ændringer samles, så kun den seneste sendes.
     */
    fun updateVolumes() {
        if (volumePending.getAndSet(true)) return
        try {
            volumeExecutor.execute {
                volumePending.set(false)
                for (s in sessions) s.setVolume(AppState.effectiveVolume(s.speaker.id))
            }
        } catch (_: Exception) {
            volumePending.set(false)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        // Selve godkendelsen (projection) ejes af StreamService, så den kan genbruges ved næste Start.
        volumeExecutor.shutdown()
        val toClose = sessions.toList()
        sessions.clear()
        thread(name = "teardown") {
            toClose.forEach { it.close() }
            dataSocket?.close()
            controlSocket?.close()
            timingSocket?.close()
        }
        AppState.log("Stoppet.")
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 2, BYTES_PER_PACKET * 16))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("lydoptagelse kunne ikke startes")
        }
        rec.startRecording()
        record = rec
        thread(name = "capture", priority = Thread.MAX_PRIORITY) {
            val buf = ByteArray(BYTES_PER_PACKET * 4)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        ring.write(buf, 0, n - n % 4)
                    } else if (n < 0) {
                        AppState.log("Lydoptagelse fejlede (kode $n)")
                        break
                    }
                }
            } finally {
                rec.release()
            }
        }
    }

    private fun dueTime(t0: Long, packet: Long): Long =
        t0 + packet * FRAMES_PER_PACKET * 1_000_000_000L / SAMPLE_RATE

    private fun senderLoop() {
        val data = dataSocket ?: return
        val encoder = AlacEncoder()
        val pcm = ByteArray(BYTES_PER_PACKET)
        val t0 = System.nanoTime() + 100_000_000L
        streamT0 = t0
        var n = 0L
        var buffering = true
        var lastStatus = t0
        sendSync(t0, rtp0, first = true)
        streaming = true

        while (running) {
            val due = dueTime(t0, n)
            while (true) {
                val wait = due - System.nanoTime()
                if (wait <= 0) break
                LockSupport.parkNanos(wait)
            }

            var avail = ring.available()
            if (avail > MAX_FILL_BYTES) {
                ring.skip(avail - CUSHION_BYTES)
                avail = ring.available()
            }
            if (buffering && avail >= CUSHION_BYTES) buffering = false
            if (!buffering && avail >= BYTES_PER_PACKET) {
                ring.read(pcm, BYTES_PER_PACKET)
            } else {
                if (!buffering) underruns++
                buffering = true
                pcm.fill(0)
            }

            val ts = (rtp0 + n * FRAMES_PER_PACKET) and MASK32
            val seq = ((seq0 + n) and 0xFFFF).toInt()
            val packet = rtpPacket(seq, ts, encoder.encode(pcm), first = n == 0L)
            history[seq % HISTORY] = packet
            for (s in sessions) send(data, packet, s.address, s.serverPort)
            n++
            nextPacket = n

            if (n % 125 == 0L) {
                sendSync(dueTime(t0, n), (rtp0 + n * FRAMES_PER_PACKET) and MASK32, first = false)
            }
            if (due - lastStatus > 30_000_000_000L) {
                lastStatus = due
                val bufferMs = ring.available() * 1000L / (SAMPLE_RATE * 4)
                AppState.log("Status: buffer $bufferMs ms, udfald i alt $underruns")
            }
        }
    }

    private fun rtpPacket(seq: Int, ts: Long, payload: ByteArray, first: Boolean): ByteArray {
        val p = ByteArray(12 + payload.size)
        p[0] = 0x80.toByte()
        p[1] = if (first) 0xE0.toByte() else 0x60.toByte()
        p[2] = (seq shr 8).toByte()
        p[3] = seq.toByte()
        Bytes.putU32(p, 4, ts)
        Bytes.putU32(p, 8, ssrc)
        System.arraycopy(payload, 0, p, 12, payload.size)
        return p
    }

    /** Fortæller højttalerne: frame [ts] - latency afspilles på NTP-tidspunktet for [dueNanos]. */
    private fun sendSync(dueNanos: Long, ts: Long, first: Boolean, only: RaopSession? = null) {
        val control = controlSocket ?: return
        val p = ByteArray(20)
        p[0] = if (first) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xD4.toByte()
        p[2] = 0x00.toByte()
        p[3] = 0x07.toByte()
        Bytes.putU32(p, 4, ts - LATENCY_FRAMES)
        Bytes.putU64(p, 8, Clock.ntp(dueNanos))
        Bytes.putU32(p, 16, ts)
        if (only != null) {
            send(control, p, only.address, only.controlPort)
        } else {
            for (s in sessions) send(control, p, s.address, s.controlPort)
        }
    }

    private fun send(socket: DatagramSocket, bytes: ByteArray, addr: InetAddress, port: Int) {
        if (port <= 0) return
        try {
            socket.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (_: Exception) {
        }
    }

    /** Besvarer højttalernes anmodninger om at gensende tabte pakker. */
    private fun controlLoop(socket: DatagramSocket) {
        val buf = ByteArray(1500)
        while (running) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(dp)
            } catch (_: Exception) {
                if (running) continue else break
            }
            if (dp.length < 8 || (buf[1].toInt() and 0x7F) != 0x55) continue
            val first = Bytes.u16(buf, 4)
            val count = min(Bytes.u16(buf, 6), 128)
            for (i in 0 until count) {
                val seq = (first + i) and 0xFFFF
                val pkt = history[seq % HISTORY] ?: continue
                if (Bytes.u16(pkt, 2) != seq) continue
                val out = ByteArray(pkt.size + 4)
                out[0] = 0x80.toByte()
                out[1] = 0xD6.toByte()
                out[2] = buf[2]
                out[3] = buf[3]
                System.arraycopy(pkt, 0, out, 4, pkt.size)
                try {
                    socket.send(DatagramPacket(out, out.size, dp.socketAddress))
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Besvarer højttalernes NTP-lignende tidsforespørgsler, så de kan følge telefonens ur. */
    private fun timingLoop(socket: DatagramSocket) {
        val buf = ByteArray(128)
        while (running) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(dp)
            } catch (_: Exception) {
                if (running) continue else break
            }
            if (dp.length < 32 || (buf[1].toInt() and 0x7F) != 0x52) continue
            val received = Clock.ntp(System.nanoTime())
            dp.address?.hostAddress?.let { lastTiming[it] = System.currentTimeMillis() }
            val out = ByteArray(32)
            out[0] = 0x80.toByte()
            out[1] = 0xD3.toByte()
            out[2] = 0x00.toByte()
            out[3] = 0x07.toByte()
            System.arraycopy(buf, 24, out, 8, 8)
            Bytes.putU64(out, 16, received)
            Bytes.putU64(out, 24, Clock.ntp(System.nanoTime()))
            try {
                socket.send(DatagramPacket(out, out.size, dp.socketAddress))
            } catch (_: Exception) {
            }
        }
    }

    private fun keepAliveLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            for (s in sessions) if (running) s.keepAlive()
        }
    }
}
