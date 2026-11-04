package dev.recorddisc.player.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.library.EditSupport
import dev.recorddisc.player.library.LyricLine
import dev.recorddisc.player.library.Lrc
import dev.recorddisc.player.library.TrackEntity
import dev.recorddisc.player.playback.PlaybackState
import kotlinx.coroutines.launch

/** One editable row: a stable [key] for the list, and the line's time and text. */
private data class DraftRow(val key: Long, val timeMs: Long?, val text: String)

/** The lyrics being edited, shared by the phone screen and the desktop pop-up. */
private class LyricsDraft(track: TrackEntity) {
    val fileName = track.fileName.substringBeforeLast('.') + ".lrc"
    val rows = mutableStateListOf<DraftRow>()
    var selected by mutableIntStateOf(0)
    var paste by mutableStateOf(false)
    var pasteText by mutableStateOf("")
    var dirty by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var support by mutableStateOf<EditSupport?>(null)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var confirmDiscard by mutableStateOf(false)
    private var nextKey = 0L

    val locked get() = saving || support != EditSupport.Editable

    fun load(lines: List<LyricLine>, positionMs: Long) {
        rows.clear()
        lines.forEach { rows += DraftRow(nextKey++, it.timeMs, it.text) }
        if (rows.isEmpty()) rows += DraftRow(nextKey++, null, "")
        selected = Lrc.activeLine(lines, positionMs).coerceAtLeast(0).coerceAtMost(rows.lastIndex)
        loaded = true
    }

    fun edit(i: Int, text: String) { rows[i] = rows[i].copy(text = text); dirty = true }

    fun stamp(positionMs: Long) {
        if (rows.isEmpty()) return
        // To the hundredth of a second, as shown.
        rows[selected] = rows[selected].copy(timeMs = positionMs / 10 * 10)
        if (selected < rows.lastIndex) selected++
        dirty = true
    }

    fun addAfterSelected() {
        val at = (selected + 1).coerceAtMost(rows.size)
        rows.add(at, DraftRow(nextKey++, null, ""))
        selected = at
        dirty = true
    }

    fun delete(i: Int) {
        rows.removeAt(i)
        if (rows.isEmpty()) rows += DraftRow(nextKey++, null, "")
        selected = selected.coerceAtMost(rows.lastIndex)
        dirty = true
    }

    /** Paste mode shows the text one line per row; switching back keeps timestamps by position. */
    fun setPasteMode(on: Boolean) {
        if (on == paste) return
        if (on) pasteText = rows.joinToString("\n") { it.text } else readPaste()
        paste = on
    }

    fun readPaste() {
        val texts = pasteText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val times = rows.map { it.timeMs }
        rows.clear()
        texts.forEachIndexed { i, text -> rows += DraftRow(nextKey++, times.getOrNull(i), text) }
        if (rows.isEmpty()) rows += DraftRow(nextKey++, null, "")
        selected = selected.coerceAtMost(rows.lastIndex)
    }

    fun lines(): List<LyricLine> {
        if (paste) readPaste()
        return rows.map { LyricLine(it.timeMs, it.text) }
    }
}

@Composable
private fun rememberDraft(model: AppModel, track: TrackEntity, state: PlaybackState): LyricsDraft {
    val draft = remember(track.id) { LyricsDraft(track) }
    DisposableEffect(model.player) {
        model.player.setPauseAtTrackEnd(true)
        onDispose { model.player.setPauseAtTrackEnd(false) }
    }
    val lyrics by rememberLyrics(model, track)
    val access by model.accessRevision.collectAsState()
    LaunchedEffect(track.id, access) { draft.support = model.lyricsSupport(track) }
    LaunchedEffect(track.id) {
        // Lyrics read so far (null until known): the editor fills once, then keeps the user's edits.
        draft.load(model.lyrics(track)?.lines.orEmpty(), if (state.current?.id == track.id) state.positionMs else 0)
    }
    if (!draft.loaded && lyrics != null) draft.load(lyrics!!.lines, 0)
    return draft
}

@Composable
private fun rememberSaveLyrics(model: AppModel, track: TrackEntity, draft: LyricsDraft, onClose: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        if (!draft.locked) {
            draft.saving = true
            draft.error = null
            val lines = draft.lines()
            scope.launch {
                val error = model.saveLyrics(track, lines)
                draft.saving = false
                if (error == null) {
                    model.setShowLyrics(true)
                    onClose()
                } else {
                    draft.error = error
                }
            }
        }
    }
}

/** Android: the full-screen lyrics editor. */
@Composable
fun BoxScope.PhoneLyricsEditor(model: AppModel, track: TrackEntity, state: PlaybackState, actions: EditorActions, onClose: () -> Unit) {
    val draft = rememberDraft(model, track, state)
    val save = rememberSaveLyrics(model, track, draft, onClose)
    val leave = { if (draft.dirty) draft.confirmDiscard = true else onClose() }
    PlatformBackHandler(enabled = true) { leave() }
    val position = rememberSmoothPosition(state)
    val isCurrent = state.current?.id == track.id
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp)) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton("←", "Back", leave)
            Text("Edit lyrics", Modifier.weight(1f), color = disc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            PrimaryButton(if (draft.saving) "Saving…" else "Save", enabled = !draft.locked, onClick = save)
        }
        FileCard(draft)
        ModeSwitch(draft, Modifier.padding(top = 12.dp))
        Transport(model, track, state, position, Modifier.padding(top = 12.dp))
        Column(Modifier.padding(top = 12.dp)) {
            EditNotice(draft.support, actions, "Lyrics can't be saved here", "To save lyrics, allow Record Disc to edit your music folder. You'll choose the same folder again.")
            ErrorBox(draft.error)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { Lines(draft, model, isCurrent, compact = false) }
        Note(draft, isCurrent, desktop = false)
        if (!draft.paste) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("+ Line", Modifier.weight(1f).height(46.dp), enabled = !draft.locked) { draft.addAfterSelected() }
                PrimaryButton("Stamp ${Lrc.formatTime(if (isCurrent) position else 0)}", Modifier.weight(1.4f).height(46.dp), enabled = !draft.locked && isCurrent) { draft.stamp(position) }
            }
        }
    }
    if (draft.confirmDiscard) DiscardDialog(draft, onClose)
}

/** Desktop: the lyrics editor as a pop-up. Esc, ✕, Cancel and clicking outside ask before discarding changes. */
@Composable
fun BoxScope.DesktopLyricsEditor(model: AppModel, track: TrackEntity, state: PlaybackState, actions: EditorActions, onClose: () -> Unit) {
    val draft = rememberDraft(model, track, state)
    val save = rememberSaveLyrics(model, track, draft, onClose)
    val leave = { if (!draft.saving) { if (draft.dirty) draft.confirmDiscard = true else onClose() } }
    val position = rememberSmoothPosition(state)
    val isCurrent = state.current?.id == track.id
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.matchParentSize().background(disc.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leave() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.padding(24.dp).widthIn(max = 680.dp).fillMaxWidth().clip(shape).background(disc.panel2).border(1.dp, disc.line, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.Escape -> { if (draft.confirmDiscard) draft.confirmDiscard = false else leave(); true }
                        e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed) && !draft.paste && !draft.locked && isCurrent -> { draft.stamp(position); true }
                        else -> false
                    }
                },
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Song details")
                        Text("Edit lyrics", color = disc.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    CircleIconButton("✕", "Close lyrics editor") { leave() }
                }
                FileCard(draft)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeSwitch(draft)
                    Transport(model, track, state, position, Modifier.weight(1f))
                }
                Column(Modifier.padding(top = 12.dp)) {
                    EditNotice(draft.support, actions, "Lyrics can't be saved here")
                    ErrorBox(draft.error)
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 310.dp).padding(top = 8.dp)) { Lines(draft, model, isCurrent, compact = true) }
                Note(draft, isCurrent, desktop = true)
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!draft.paste) {
                        OutlineButton("+ Add line", enabled = !draft.locked) { draft.addAfterSelected() }
                        PrimaryButton("Stamp ${Lrc.formatTime(if (isCurrent) position else 0)}", enabled = !draft.locked && isCurrent) { draft.stamp(position) }
                        Text(
                            "Ctrl + Enter", Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, disc.line, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                            color = disc.muted, fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlineButton("Cancel", enabled = !draft.saving) { leave() }
                    PrimaryButton(if (draft.saving) "Saving…" else "Save lyrics", enabled = !draft.locked, onClick = save)
                }
            }
            if (draft.confirmDiscard) DiscardDialog(draft, onClose)
        }
    }
}

@Composable
private fun FileCard(draft: LyricsDraft) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(disc.soft), contentAlignment = Alignment.Center) {
            Text(".lrc", color = disc.accentReadable, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column {
            Text(draft.fileName, color = disc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Saved next to the audio file", color = disc.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ModeSwitch(draft: LyricsDraft, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).border(1.dp, disc.line, RoundedCornerShape(50)).padding(3.dp)) {
        for ((paste, label) in listOf(false to "Timed lines", true to "Paste text")) {
            val on = draft.paste == paste
            Text(
                label,
                Modifier.clip(RoundedCornerShape(50)).background(if (on) disc.accent else disc.tint.copy(alpha = 0f))
                    .clickable(enabled = !draft.saving) { draft.setPasteMode(paste) }.padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (on) disc.accentInk else disc.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}

/** Play/pause for the song being edited (starting it if another song is current), its time, and a seek bar. */
@Composable
private fun Transport(model: AppModel, track: TrackEntity, state: PlaybackState, position: Long, modifier: Modifier = Modifier) {
    val isCurrent = state.current?.id == track.id
    val playing = isCurrent && state.isPlaying
    val duration = (if (isCurrent) state.durationMs else null) ?: track.durationMs ?: 0L
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().clip(shape).border(1.dp, disc.line, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClickLabel = if (playing) "Pause" else "Play") {
                if (isCurrent) model.togglePlay() else model.playForEditing(track)
            }.semantics { contentDescription = if (playing) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) DiscIcons.Pause else DiscIcons.Play, null, Modifier.size(16.dp), tint = disc.text)
        }
        Text(Lrc.formatTime(if (isCurrent) position else 0), color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        ThinSlider(
            if (isCurrent && duration > 0) position.toFloat() / duration else 0f,
            onCommit = { f -> if (isCurrent) model.player.seekTo((f * duration).toLong()) },
            label = "Seek", modifier = Modifier.weight(1f),
        )
        Text(formatTime(duration), color = disc.muted, fontSize = 11.sp)
    }
}

/** The timed rows, or the paste box. */
@Composable
private fun Lines(draft: LyricsDraft, model: AppModel, isCurrent: Boolean, compact: Boolean) {
    val fieldShape = RoundedCornerShape(10.dp)
    if (draft.paste) {
        BasicTextField(
            draft.pasteText, { draft.pasteText = it; draft.dirty = true },
            Modifier.fillMaxSize().heightIn(min = if (compact) 240.dp else 160.dp).clip(fieldShape).background(if (compact) disc.panel else disc.panel2)
                .border(1.dp, disc.line, fieldShape).padding(12.dp).semantics { contentDescription = "Paste lyrics" },
            enabled = !draft.locked,
            textStyle = TextStyle(color = disc.text, fontSize = 13.sp, lineHeight = 1.6.em),
            cursorBrush = SolidColor(disc.accentReadable),
            decorationBox = { inner ->
                Box {
                    if (draft.pasteText.isEmpty()) Text("Paste lyrics here, one line per row", color = disc.muted.copy(alpha = 0.7f), fontSize = 13.sp)
                    inner()
                }
            },
        )
        return
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.rows.forEachIndexed { i, row ->
            val selected = i == draft.selected
            val rowShape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().clip(rowShape).background(if (selected) disc.soft else disc.tint.copy(alpha = 0f))
                    .border(1.dp, if (selected) disc.accent.copy(alpha = 0.45f) else disc.line, rowShape)
                    .clickable { draft.selected = i }.padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val time = row.timeMs
                Text(
                    Lrc.formatTime(time),
                    Modifier.width(if (compact) 78.dp else 72.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Select line and jump to its time") {
                            draft.selected = i
                            if (time != null && isCurrent) model.player.seekTo(time)
                        }.padding(vertical = 6.dp),
                    color = if (time == null) disc.muted else disc.accentReadable, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                BasicTextField(
                    row.text, { draft.edit(i, it) },
                    Modifier.weight(1f).semantics { contentDescription = "Lyric line ${i + 1}" },
                    enabled = !draft.locked, singleLine = true,
                    textStyle = TextStyle(color = disc.text, fontSize = 13.sp),
                    cursorBrush = SolidColor(disc.accentReadable),
                    decorationBox = { inner ->
                        Box { if (row.text.isEmpty()) Text("Lyric line", color = disc.muted.copy(alpha = 0.6f), fontSize = 13.sp); inner() }
                    },
                )
                Text(
                    "×", Modifier.clip(CircleShape).clickable(enabled = !draft.locked, onClickLabel = "Delete line") { draft.delete(i) }.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = disc.muted, fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Note(draft: LyricsDraft, isCurrent: Boolean, desktop: Boolean) {
    val text = when {
        draft.paste -> "One line per row. Switch back to Timed lines to add timestamps."
        !isCurrent -> "Press play to start this song, then ${if (desktop) "press" else "tap"} Stamp as each line starts."
        desktop -> "Play the song and press Stamp as each line starts. Click a time to jump there."
        else -> "Play the song and tap Stamp as each line starts. Tap a time to jump there."
    }
    Text(text, Modifier.padding(top = 8.dp), color = disc.muted, fontSize = 11.sp, lineHeight = 1.5.em)
}

@Composable
private fun BoxScope.DiscardDialog(draft: LyricsDraft, onClose: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.matchParentSize().background(disc.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 320.dp).padding(16.dp).clip(shape).background(disc.panel2).border(1.dp, disc.line, shape).padding(18.dp)) {
            Text("Discard changes?", color = disc.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Your changes to these lyrics haven't been saved.", Modifier.padding(top = 6.dp, bottom = 16.dp), color = disc.muted, fontSize = 12.sp, lineHeight = 1.5.em)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Keep editing", Modifier.weight(1f).height(44.dp)) { draft.confirmDiscard = false }
                PrimaryButton("Discard", Modifier.weight(1f).height(44.dp)) {
                    draft.confirmDiscard = false
                    draft.dirty = false
                    onClose()
                }
            }
        }
    }
}
