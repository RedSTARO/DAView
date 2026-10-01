package com.daview.server.library

import java.io.ByteArrayOutputStream

/**
 * Transport streams put together packet by packet, for the tests: program
 * tables with their CRCs, PES packets with timestamps, an H.264 sequence
 * parameter set the way an encoder writes one, in 188-, 192- (Blu-ray) and
 * 204-byte packets. Nothing here is taken from a real file.
 */
internal object TransportStreams {


    data class Es(val type: Int, val pid: Int, val descriptors: ByteArray = ByteArray(0))

    fun reader(bytes: ByteArray) = MkvProbe.RangeReader { start, length ->
        if (start >= bytes.size) ByteArray(0) else bytes.copyOfRange(start.toInt(), minOf(start + length, bytes.size.toLong()).toInt())
    }

    /** The layout of the real Kaiji clip: video, DTS-HD MA, PGS, a PCR PID of its own. */
    fun kaiji(packetSize: Int, breakPmtCrc: Boolean = false, stamp: (Int) -> Int = { it }) = clip(
        packetSize = packetSize,
        hdmv = true,
        streams = listOf(
            Es(0x1B, 0x1011, descriptor(0x05, 'H'.code, 'D'.code, 'M'.code, 'V'.code, 0xFF, 0x1B, 0x44, 0x3F)),
            Es(0x86, 0x1100),
            Es(0x90, 0x1200)
        ),
        firstPts = 1_048_560L,
        lastPts = 123_210_480L,
        breakPmtCrc = breakPmtCrc,
        stamp = stamp
    )

    fun clip(
        streams: List<Es>,
        hdmv: Boolean,
        packetSize: Int = 192,
        registration: String = "HDMV",
        firstPts: Long = 1_048_560L,
        lastPts: Long? = 123_210_480L,
        videoPayload: ByteArray = annexB(sps(widthInMbs = 120, heightInMapUnits = 34, frameMbsOnly = false, cropBottom = 2)),
        audioPayload: ByteArray = ByteArray(300) { 0x55 },
        filler: Int = 3 * 1024 * 1024,
        breakPmtCrc: Boolean = false,
        stamp: (Int) -> Int = { it }
    ): ByteArray {
        val out = Packets(packetSize, stamp)
        val pmtPid = 0x100
        out.section(0, pat(1 to pmtPid, programZero = true))
        val programInfo = if (hdmv) descriptor(0x05, *registration.map { it.code }.toIntArray()) else ByteArray(0)
        out.section(pmtPid, pmt(1, pcrPid = 0x1001, programInfo, streams).also { if (breakPmtCrc) it[it.size - 1] = (it.last() + 1).toByte() })
        val timed = streams.filter { it.type in setOf(0x01, 0x02, 0x1B, 0x24, 0x83, 0x81, 0x86, 0x06, 0x0F, 0xEA) }
        timed.forEachIndexed { n, es ->
            val payload = if (n == 0) videoPayload else audioPayload
            out.pes(es.pid, if (n == 0) 0xE0 else 0xFD, firstPts + n * 1_500L, payload)
        }
        out.nullPackets(filler / packetSize)
        if (lastPts != null) {
            timed.forEachIndexed { n, es ->
                out.pes(es.pid, if (n == 0) 0xE0 else 0xFD, (lastPts - (timed.size - 1 - n) * 3_000L + (1L shl 33)) % (1L shl 33), ByteArray(200))
            }
        }
        out.nullPackets(8)
        return out.bytes()
    }

    /** [stamp] gives packet n's four-byte Blu-ray arrival stamp. */
    private class Packets(private val size: Int, private val stamp: (Int) -> Int) {
        private val out = ByteArrayOutputStream()
        private var packets = 0
        private val continuity = mutableMapOf<Int, Int>()

        fun bytes(): ByteArray = out.toByteArray()

        private fun packet(pid: Int, unitStart: Boolean, payload: ByteArray, from: Int): Int {
            if (size == 192) {
                val value = stamp(packets++)
                out.write(value ushr 24); out.write(value ushr 16); out.write(value ushr 8); out.write(value)
            }
            val cc = continuity.getOrDefault(pid, 0)
            continuity[pid] = (cc + 1) and 0x0F
            val room = 184
            val take = minOf(room, payload.size - from)
            out.write(0x47)
            out.write((if (unitStart) 0x40 else 0) or (pid shr 8))
            out.write(pid and 0xFF)
            if (take < room) {
                // Adaptation field of stuffing, so the payload ends the packet.
                out.write(0x30 or cc)
                val adaptation = room - take - 1
                out.write(adaptation)
                if (adaptation > 0) {
                    out.write(0x00)
                    repeat(adaptation - 1) { out.write(0xFF) }
                }
            } else {
                out.write(0x10 or cc)
            }
            out.write(payload, from, take)
            if (size == 204) repeat(16) { out.write(0) }
            return take
        }

        fun section(pid: Int, section: ByteArray) {
            val payload = byteArrayOf(0) + section
            var at = 0
            while (at < payload.size) at += packet(pid, at == 0, payload, at)
        }

        fun pes(pid: Int, streamId: Int, pts: Long, data: ByteArray) {
            val header = byteArrayOf(0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5) + ptsBytes(pts)
            val payload = header + data
            var at = 0
            while (at < payload.size) at += packet(pid, at == 0, payload, at)
        }

        fun nullPackets(count: Int) {
            val empty = ByteArray(184) { 0xFF.toByte() }
            repeat(count) { packet(0x1FFF, false, empty, 0) }
        }
    }

    private fun ptsBytes(pts: Long) = byteArrayOf(
        (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
        (pts shr 22).toByte(),
        (((pts shr 14) and 0xFE) or 1).toByte(),
        (pts shr 7).toByte(),
        (((pts shl 1) and 0xFE) or 1).toByte()
    )

    private fun pat(vararg programs: Pair<Int, Int>, programZero: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        if (programZero) body.write(byteArrayOf(0, 0, 0xE0.toByte(), 0x1F))
        programs.forEach { (number, pid) ->
            body.write(byteArrayOf((number shr 8).toByte(), number.toByte(), (0xE0 or (pid shr 8)).toByte(), pid.toByte()))
        }
        return withCrc(0x00, tableExtension = 1, body.toByteArray())
    }

    private fun pmt(number: Int, pcrPid: Int, programInfo: ByteArray, streams: List<Es>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf((0xE0 or (pcrPid shr 8)).toByte(), pcrPid.toByte(), (0xF0 or (programInfo.size shr 8)).toByte(), programInfo.size.toByte()))
        body.write(programInfo)
        streams.forEach { es ->
            body.write(
                byteArrayOf(
                    es.type.toByte(), (0xE0 or (es.pid shr 8)).toByte(), es.pid.toByte(),
                    (0xF0 or (es.descriptors.size shr 8)).toByte(), es.descriptors.size.toByte()
                )
            )
            body.write(es.descriptors)
        }
        return withCrc(0x02, tableExtension = number, body.toByteArray())
    }

    private fun withCrc(tableId: Int, tableExtension: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val section = byteArrayOf(
            tableId.toByte(), (0xB0 or (length shr 8)).toByte(), length.toByte(),
            (tableExtension shr 8).toByte(), tableExtension.toByte(), 0xC1.toByte(), 0, 0
        ) + body
        val crc = crc32(section)
        return section + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    fun descriptor(tag: Int, vararg body: Int) = byteArrayOf(tag.toByte(), body.size.toByte()) + ByteArray(body.size) { body[it].toByte() }

    fun language(code: String) = descriptor(0x0A, *code.map { it.code }.toIntArray(), 0)

    fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 1, 0x09, 0x10))
        nals.forEach { out.write(byteArrayOf(0, 0, 0, 1)); out.write(it) }
        return out.toByteArray()
    }

    /** A High-profile SPS, emulation prevention included, the way an encoder writes one. */
    fun sps(widthInMbs: Int, heightInMapUnits: Int, frameMbsOnly: Boolean, cropBottom: Int): ByteArray {
        val bits = BitWriter()
        bits.u(8, 100)
        bits.u(8, 0)
        bits.u(8, 41)
        bits.ue(0)
        bits.ue(1)
        bits.ue(0)
        bits.ue(0)
        bits.u(1, 0)
        // A scaling matrix, which is the part a parser most easily gets wrong.
        bits.u(1, 1)
        repeat(8) { list ->
            if (list == 0) {
                bits.u(1, 1)
                repeat(16) { bits.se(if (it == 0) 8 else -1) }
            } else {
                bits.u(1, 0)
            }
        }
        bits.ue(0)
        bits.ue(0)
        bits.ue(2)
        bits.ue(4)
        bits.u(1, 0)
        bits.ue(widthInMbs - 1)
        bits.ue(heightInMapUnits - 1)
        bits.u(1, if (frameMbsOnly) 1 else 0)
        if (!frameMbsOnly) bits.u(1, 1)
        bits.u(1, 1)
        if (cropBottom > 0) {
            bits.u(1, 1)
            bits.ue(0); bits.ue(0); bits.ue(0); bits.ue(cropBottom)
        } else {
            bits.u(1, 0)
        }
        bits.u(1, 0)
        bits.u(1, 1)
        return escape(byteArrayOf(0x67) + bits.bytes())
    }

    private fun escape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun u(count: Int, value: Int) = (count - 1 downTo 0).forEach { bits += (value shr it) and 1 }

        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bits += 0 }
            u(length, code)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun bytes(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { n -> (0 until 8).fold(0) { acc, i -> (acc shl 1) or bits[n * 8 + i] }.toByte() }
        }
    }

    val MPEG2_SEQUENCE = byteArrayOf(0, 0, 1, 0xB3.toByte(), 0x78, 0x04, 0x38, 0x33, 0, 0, 0, 0)
}
