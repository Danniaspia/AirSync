package dk.airsync

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale

/** RTSP-styreforbindelse til én AirPlay 1 (RAOP)-højttaler. Selve lyden sendes af [Streamer] over UDP. */
class RaopSession(
    val speaker: Speaker,
    private val localControlPort: Int,
    private val localTimingPort: Int,
) {
    private class Response(val code: Int, val headers: Map<String, String>)

    companion object {
        // 0x01 = ukrypteret, efterfulgt af en 32-byte curve25519-nøgle (som pyatv bruger).
        private val AUTH_SETUP_BODY = Bytes.fromHex(
            "01 5902ede90d4ef2bd 4cb68a6330038207 a94dbd50d8aa465b 5d8c012a0c7e1d4e"
        )
    }

    lateinit var address: InetAddress
        private set
    var serverPort = 0
        private set
    var controlPort = 0
        private set

    private var socket: Socket? = null
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private lateinit var url: String
    private var cseq = 0
    private var session: String? = null
    private val random = SecureRandom()
    private val clientId = (1..16).map { "0123456789ABCDEF"[random.nextInt(16)] }.joinToString("")
    private val activeRemote = (random.nextInt() and 0x7FFFFFFF).toString()
    private var feedbackSupported = false
    private var lastKeepAlive = 0L
    @Volatile private var lastVolume = -1

    private fun log(msg: String) = AppState.log("${speaker.name}: $msg")

    fun connect(seq0: Int, rtp0: Long, volumePercent: Int): Boolean {
        return try {
            address = InetAddress.getByName(speaker.host)
            log("forbinder til ${speaker.host}:${speaker.port} (et=${speaker.txt["et"]}, cn=${speaker.txt["cn"]})")
            val s = Socket()
            s.connect(InetSocketAddress(address, speaker.port), 5000)
            s.soTimeout = 5000
            s.tcpNoDelay = true
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()

            val local = s.localAddress.hostAddress
            val sid = (random.nextInt() and 0x7FFFFFFF).toString()
            url = "rtsp://$local/$sid"

            if (4 in speaker.encryptionTypes) {
                val auth = request("POST", "/auth-setup", body = AUTH_SETUP_BODY, contentType = "application/octet-stream")
                if (auth.code != 200) log("auth-setup svarede ${auth.code} (fortsætter)")
            }

            val options = request("OPTIONS", "*")
            if (options.code != 200) log("OPTIONS svarede ${options.code} (fortsætter)")

            val sdp = "v=0\r\n" +
                "o=iTunes $sid 0 IN IP4 $local\r\n" +
                "s=iTunes\r\n" +
                "c=IN IP4 ${address.hostAddress}\r\n" +
                "t=0 0\r\n" +
                "m=audio 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 AppleLossless\r\n" +
                "a=fmtp:96 ${Streamer.FRAMES_PER_PACKET} 0 16 40 10 14 2 255 0 0 ${Streamer.SAMPLE_RATE}\r\n"
            expectOk(request("ANNOUNCE", url, body = sdp.toByteArray(Charsets.UTF_8), contentType = "application/sdp"), "ANNOUNCE")

            val setup = request(
                "SETUP", url,
                headers = listOf(
                    "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                        "control_port=$localControlPort;timing_port=$localTimingPort"
                ),
            )
            expectOk(setup, "SETUP")
            session = setup.headers["session"]?.substringBefore(';')?.trim()
            val transport = setup.headers["transport"] ?: ""
            serverPort = transportParam(transport, "server_port") ?: throw IOException("SETUP gav ingen server_port")
            controlPort = transportParam(transport, "control_port") ?: 0
            if (controlPort == 0) log("ingen control_port – synkronisering kan halte")

            val record = request(
                "RECORD", url,
                headers = listOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq0;rtptime=$rtp0"),
            )
            expectOk(record, "RECORD")
            record.headers["audio-latency"]?.let { log("modtagerens egen forsinkelse: $it samples") }

            setVolume(volumePercent)
            feedbackSupported = try {
                request("POST", "/feedback").code == 200
            } catch (_: Exception) {
                false
            }
            lastKeepAlive = System.currentTimeMillis()
            log("forbundet ✓")
            true
        } catch (e: Exception) {
            log("fejl: ${e.message ?: e.javaClass.simpleName}")
            closeSocket()
            false
        }
    }

    fun setVolume(percent: Int) {
        if (percent == lastVolume) return
        val db = if (percent <= 0) -144.0 else -30.0 + 30.0 * percent / 100.0
        try {
            val body = String.format(Locale.US, "volume: %.6f\r\n", db).toByteArray(Charsets.UTF_8)
            request("SET_PARAMETER", url, body = body, contentType = "text/parameters")
            lastVolume = percent
        } catch (e: Exception) {
            log("lydstyrke fejlede: ${e.message}")
        }
    }

    /** Holder forbindelsen i live. Kaldes ca. hvert sekund. */
    fun keepAlive() {
        val now = System.currentTimeMillis()
        val interval = if (feedbackSupported) 2_000L else 15_000L
        if (now - lastKeepAlive < interval) return
        lastKeepAlive = now
        try {
            if (feedbackSupported) request("POST", "/feedback") else request("OPTIONS", "*")
        } catch (e: Exception) {
            log("keep-alive fejlede: ${e.message}")
        }
    }

    fun close() {
        try {
            if (session != null) request("TEARDOWN", url)
        } catch (_: Exception) {
        }
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun expectOk(r: Response, step: String) {
        if (r.code != 200) {
            val hint = when (r.code) {
                401, 403 -> " – højttaleren kræver kode eller parring"
                453 -> " – højttaleren er optaget af en anden afspiller"
                else -> ""
            }
            throw IOException("$step svarede ${r.code}$hint")
        }
    }

    private fun transportParam(transport: String, key: String): Int? =
        transport.split(';').firstOrNull { it.trim().startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()

    @Synchronized
    private fun request(
        method: String,
        uri: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): Response {
        if (socket == null) throw IOException("ikke forbundet")
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: iTunes/7.6.2 (Windows; N;)\r\n")
        sb.append("Client-Instance: $clientId\r\n")
        sb.append("DACP-ID: $clientId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        session?.let { sb.append("Session: $it\r\n") }
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null) {
            contentType?.let { sb.append("Content-Type: $it\r\n") }
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let { output.write(it) }
        output.flush()
        return readResponse()
    }

    private fun readLine(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("forbindelsen blev lukket")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString("ISO-8859-1")
    }

    private fun readResponse(): Response {
        val status = readLine()
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("ugyldigt svar: $status")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        var remaining = headers["content-length"]?.toIntOrNull() ?: 0
        val skip = ByteArray(1024)
        while (remaining > 0) {
            val n = input.read(skip, 0, minOf(remaining, skip.size))
            if (n < 0) throw IOException("forbindelsen blev lukket")
            remaining -= n
        }
        return Response(code, headers)
    }
}
