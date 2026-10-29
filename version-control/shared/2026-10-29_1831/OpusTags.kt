package dev.recorddisc.player.tags

import org.jaudiotagger.tag.vorbiscomment.VorbisCommentCreator
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentReader
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads and writes the tags of Ogg Opus files (RFC 7845) using jaudiotagger's Vorbis comment
 * handling, since jaudiotagger 3.0.1 has no Opus support.
 *
 * Only the first logical stream is edited. Later chained streams are copied unchanged;
 * interleaved (multiplexed) streams are refused for writing.
 */
object OpusTags {
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)

    // RFC 7845 section 8 allows treating comment headers over 120 MB as invalid.
    private const val MAX_COMMENT_PACKET = 125_829_120
    private const val MAX_PAGE_SEGMENTS = 255

    class OpusTagException(message: String) : IOException(message)

    fun read(file: File): VorbisCommentTag = file.inputStream().buffered().use { input ->
        val headers = readHeaders(input)
        parseComments(headers.commentPacket).tag
    }

    fun write(file: File, tag: VorbisCommentTag) {
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            BufferedInputStream(file.inputStream()).use { input ->
                FileOutputStream(temp).use { fos ->
                    val out = BufferedOutputStream(fos)
                    rewrite(input, out, tag)
                    out.flush()
                    fos.fd.sync()
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    // ---- Ogg pages ----

    private class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        var sequence: Int,
        val lacing: IntArray,
        val body: ByteArray
    ) {
        val isContinued get() = headerType and 0x01 != 0
        val isFirst get() = headerType and 0x02 != 0
        val isLast get() = headerType and 0x04 != 0

        fun write(out: OutputStream) {
            val header = ByteArray(27 + lacing.size)
            "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
            header[4] = 0
            header[5] = headerType.toByte()
            putLE(header, 6, granule, 8)
            putLE(header, 14, serial.toLong(), 4)
            putLE(header, 18, sequence.toLong(), 4)
            header[26] = lacing.size.toByte()
            lacing.forEachIndexed { i, v -> header[27 + i] = v.toByte() }
            putLE(header, 22, crc(header, body).toLong(), 4)
            out.write(header)
            out.write(body)
        }
    }

    /** Returns null at a clean end of file. */
    private fun readPage(input: InputStream): Page? {
        val fixed = ByteArray(27)
        val first = input.read()
        if (first < 0) return null
        fixed[0] = first.toByte()
        readFully(input, fixed, 1, 26)
        if (!fixed.copyOfRange(0, 4).contentEquals("OggS".toByteArray(Charsets.US_ASCII))) throw OpusTagException("Not an Ogg page")
        if (fixed[4].toInt() != 0) throw OpusTagException("Unsupported Ogg version")
        val count = fixed[26].toInt() and 0xFF
        val lacingBytes = ByteArray(count).also { readFully(input, it, 0, count) }
        val lacing = IntArray(count) { lacingBytes[it].toInt() and 0xFF }
        val body = ByteArray(lacing.sum()).also { readFully(input, it, 0, it.size) }
        val page = Page(
            headerType = fixed[5].toInt() and 0xFF,
            granule = getLE(fixed, 6, 8),
            serial = getLE(fixed, 14, 4).toInt(),
            sequence = getLE(fixed, 18, 4).toInt(),
            lacing = lacing,
            body = body
        )
        val header = fixed + lacingBytes
        val stored = getLE(header, 22, 4).toInt()
        for (i in 22 until 26) header[i] = 0
        if (crc(header, body) != stored) throw OpusTagException("Damaged Ogg page (checksum mismatch)")
        return page
    }

    // ---- headers ----

    private class Headers(val serial: Int, val idPage: Page, val commentPacket: ByteArray, val commentPageCount: Int)

    private fun readHeaders(input: InputStream): Headers {
        val idPage = readPage(input) ?: throw OpusTagException("Empty file")
        if (!idPage.isFirst || idPage.lacing.isEmpty() || idPage.lacing.last() == 255 || idPage.lacing.count { it < 255 } != 1 ||
            !startsWith(idPage.body, OPUS_HEAD)
        ) throw OpusTagException("First page is not an Opus identification header")
        val serial = idPage.serial

        val packet = ByteArrayOutputStream()
        var pages = 0
        while (true) {
            val page = readPage(input) ?: throw OpusTagException("File ends inside the comment header")
            if (page.serial != serial) throw OpusTagException("Multiplexed Ogg streams are not supported")
            if (pages == 0 && page.isContinued) throw OpusTagException("Comment header does not start a new packet")
            pages++
            var offset = 0
            for ((i, len) in page.lacing.withIndex()) {
                packet.write(page.body, offset, len)
                offset += len
                if (packet.size() > MAX_COMMENT_PACKET) throw OpusTagException("Comment header is larger than 120 MB")
                if (len < 255) {
                    // RFC 7845: the comment header packet MUST finish the page on which it completes.
                    if (i != page.lacing.lastIndex) throw OpusTagException("Audio data shares the comment header page")
                    val bytes = packet.toByteArray()
                    if (!startsWith(bytes, OPUS_TAGS)) throw OpusTagException("Second packet is not an Opus comment header")
                    return Headers(serial, idPage, bytes, pages)
                }
            }
        }
    }

    private class ParsedComments(val tag: VorbisCommentTag, val trailing: ByteArray)

    private fun parseComments(packet: ByteArray): ParsedComments {
        // Walk the length fields ourselves so oversized lengths are rejected before allocation
        // and so we know where the comment list ends (for the trailing-data rule).
        var pos = OPUS_TAGS.size
        fun need(n: Long) { if (n < 0 || pos + n > packet.size) throw OpusTagException("Comment header is truncated or malformed") }
        need(4); val vendorLen = getLE(packet, pos, 4); pos += 4
        need(vendorLen); pos += vendorLen.toInt()
        need(4); val count = getLE(packet, pos, 4); pos += 4
        for (i in 0 until count) {
            need(4); val len = getLE(packet, pos, 4); pos += 4
            need(len); pos += len.toInt()
        }
        val comments = packet.copyOfRange(OPUS_TAGS.size, pos)
        val tag = VorbisCommentReader().read(comments, false, null)
        return ParsedComments(tag, packet.copyOfRange(pos, packet.size))
    }

    // ---- rewrite ----

    private fun rewrite(input: InputStream, out: OutputStream, tag: VorbisCommentTag) {
        val headers = readHeaders(input)
        val old = parseComments(headers.commentPacket)

        val newPacket = ByteArrayOutputStream().apply {
            write(OPUS_TAGS)
            val body = VorbisCommentCreator().convertMetadata(tag, true)
            write(body.array(), body.arrayOffset() + body.position(), body.remaining())
            // RFC 7845 5.2: preserve trailing data when the LSB of its first byte is 1; otherwise it is padding.
            if (old.trailing.isNotEmpty() && old.trailing[0].toInt() and 1 == 1) write(old.trailing)
        }.toByteArray()
        if (newPacket.size > MAX_COMMENT_PACKET) throw OpusTagException("New tags exceed 120 MB")

        headers.idPage.write(out)
        val commentPages = paginate(newPacket, headers.serial, firstSequence = 1)
        commentPages.forEach { it.write(out) }

        val shift = commentPages.size - headers.commentPageCount
        var inFirstStream = true
        while (true) {
            val page = readPage(input) ?: break
            if (inFirstStream) {
                if (page.serial != headers.serial) {
                    if (!page.isFirst) throw OpusTagException("Multiplexed Ogg streams are not supported")
                    inFirstStream = false
                } else {
                    page.sequence += shift
                    if (page.isLast) inFirstStream = false
                }
            }
            page.write(out)
        }
    }

    private fun paginate(packet: ByteArray, serial: Int, firstSequence: Int): List<Page> {
        val lacing = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) { lacing += 255; remaining -= 255 }
        lacing += remaining // a final 0 marks the end when the size is a multiple of 255

        val pages = ArrayList<Page>()
        var offset = 0
        var seq = firstSequence
        for (start in lacing.indices step MAX_PAGE_SEGMENTS) {
            val chunk = lacing.subList(start, minOf(start + MAX_PAGE_SEGMENTS, lacing.size)).toIntArray()
            val size = chunk.sum()
            val completes = start + chunk.size == lacing.size
            pages += Page(
                headerType = if (start == 0) 0 else 0x01,
                granule = if (completes) 0L else -1L, // RFC 7845: 0 where the comment header completes
                serial = serial,
                sequence = seq++,
                lacing = chunk,
                body = packet.copyOfRange(offset, offset + size)
            )
            offset += size
        }
        return pages
    }

    // ---- helpers ----

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg CRC-32: polynomial 0x04C11DB7, initial value 0, no reflection, no final XOR. */
    private fun crc(header: ByteArray, body: ByteArray): Int {
        var c = 0
        for (b in header) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        for (b in body) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray) =
        data.size >= prefix.size && data.copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun getLE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun putLE(b: ByteArray, off: Int, value: Long, n: Int) {
        for (i in 0 until n) b[off + i] = (value ushr (8 * i)).toByte()
    }

    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val r = input.read(b, off + done, len - done)
            if (r < 0) throw EOFException("Unexpected end of Ogg file")
            done += r
        }
    }
}
