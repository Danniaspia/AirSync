package dk.airsync

import kotlin.math.min

/** Fælles ur for timing-svar og sync-pakker. Alle højttalere synkroniserer mod dette ur. */
object Clock {
    private const val NTP_EPOCH_OFFSET = 2208988800L
    private val baseNanos = System.nanoTime()
    private val baseUnixNanos = System.currentTimeMillis() * 1_000_000L

    /** 64-bit NTP-tid (sekunder.fraktion) for et System.nanoTime()-tidspunkt. */
    fun ntp(nanoTime: Long): Long {
        val unix = baseUnixNanos + (nanoTime - baseNanos)
        val sec = unix / 1_000_000_000L + NTP_EPOCH_OFFSET
        val frac = ((unix % 1_000_000_000L) shl 32) / 1_000_000_000L
        return (sec shl 32) or frac
    }
}

object Bytes {
    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun putU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v shr (56 - 8 * i)).toByte()
    }

    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun fromHex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Ringbuffer til 16-bit stereo PCM. Alle længder er multipla af 4 bytes (én frame). */
class PcmRing(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var readPos = 0
    private var size = 0

    @Synchronized
    fun available(): Int = size

    @Synchronized
    fun write(src: ByteArray, off: Int, len: Int) {
        var o = off
        var n = len
        if (n > capacity) {
            o += n - capacity
            n = capacity
        }
        val overflow = size + n - capacity
        if (overflow > 0) {
            readPos = (readPos + overflow) % capacity
            size -= overflow
        }
        var w = (readPos + size) % capacity
        var rem = n
        while (rem > 0) {
            val chunk = min(rem, capacity - w)
            System.arraycopy(src, o, buf, w, chunk)
            o += chunk
            rem -= chunk
            w = (w + chunk) % capacity
        }
        size += n
    }

    @Synchronized
    fun read(dst: ByteArray, len: Int): Int {
        val n = min(len, size)
        var r = 0
        while (r < n) {
            val chunk = min(n - r, capacity - readPos)
            System.arraycopy(buf, readPos, dst, r, chunk)
            r += chunk
            readPos = (readPos + chunk) % capacity
        }
        size -= n
        return n
    }

    @Synchronized
    fun skip(len: Int) {
        val n = min(len, size)
        readPos = (readPos + n) % capacity
        size -= n
    }
}

/**
 * Pakker PCM ind i ukomprimerede ALAC-frames ("escape"-frames), som alle AirPlay-modtagere forstår.
 * Layout: tag ID_CPE(3) instans(4) ubrugt(12) partial(1) shift(2) escape(1), samples L/R 16 bit, ID_END(3).
 */
class AlacEncoder {
    fun encode(pcmLittleEndian: ByteArray): ByteArray {
        val frames = pcmLittleEndian.size / 4
        val w = BitWriter((23 + frames * 32 + 3 + 7) / 8)
        w.write(1, 3)
        w.write(0, 4)
        w.write(0, 12)
        w.write(0, 1)
        w.write(0, 2)
        w.write(1, 1)
        for (f in 0 until frames) {
            val o = f * 4
            val left = (pcmLittleEndian[o].toInt() and 0xFF) or ((pcmLittleEndian[o + 1].toInt() and 0xFF) shl 8)
            val right = (pcmLittleEndian[o + 2].toInt() and 0xFF) or ((pcmLittleEndian[o + 3].toInt() and 0xFF) shl 8)
            w.write(left, 16)
            w.write(right, 16)
        }
        w.write(7, 3)
        return w.buf
    }

    private class BitWriter(size: Int) {
        val buf = ByteArray(size)
        private var pos = 0

        fun write(value: Int, bits: Int) {
            var i = bits - 1
            while (i >= 0) {
                if ((value ushr i) and 1 != 0) {
                    val idx = pos ushr 3
                    buf[idx] = (buf[idx].toInt() or (0x80 ushr (pos and 7))).toByte()
                }
                pos++
                i--
            }
        }
    }
}
