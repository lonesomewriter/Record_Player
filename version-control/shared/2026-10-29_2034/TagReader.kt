package dev.recorddisc.player.tags

import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.TrackTags
import dev.recorddisc.player.library.parseLeadingNumber
import dev.recorddisc.player.library.parseYear
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.audio.wav.WavSaveOptions
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Reads library tags with jaudiotagger, and with [OpusTags] for Opus files. */
object TagReader {
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        // WAV's default RIFF-INFO tags can't hold cover art; use the ID3 chunk, synced with INFO.
        TagOptionSingleton.getInstance().apply {
            wavOptions = WavOptions.READ_ID3_ONLY_AND_SYNC
            wavSaveOptions = WavSaveOptions.SAVE_BOTH_AND_SYNC
        }
    }

    fun read(file: File): TrackTags {
        if (AudioFormats.extensionOf(file.name) == "opus") {
            return fromTag(OpusTags.read(file), OpusTags.durationMs(file))
        }
        val audio = AudioFileIO.read(file)
        val durationMs = audio.audioHeader?.preciseTrackLength?.let { (it * 1000).toLong() }
        return fromTag(audio.tag, durationMs)
    }

    private fun fromTag(tag: Tag?, durationMs: Long?) = TrackTags(
        title = tag.text(FieldKey.TITLE),
        artist = tag.text(FieldKey.ARTIST),
        album = tag.text(FieldKey.ALBUM),
        albumArtist = tag.text(FieldKey.ALBUM_ARTIST),
        year = parseYear(tag.text(FieldKey.YEAR)),
        trackNumber = parseLeadingNumber(tag.text(FieldKey.TRACK)),
        discNumber = parseLeadingNumber(tag.text(FieldKey.DISC_NO)),
        genre = tag.text(FieldKey.GENRE),
        durationMs = durationMs,
        hasArtwork = runCatching { tag?.firstArtwork != null }.getOrDefault(false),
    )

    // Some tag types throw for fields they don't support; treat those as missing.
    private fun Tag?.text(key: FieldKey): String? =
        this?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
}
