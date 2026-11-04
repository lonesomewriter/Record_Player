package dev.recorddisc.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.CoverChange
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.TagEdit
import dev.recorddisc.player.library.TagRules
import dev.recorddisc.player.library.TrackEntity
import kotlinx.coroutines.launch

/** What each platform gives the song details editor. */
class EditorActions(
    /**
     * Opens the image picker; calls back with the chosen file's bytes, or null if nothing was
     * chosen. Defaults to doing nothing (screenshot tests).
     */
    val chooseCover: ((ByteArray?) -> Unit) -> Unit = { it(null) },
    /** Android: reopens the folder picker so the music folder can be changed (see [EditSupport.NeedsWriteAccess]). */
    val allowEditing: () -> Unit = {},
)

/** Opens the song details editor for a song; provided by each layout for the song ⋮ menus. */
val LocalEditSong = staticCompositionLocalOf<(TrackEntity) -> Unit> { {} }

@Composable
fun ProvideEditSong(onEdit: (TrackEntity) -> Unit, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalEditSong provides onEdit, content = content)

/** Checks a picked image: JPEG or PNG, at most [TagRules.MAX_COVER_BYTES]. Returns the cover, or why it can't be used. */
fun coverFromBytes(bytes: ByteArray): Pair<CoverChange.Replace?, String?> {
    if (bytes.size > TagRules.MAX_COVER_BYTES) return null to "That image is too large (the limit is 10 MB)"
    val mime = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        else -> return null to "Choose a JPEG or PNG image"
    }
    val (w, h) = imageSize(bytes) ?: return null to "That image can't be read"
    return CoverChange.Replace(bytes, mime, w, h) to null
}

/** The editor's state: the typed values, the cover change, and how saving is going. */
private class EditorState(track: TrackEntity) {
    var title by mutableStateOf(track.title.orEmpty())
    var artist by mutableStateOf(track.artist.orEmpty())
    var album by mutableStateOf(track.album.orEmpty())
    var albumArtist by mutableStateOf(track.albumArtist.orEmpty())
    var year by mutableStateOf(track.year?.toString().orEmpty())
    var trackNo by mutableStateOf(track.trackNumber?.toString().orEmpty())
    var genre by mutableStateOf(track.genre.orEmpty())
    var cover by mutableStateOf<CoverChange>(CoverChange.Keep)
    var support by mutableStateOf<EditSupport?>(null)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val yearError get() = if (TagRules.yearValid(year)) null else "Use a 4-digit year"
    val trackError get() = if (TagRules.trackValid(trackNo)) null else "Use a number from 1 to 999"
    val locked get() = saving || support != EditSupport.Editable
    val canSave get() = !locked && yearError == null && trackError == null

    fun edit(): TagEdit? = TagEdit.fromText(title, artist, album, albumArtist, year, trackNo, genre, cover)
}

@Composable
private fun rememberEditor(model: AppModel, track: TrackEntity): EditorState {
    val state = remember(track.id) { EditorState(track) }
    val access by model.accessRevision.collectAsState()
    LaunchedEffect(track.id, access) { state.support = model.editSupport(track) }
    return state
}

/** Android: the full-screen editor, as in the mockup. */
@Composable
fun PhoneTagEditor(model: AppModel, track: TrackEntity, actions: EditorActions, onClose: () -> Unit) {
    val state = rememberEditor(model, track)
    val save = rememberSave(model, track, state, onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 24.dp)) {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton("←", "Back", onClose)
            Text("Edit metadata", color = disc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        CoverRow(model, track, state, actions, 92.dp)
        Notice(state, actions)
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Field("Title", state.title, state.locked) { state.title = it }
            Field("Artist", state.artist, state.locked) { state.artist = it }
            Field("Album", state.album, state.locked) { state.album = it }
            Field("Album artist", state.albumArtist, state.locked) { state.albumArtist = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Year", state.year, state.locked, Modifier.weight(1f), numeric = true, error = state.yearError) { state.year = it }
                Field("Track", state.trackNo, state.locked, Modifier.weight(1f), numeric = true, error = state.trackError) { state.trackNo = it }
            }
            Field("Genre", state.genre, state.locked) { state.genre = it }
        }
        SaveError(state)
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("Cancel", Modifier.weight(1f).height(46.dp), onClick = onClose)
            PrimaryButton(if (state.saving) "Saving…" else "Save", Modifier.weight(1f).height(46.dp), enabled = state.canSave, onClick = save)
        }
    }
}

/** Desktop: the editor as a pop-up over the app, as in the mockup. Esc, ✕ and clicking outside close it. */
@Composable
fun BoxScope.DesktopTagEditor(model: AppModel, track: TrackEntity, actions: EditorActions, onClose: () -> Unit) {
    val state = rememberEditor(model, track)
    val save = rememberSave(model, track, state, onClose)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.matchParentSize().background(disc.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!state.saving) onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 620.dp).fillMaxWidth().clip(shape).background(disc.panel2).border(1.dp, disc.line, shape)
                // Clicks inside the card don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .focusRequester(focus).focusable()
                .onKeyEvent { if (it.key == Key.Escape && !state.saving) { onClose(); true } else false }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Track details")
                    Text("Edit metadata", color = disc.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                CircleIconButton("✕", "Close metadata editor") { if (!state.saving) onClose() }
            }
            CoverRow(model, track, state, actions, 92.dp)
            Notice(state, actions)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Title", state.title, state.locked, compact = true) { state.title = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Artist", state.artist, state.locked, Modifier.weight(1f), compact = true) { state.artist = it }
                    Field("Album", state.album, state.locked, Modifier.weight(1f), compact = true) { state.album = it }
                }
                Field("Album artist", state.albumArtist, state.locked, compact = true) { state.albumArtist = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Year", state.year, state.locked, Modifier.weight(1f), numeric = true, error = state.yearError, compact = true) { state.year = it }
                    Field("Track", state.trackNo, state.locked, Modifier.weight(1f), numeric = true, error = state.trackError, compact = true) { state.trackNo = it }
                }
                Field("Genre", state.genre, state.locked, compact = true) { state.genre = it }
            }
            SaveError(state)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Cancel", Modifier.weight(1f).height(42.dp), enabled = !state.saving, onClick = onClose)
                PrimaryButton(if (state.saving) "Saving…" else "Save changes", Modifier.weight(1f).height(42.dp), enabled = state.canSave, onClick = save)
            }
        }
    }
}

@Composable
private fun rememberSave(model: AppModel, track: TrackEntity, state: EditorState, onClose: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        val edit = state.edit()
        if (edit != null && state.canSave) {
            state.saving = true
            state.error = null
            scope.launch {
                val error = model.saveTags(track, edit)
                state.saving = false
                if (error == null) onClose() else state.error = error
            }
        }
    }
}

/** The cover (as it will be after saving) with Choose cover image and Remove cover. */
@Composable
private fun CoverRow(model: AppModel, track: TrackEntity, state: EditorState, actions: EditorActions, size: Dp) {
    val corner = 15.dp
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val cover = state.cover) {
            is CoverChange.Replace -> {
                val image = remember(cover) { decodeImage(cover.bytes, 300) }
                if (image != null) {
                    Image(image, "New cover", Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
                } else {
                    ArtThumb(model.artwork, track.location, size, corner)
                }
            }
            CoverChange.Remove -> ArtThumb(model.artwork, track.location, size, corner, Modifier.alpha(0.35f))
            CoverChange.Keep -> ArtThumb(model.artwork, track.location, size, corner)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Choose cover image", enabled = !state.locked) {
                actions.chooseCover { bytes ->
                    if (bytes != null) {
                        val (cover, problem) = coverFromBytes(bytes)
                        if (cover != null) state.cover = cover else model.message(problem ?: "That image can't be used")
                    }
                }
            }
            val removing = state.cover == CoverChange.Remove
            OutlineButton(if (removing) "Keep cover" else "Remove cover", enabled = !state.locked && (track.hasArtwork || state.cover is CoverChange.Replace)) {
                state.cover = if (removing || state.cover is CoverChange.Replace) CoverChange.Keep else CoverChange.Remove
            }
            when {
                removing -> Text("The cover will be removed when you save", color = disc.muted, fontSize = 11.sp)
                state.cover is CoverChange.Replace -> Text("New cover, saved when you save", color = disc.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ColumnScope.Notice(state: EditorState, actions: EditorActions) = EditNotice(state.support, actions)

/**
 * Allow editing (Android, read-only folder) or why the file can't be changed; nothing when
 * editing is possible. [readOnlyTitle] and [allowText] word it for the editor using it.
 */
@Composable
internal fun ColumnScope.EditNotice(
    support: EditSupport?,
    actions: EditorActions,
    readOnlyTitle: String = "This file can't be changed",
    allowText: String = "To change song files, allow Record Player to edit your music folder. You'll choose the same folder again.",
) {
    if (support == null || support == EditSupport.Editable) return
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.padding(bottom = 14.dp).fillMaxWidth().clip(shape).background(disc.accent.copy(alpha = 0.08f))
            .border(1.dp, disc.accent.copy(alpha = 0.3f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (support) {
            EditSupport.NeedsWriteAccess -> {
                Text("Allow editing", color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(allowText, color = disc.text, fontSize = 12.sp, lineHeight = 1.5.em)
                PrimaryButton("Allow editing", Modifier.padding(top = 10.dp), onClick = actions.allowEditing)
            }
            is EditSupport.ReadOnly -> {
                Text(readOnlyTitle, color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(support.reason, color = disc.text, fontSize = 12.sp, lineHeight = 1.5.em)
            }
            EditSupport.Editable -> Unit
        }
    }
}

@Composable
private fun SaveError(state: EditorState) = ErrorBox(state.error)

/** A save error in a red box; nothing when [error] is null. */
@Composable
internal fun ErrorBox(error: String?) {
    error ?: return
    val shape = RoundedCornerShape(12.dp)
    Text(
        error, Modifier.padding(top = 14.dp).fillMaxWidth().clip(shape).border(1.dp, disc.error, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        color = disc.error, fontSize = 12.sp, lineHeight = 1.5.em,
    )
}

/** A labelled text box like the mockup's: label above, red text and border when [error] is set. */
@Composable
private fun Field(
    label: String,
    value: String,
    locked: Boolean,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    error: String? = null,
    compact: Boolean = false,
    onChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(), Modifier.padding(bottom = 6.dp), color = disc.muted, fontSize = if (compact) 9.sp else 10.sp,
            letterSpacing = 0.12.em, fontWeight = if (compact) FontWeight.Bold else FontWeight.Normal,
        )
        BasicTextField(
            value, onChange,
            // The phone screen is `panel` and the desktop pop-up `panel2`; the box stands out from either.
            Modifier.fillMaxWidth().alpha(if (locked) 0.55f else 1f).clip(shape).background(if (compact) disc.panel else disc.panel2)
                .border(1.dp, if (error != null) disc.error else disc.line, shape)
                .padding(horizontal = if (compact) 11.dp else 12.dp, vertical = if (compact) 10.dp else 11.dp)
                .semantics { contentDescription = label },
            enabled = !locked,
            singleLine = true,
            textStyle = TextStyle(color = disc.text, fontSize = if (compact) 12.sp else 14.sp),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            cursorBrush = SolidColor(disc.accentReadable),
        )
        if (error != null) Text(error, Modifier.padding(top = 5.dp), color = disc.error, fontSize = 11.sp)
    }
}
