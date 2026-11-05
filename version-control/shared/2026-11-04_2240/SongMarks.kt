package dev.recorddisc.player.convert

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/*
 * "To audio file" (prototype, 2026-11-04): marking where each song starts inside the single
 * output file, so players can skip between songs. Shared by desktop and Android: these only
 * rewrite file headers after the audio has been encoded. Nothing here touches the audio itself.
 */

/** A song inside the joined file: its title and the sample (per channel) where it starts. */
data class SongMark(val title: String, val startSample: Long)

/**
 * FLAC: adds a CUESHEET block (one track per song) and title/album tags (VORBIS_COMMENT) to a
 * finished FLAC file, replacing any the encoder wrote. FLAC allows at most 254 tracks besides
 * the lead-out, so longer playlists get tags but no cue sheet.
 */
object FlacMarks {
    private const val STREAMINFO = 0
    private const val VORBIS_COMMENT = 4
    private const val CUESHEET = 5
    const val MAX_TRACKS = 254

    fun write(file: File, totalSamples: Long, marks: List<SongMark>, tags: Map<String, String>, vendor: String = "Record Player") {
        val blocks = readBlocks(file).toMutableList()
        // Encoders that can't seek back (Android's MediaCodec) leave STREAMINFO's length at 0.
        blocks.firstOrNull { it.type == STREAMINFO }?.let { fillTotalSamples(it.body, totalSamples) }
        blocks.removeAll { it.type == VORBIS_COMMENT || it.type == CUESHEET }
        blocks += Block(VORBIS_COMMENT, vorbisComment(vendor, tags))
        if (marks.isNotEmpty() && marks.size <= MAX_TRACKS) blocks += Block(CUESHEET, cueSheet(totalSamples, marks))
        val audioStart = audioOffset(file)
        replaceHeader(file, audioStart) { out ->
            out.write("fLaC".toByteArray())
            blocks.forEachIndexed { i, block ->
                val last = if (i == blocks.lastIndex) 0x80 else 0
                out.write(last or block.type)
                out.write(int24(block.body.size))
                out.write(block.body)
            }
        }
    }

    /** The cue-sheet track offsets (samples), lead-out excluded; empty when there's none. Used by tests. */
    fun readTrackOffsets(file: File): List<Long> {
        val body = readBlocks(file).firstOrNull { it.type == CUESHEET }?.body ?: return emptyList()
        val data = java.nio.ByteBuffer.wrap(body)
        data.position(128 + 8 + 259)
        val count = data.get().toInt() and 0xFF
        val offsets = mutableListOf<Long>()
        repeat(count) {
            val offset = data.long
            val number = data.get().toInt() and 0xFF
            data.position(data.position() + 12 + 1 + 13)
            val indexes = data.get().toInt() and 0xFF
            data.position(data.position() + indexes * 12)
            if (number != 255) offsets += offset
        }
        return offsets
    }

    /** The VORBIS_COMMENT entries ("TITLE=…"). Used by tests. */
    fun readTags(file: File): List<String> {
        val body = readBlocks(file).firstOrNull { it.type == VORBIS_COMMENT }?.body ?: return emptyList()
        val data = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.position(4 + data.int)
        return List(data.int) {
            val bytes = ByteArray(data.int)
            data.get(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    private class Block(val type: Int, val body: ByteArray)

    private fun readBlocks(file: File): List<Block> = RandomAccessFile(file, "r").use { raf ->
        val magic = ByteArray(4).also { raf.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "fLaC") { "Not a FLAC file" }
        val blocks = mutableListOf<Block>()
        while (true) {
            val header = raf.readUnsignedByte()
            val length = (raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
            val body = ByteArray(length).also { raf.readFully(it) }
            blocks += Block(header and 0x7F, body)
            if (header and 0x80 != 0) break
        }
        blocks
    }

    /** Where the audio frames begin: just after the last metadata block. */
    private fun audioOffset(file: File): Long = RandomAccessFile(file, "r").use { raf ->
        raf.seek(4)
        var last = false
        while (!last) {
            val header = raf.readUnsignedByte()
            last = header and 0x80 != 0
            val length = (raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
            raf.seek(raf.filePointer + length)
        }
        raf.filePointer
    }

    private fun vorbisComment(vendor: String, tags: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        le32(vendorBytes.size); out.write(vendorBytes)
        le32(tags.size)
        for ((key, value) in tags) {
            val entry = "${key.uppercase()}=$value".toByteArray(Charsets.UTF_8)
            le32(entry.size); out.write(entry)
        }
        return out.toByteArray()
    }

    /** A non-CD cue sheet: track N starts at its song's first sample, lead-out (255) at the end. */
    private fun cueSheet(totalSamples: Long, marks: List<SongMark>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(ByteArray(128)) // media catalog number: none
        out.writeLong(0) // lead-in samples: 0 for non-CD
        out.write(ByteArray(259)) // "is CD" flag off + reserved
        out.writeByte(marks.size + 1)
        marks.forEachIndexed { i, mark ->
            out.writeLong(mark.startSample)
            out.writeByte(i + 1)
            out.write(ByteArray(12)) // ISRC: none
            out.write(ByteArray(14)) // audio track, no pre-emphasis, reserved
            out.writeByte(1) // one index point: index 1 at the track start
            out.writeLong(0); out.writeByte(1); out.write(ByteArray(3))
        }
        out.writeLong(totalSamples)
        out.writeByte(255) // lead-out
        out.write(ByteArray(12)); out.write(ByteArray(14))
        out.writeByte(0)
        return bytes.toByteArray()
    }

    private fun int24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /**
     * STREAMINFO keeps the length (samples per channel) in 36 bits: the low 4 bits of byte 13 and
     * bytes 14–17. Sets it only when the encoder left it at 0 ("unknown").
     */
    private fun fillTotalSamples(streamInfo: ByteArray, total: Long) {
        if (streamInfo.size < 18) return
        val current = ((streamInfo[13].toLong() and 0x0F) shl 32) or
            (java.nio.ByteBuffer.wrap(streamInfo, 14, 4).int.toLong() and 0xFFFFFFFFL)
        if (current != 0L || total <= 0 || total >= (1L shl 36)) return
        streamInfo[13] = ((streamInfo[13].toInt() and 0xF0) or (total shr 32).toInt()).toByte()
        java.nio.ByteBuffer.wrap(streamInfo).putInt(14, total.toInt())
    }

    /** STREAMINFO's length in samples per channel (0 = unknown). Used by tests and the Android self-check. */
    fun readTotalSamples(file: File): Long {
        val info = readBlocks(file).firstOrNull { it.type == STREAMINFO }?.body ?: return 0
        return ((info[13].toLong() and 0x0F) shl 32) or (java.nio.ByteBuffer.wrap(info, 14, 4).int.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * M4A: adds Nero-style chapters ("chpl" in moov/udta, the form FFmpeg, VLC and many players read)
 * to a finished MP4 file. When the moov box sits before the audio, the audio's chunk offsets are
 * shifted by the bytes added. At most 255 chapters fit; longer playlists get none.
 */
object Mp4Chapters {
    const val MAX_CHAPTERS = 255

    fun write(file: File, sampleRate: Int, marks: List<SongMark>) {
        if (marks.isEmpty() || marks.size > MAX_CHAPTERS) return
        val top = topBoxes(file)
        val moov = top.firstOrNull { it.type == "moov" } ?: error("No moov box")
        val mdat = top.firstOrNull { it.type == "mdat" }
        val old = RandomAccessFile(file, "r").use { raf -> ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) } }
        val chpl = chpl(sampleRate, marks)
        var updated = withChapters(old, chpl)
        val growth = updated.size - old.size
        // Audio after moov moves by `growth` bytes, so its chunk offsets must follow.
        if (mdat != null && mdat.offset > moov.offset) updated = shiftChunkOffsets(updated, growth.toLong())
        replaceRange(file, moov.offset, moov.size, updated)
    }

    /** Chapter start times (ms) and titles. Used by tests. */
    fun read(file: File): List<Pair<Long, String>> {
        val moov = topBoxes(file).firstOrNull { it.type == "moov" } ?: return emptyList()
        val bytes = RandomAccessFile(file, "r").use { raf -> ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) } }
        val udta = children(bytes, 8, bytes.size).firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = children(bytes, udta.offset.toInt() + 8, (udta.offset + udta.size).toInt()).firstOrNull { it.type == "chpl" } ?: return emptyList()
        val data = java.nio.ByteBuffer.wrap(bytes, chpl.offset.toInt() + 8 + 4 + 4, chpl.size.toInt() - 16)
        return List(data.get().toInt() and 0xFF) {
            val start = data.long
            val title = ByteArray(data.get().toInt() and 0xFF).also { data.get(it) }
            start / 10_000 to String(title, Charsets.UTF_8)
        }
    }

    private class Box(val type: String, val offset: Long, val size: Long)

    private fun topBoxes(file: File): List<Box> = RandomAccessFile(file, "r").use { raf ->
        val boxes = mutableListOf<Box>()
        var pos = 0L
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
            if (size == 1L) size = raf.readLong() else if (size == 0L) size = raf.length() - pos
            boxes += Box(type, pos, size)
            pos += size
        }
        boxes
    }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val size = java.nio.ByteBuffer.wrap(bytes, pos, 4).int.toLong() and 0xFFFFFFFFL
            if (size < 8) break
            boxes += Box(String(bytes, pos + 4, 4, Charsets.US_ASCII), pos.toLong(), size)
            pos += size.toInt()
        }
        return boxes
    }

    /** "chpl" v1: 100-ns start times and UTF-8 titles (cut to 255 bytes). */
    private fun chpl(sampleRate: Int, marks: List<SongMark>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(0); out.write("chpl".toByteArray())
        out.writeInt(0x01000000) // version 1, flags 0
        out.writeInt(0) // reserved
        out.writeByte(marks.size)
        for (mark in marks) {
            out.writeLong(mark.startSample * 10_000_000L / sampleRate)
            val title = mark.title.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
            out.writeByte(title.size); out.write(title)
        }
        return bytes.toByteArray().also { java.nio.ByteBuffer.wrap(it).putInt(0, it.size) }
    }

    /** moov with chpl in its udta (replacing an old chpl; adding udta when missing). */
    private fun withChapters(moov: ByteArray, chpl: ByteArray): ByteArray {
        val kids = children(moov, 8, moov.size)
        val out = ByteArrayOutputStream()
        out.write(moov, 0, 8)
        var placed = false
        for (kid in kids) {
            if (kid.type != "udta") { out.write(moov, kid.offset.toInt(), kid.size.toInt()); continue }
            val inner = children(moov, kid.offset.toInt() + 8, (kid.offset + kid.size).toInt()).filter { it.type != "chpl" }
            val udta = ByteArrayOutputStream()
            udta.write(ByteArray(4)); udta.write("udta".toByteArray())
            for (box in inner) udta.write(moov, box.offset.toInt(), box.size.toInt())
            udta.write(chpl)
            out.write(udta.toByteArray().also { java.nio.ByteBuffer.wrap(it).putInt(0, it.size) })
            placed = true
        }
        if (!placed) {
            out.write(ByteArray(4).also { java.nio.ByteBuffer.wrap(it).putInt(0, 8 + chpl.size) })
            out.write("udta".toByteArray()); out.write(chpl)
        }
        return out.toByteArray().also { java.nio.ByteBuffer.wrap(it).putInt(0, it.size) }
    }

    /** Adds [delta] to every stco / co64 chunk offset inside moov. */
    private fun shiftChunkOffsets(moov: ByteArray, delta: Long): ByteArray {
        val buf = java.nio.ByteBuffer.wrap(moov)
        fun walk(from: Int, to: Int) {
            for (box in children(moov, from, to)) {
                val start = box.offset.toInt()
                when (box.type) {
                    "trak", "mdia", "minf", "stbl" -> walk(start + 8, start + box.size.toInt())
                    "stco" -> {
                        val count = buf.getInt(start + 12)
                        for (i in 0 until count) {
                            val at = start + 16 + i * 4
                            buf.putInt(at, ((buf.getInt(at).toLong() and 0xFFFFFFFFL) + delta).toInt())
                        }
                    }
                    "co64" -> {
                        val count = buf.getInt(start + 12)
                        for (i in 0 until count) {
                            val at = start + 16 + i * 8
                            buf.putLong(at, buf.getLong(at) + delta)
                        }
                    }
                }
            }
        }
        walk(8, moov.size)
        return moov
    }
}

/** Rewrites [file] with everything before [audioStart] replaced by what [header] writes. */
private fun replaceHeader(file: File, audioStart: Long, header: (java.io.OutputStream) -> Unit) =
    rewrite(file) { out, raf ->
        header(out)
        raf.seek(audioStart)
        copy(raf, out, raf.length() - audioStart)
    }

/** Rewrites [file] with the [length] bytes at [offset] replaced by [bytes]. */
private fun replaceRange(file: File, offset: Long, length: Long, bytes: ByteArray) =
    rewrite(file) { out, raf ->
        raf.seek(0); copy(raf, out, offset)
        out.write(bytes)
        raf.seek(offset + length); copy(raf, out, raf.length() - offset - length)
    }

/** Writes a new version next to [file], then swaps it in, so a failure never leaves half a file. */
private fun rewrite(file: File, body: (java.io.OutputStream, RandomAccessFile) -> Unit) {
    val temp = File(file.parentFile, ".${file.name}.marks")
    RandomAccessFile(file, "r").use { raf -> temp.outputStream().buffered().use { out -> body(out, raf) } }
    check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) { "Couldn't replace ${file.name}" }
}

private fun copy(raf: RandomAccessFile, out: java.io.OutputStream, count: Long) {
    val buffer = ByteArray(1 shl 16)
    var left = count
    while (left > 0) {
        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
}
