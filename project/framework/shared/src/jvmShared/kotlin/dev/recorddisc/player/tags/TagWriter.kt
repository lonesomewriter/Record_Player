package dev.recorddisc.player.tags

import dev.recorddisc.player.library.AudioFormats
import dev.recorddisc.player.library.CoverChange
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagSaveException
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.vorbiscomment.util.Base64Coder
import java.io.File

/** Writes the editor's changes into a song file, in place. Callers work on a copy (see [BackupThenOverwrite]). */
object TagWriter {
    init {
        TagOptions.ensure()
    }

    /** Applies [edit] to [file], then reads the tags back and throws [TagSaveException] if they don't match. */
    fun apply(file: File, edit: TagEdit) {
        if (AudioFormats.extensionOf(file.name) == "opus") {
            val tag = OpusTags.read(file)
            fill(tag, edit)
            OpusTags.write(file, tag)
        } else {
            val audio = AudioFileIO.read(file)
            fill(audio.tagOrCreateAndSetDefault, edit)
            audio.commit()
        }
        verify(file, edit)
    }

    private fun fill(tag: Tag, edit: TagEdit) {
        tag.put(FieldKey.TITLE, edit.title)
        tag.put(FieldKey.ARTIST, edit.artist)
        tag.put(FieldKey.ALBUM, edit.album)
        tag.put(FieldKey.ALBUM_ARTIST, edit.albumArtist)
        tag.put(FieldKey.YEAR, edit.year?.toString())
        tag.put(FieldKey.TRACK, edit.trackNumber?.toString())
        tag.put(FieldKey.GENRE, edit.genre)
        when (val cover = edit.cover) {
            CoverChange.Keep -> Unit
            CoverChange.Remove -> tag.deleteArtworkField()
            is CoverChange.Replace -> {
                tag.deleteArtworkField()
                tag.setField(coverField(tag, cover))
            }
        }
    }

    /**
     * The front-cover field, built from the bytes and the size we already know. jaudiotagger's
     * own FLAC and Ogg paths measure the image with java.awt, which Android doesn't have.
     */
    private fun coverField(tag: Tag, cover: CoverChange.Replace) = when (tag) {
        is FlacTag -> tag.createArtworkField(cover.bytes, PictureTypes.DEFAULT_ID, cover.mimeType, "", cover.width, cover.height, 24, 0)
        is VorbisCommentTag -> {
            val picture = MetadataBlockDataPicture(cover.bytes, PictureTypes.DEFAULT_ID, cover.mimeType, "", cover.width, cover.height, 24, 0)
            tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, String(Base64Coder.encode(picture.rawContent)))
        }
        else -> tag.createField(
            ArtworkFactory.getNew().apply {
                binaryData = cover.bytes
                mimeType = cover.mimeType
                width = cover.width
                height = cover.height
                pictureType = PictureTypes.DEFAULT_ID
            },
        )
    }

    private fun Tag.put(key: FieldKey, value: String?) {
        if (value == null) deleteField(key) else setField(key, value)
    }

    private fun verify(file: File, edit: TagEdit) {
        val read = TagReader.read(file)
        val mismatch = when {
            read.title != edit.title -> "title"
            read.artist != edit.artist -> "artist"
            read.album != edit.album -> "album"
            read.albumArtist != edit.albumArtist -> "album artist"
            read.year != edit.year -> "year"
            read.trackNumber != edit.trackNumber -> "track"
            read.genre != edit.genre -> "genre"
            edit.cover == CoverChange.Remove && read.hasArtwork -> "cover"
            edit.cover is CoverChange.Replace && !read.hasArtwork -> "cover"
            else -> null
        }
        if (mismatch != null) throw TagSaveException("The $mismatch didn't save correctly, so nothing was changed")
    }
}
