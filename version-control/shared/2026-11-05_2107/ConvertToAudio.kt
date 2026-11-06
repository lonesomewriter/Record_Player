package dev.recorddisc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.ConvertStatus
import dev.recorddisc.player.app.ConvertTask
import dev.recorddisc.player.convert.ConvertFormat
import dev.recorddisc.player.convert.ConvertOptions
import dev.recorddisc.player.convert.ConvertRules
import dev.recorddisc.player.library.PlaylistView

/*
 * Convert to Audio (2026-11-04 mockups): the dialog that sets up a conversion, the progress popup
 * (closing it leaves the job running in the Inbox), and the Inbox card. Shared by both platforms.
 */

/** A file chosen with Change / Choose…: where it is, and how to show it. */
data class ChosenFile(val location: String, val label: String)

/** The text shown when the progress popup is closed mid-conversion. */
const val STILL_CONVERTING = "Still converting — it's in your Inbox"

/**
 * Convert to Audio for [playlist]. After Convert, it turns into the progress popup; closing that
 * leaves the job running in the Inbox. [desktop] picks "Choose…" over "Change".
 */
@Composable
fun ConvertDialog(model: AppModel, playlist: PlaylistView, actions: PlaylistFileActions, desktop: Boolean, onDismiss: () -> Unit) {
    val options = remember { model.convertFormats() }
    val usable = options.filter { it.available }
    var format by remember { mutableStateOf(usable.firstOrNull()?.format ?: ConvertFormat.Flac) }
    var gapText by remember { mutableStateOf("0") }
    var evenOut by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf(ConvertRules.withExtension(ConvertRules.fileName(playlist.name), format)) }
    var chosen by remember { mutableStateOf<ChosenFile?>(null) }
    var formatMenu by remember { mutableStateOf(false) }
    var startedId by remember { mutableStateOf<Long?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val defaultFolder = model.convertDefaultFolder
    val gap = ConvertRules.parseGap(gapText)
    val task by model.convertTask.collectAsState()

    // After Convert: the progress popup (and the done state), for this job only.
    startedId?.let { id ->
        val current = task?.takeIf { it.id == id }
        if (current == null) { onDismiss(); return }
        ConvertProgressDialog(
            current,
            onHide = {
                if (current.status == ConvertStatus.Running) model.message(STILL_CONVERTING) else model.dismissConversion()
                onDismiss()
            },
            onCancel = model::cancelConversion,
            onDone = { model.dismissConversion(); onDismiss() },
        )
        return
    }

    fun setFormat(next: ConvertFormat) {
        format = next
        fileName = ConvertRules.withExtension(fileName, next)
        // A chosen file has the old extension, so it's chosen again for the new format.
        chosen = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CONVERT TO AUDIO", color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em)
                    Text(playlist.name, color = disc.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                CircleIconButton("✕", "Close", onDismiss)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val totalMs = ConvertRules.totalMs(playlist.songs.map { it.durationMs }, gap)
                Text("${plural(playlist.songs.size, "song")} · ${ConvertRules.estimate(format, totalMs).substringAfter(" · ")}", color = disc.muted, fontSize = 12.sp)
                if (usable.isEmpty()) {
                    Text(
                        if (desktop) "Converting needs GStreamer's encoders, which aren't installed." else "This phone has no audio encoders the app can use.",
                        color = disc.error, fontSize = 12.sp,
                    )
                }

                // Format: a list like the mockup's select; formats this device can't make are greyed out with why.
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    FieldLabel("Format")
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, disc.line, RoundedCornerShape(10.dp))
                                .clickable(enabled = usable.isNotEmpty(), onClickLabel = "Choose a format") { formatMenu = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(format.label, Modifier.weight(1f), color = disc.text, fontSize = 13.sp)
                            Text("▾", color = disc.muted, fontSize = 12.sp)
                        }
                        DropdownMenu(formatMenu, onDismissRequest = { formatMenu = false }) {
                            for (option in options) {
                                DropdownMenuItem(
                                    enabled = option.available,
                                    text = {
                                        Text(
                                            if (option.available) option.format.label else "${option.format.label.substringBefore(" ·")} · ${option.note ?: "not available"}",
                                            color = if (option.available) disc.text else disc.muted,
                                        )
                                    },
                                    onClick = { formatMenu = false; setFormat(option.format) },
                                )
                            }
                        }
                    }
                }

                // Gap between songs: whole seconds, − greyed out at 0, never below 0 (decision 6).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Gap between songs", Modifier.weight(1f), color = disc.text, fontSize = 13.sp)
                    Row(
                        Modifier.semantics { contentDescription = "Gap between songs in seconds" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StepButton("−", "Shorter gap", enabled = gap > 0) { gapText = (gap - 1).coerceAtLeast(0).toString() }
                        OutlinedTextField(
                            value = gapText,
                            onValueChange = { text -> gapText = text.filter { it.isDigit() }.take(5) },
                            modifier = Modifier.width(72.dp),
                            singleLine = true,
                            suffix = { Text("s", color = disc.muted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(color = disc.text, fontSize = 13.sp, textAlign = TextAlign.End),
                        )
                        StepButton("+", "Longer gap", enabled = true) { gapText = (gap + 1).toString() }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Even out volume", color = disc.text, fontSize = 13.sp)
                        Text("Match loudness · takes about twice as long", color = disc.muted, fontSize = 11.sp)
                    }
                    SettingSwitch(evenOut, "Even out volume") { evenOut = it }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    FieldLabel("File name")
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it; chosen = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = disc.text, fontSize = 13.sp),
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save to", color = disc.text, fontSize = 13.sp)
                        Text(chosen?.label ?: defaultFolder ?: "Choose where to save it", color = disc.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    SmallButton(if (desktop) "Choose…" else if (defaultFolder == null && chosen == null) "Save to" else "Change") {
                        actions.chooseConvertDestination(ConvertRules.withExtension(fileName, format), format) { picked -> if (picked != null) chosen = picked }
                    }
                }

                Text(format.marksNote, color = disc.muted, fontSize = 11.sp)
                Text("${ConvertRules.estimate(format, totalMs)} · Converted on this device. Nothing is uploaded.", color = disc.muted, fontSize = 11.sp)
                problem?.let { Text(it, color = disc.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            val canStart = format in usable.map { it.format } && playlist.songs.isNotEmpty() && (defaultFolder != null || chosen != null)
            TextButton(enabled = canStart, onClick = {
                val request = ConvertOptions(
                    format = format, gapSeconds = gap, evenOutVolume = evenOut,
                    fileName = ConvertRules.withExtension(fileName, format),
                    destination = chosen?.location, destinationLabel = chosen?.label,
                )
                // Android starts it with its background service; desktop calls the model directly.
                // (Not `?:`: the service path returns null on success, which must not start it twice.)
                val startInBackground = actions.startBackgroundConversion
                val failure = if (startInBackground != null) startInBackground(playlist.id, playlist.name, request)
                else model.startConversion(playlist.id, playlist.name, request)
                if (failure != null) problem = failure else startedId = model.convertTask.value?.id
            }) { Text("Convert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The progress popup: song N of M, %, and Cancel conversion. When it's done it shows where the file
 * went. [onHide] closes it (the job keeps running, in the Inbox).
 */
@Composable
fun ConvertProgressDialog(task: ConvertTask, onHide: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onHide,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CONVERT TO AUDIO", color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.16.em)
                    Text(task.playlistName, color = disc.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                CircleIconButton("✕", if (task.status == ConvertStatus.Running) "Hide conversion progress" else "Close", onHide)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (task.status) {
                    ConvertStatus.Running -> {
                        ConvertProgressBody(task)
                        Text(
                            "You can close this: converting carries on in the background, waits in the Inbox, and a notification appears when it's done.",
                            color = disc.muted, fontSize = 11.sp,
                        )
                    }
                    ConvertStatus.Completed -> {
                        Text("✓ ${task.saved?.shownAs?.substringAfterLast('/') ?: task.fileName}", color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Saved to ${task.saved?.shownAs?.substringBeforeLast('/', task.destinationLabel) ?: task.destinationLabel}", color = disc.muted, fontSize = 12.sp)
                        if (task.skipped > 0) Text("${plural(task.skipped, "song")} no longer in your library ${if (task.skipped == 1) "was" else "were"} left out.", color = disc.muted, fontSize = 11.sp)
                    }
                    ConvertStatus.Failed -> Text(task.failure ?: "The conversion failed", color = disc.error, fontSize = 12.sp)
                    ConvertStatus.Cancelled -> Text("Conversion cancelled. Nothing was saved.", color = disc.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (task.status == ConvertStatus.Running) TextButton(onClick = onCancel) { Text("Cancel conversion") }
            else TextButton(onClick = onDone) { Text("Done") }
        },
    )
}

/** "Song 3 of 12 · Title", the % and a bar. */
@Composable
private fun ConvertProgressBody(task: ConvertTask) {
    val p = task.progress
    val step = when {
        task.songs == 0 -> "Getting the songs ready"
        p == null -> "Song 1 of ${task.songs}"
        p.measuring -> "Measuring volume · song ${p.song + 1} of ${p.songs}"
        else -> "Song ${p.song + 1} of ${p.songs}"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(step, color = disc.muted, fontSize = 11.sp)
            Text(task.currentTitle ?: "", color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${task.percent}%", color = disc.accentReadable, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    LinearProgressIndicator(progress = { task.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = disc.accentReadable, trackColor = disc.line)
}

/**
 * The job in the Inbox: "Converting “Road Trip”", %, the song, and Open progress / Cancel while
 * it runs; "Converted …", where it was saved and Dismiss when it's done.
 */
@Composable
fun ConvertInboxCard(task: ConvertTask, onOpenProgress: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit, onShowInFolder: (() -> Unit)? = null) {
    val running = task.status == ConvertStatus.Running
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(disc.panel2).border(1.dp, disc.line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(disc.soft), contentAlignment = Alignment.Center) {
                Text("♫", color = disc.accentReadable, fontSize = 16.sp)
            }
            Column(Modifier.weight(1f)) {
                val title = when (task.status) {
                    ConvertStatus.Running -> "Converting “${task.playlistName}”"
                    ConvertStatus.Completed -> "Converted “${task.playlistName}”"
                    ConvertStatus.Failed -> "Couldn't convert “${task.playlistName}”"
                    ConvertStatus.Cancelled -> "Cancelled “${task.playlistName}”"
                }
                Text(title, color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${task.format.label.substringBefore(" ·")} · ${task.fileName}", color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (running || task.status == ConvertStatus.Completed) Text("${task.percent}%", color = disc.accentReadable, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        when (task.status) {
            ConvertStatus.Running -> {
                val p = task.progress
                Text(
                    listOfNotNull(
                        if (p?.measuring == true) "Measuring volume · song ${p.song + 1} of ${p.songs}" else "Song ${(p?.song ?: 0) + 1} of ${task.songs.coerceAtLeast(1)}",
                        task.currentTitle,
                    ).joinToString(" · "),
                    color = disc.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { task.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = disc.accentReadable, trackColor = disc.line)
                Row(Modifier.fillMaxWidth()) {
                    Text("Saving to ${task.destinationLabel}", Modifier.weight(1f), color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("In progress", color = disc.muted, fontSize = 11.sp)
                }
            }
            ConvertStatus.Completed -> Text("Saved to ${task.saved?.shownAs ?: task.destinationLabel}", color = disc.muted, fontSize = 12.sp)
            ConvertStatus.Failed -> Text(task.failure ?: "The conversion failed", color = disc.error, fontSize = 12.sp)
            ConvertStatus.Cancelled -> Text("Nothing was saved.", color = disc.muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                SmallButton("Open progress", onClick = onOpenProgress)
                SmallButton("Cancel", onClick = onCancel)
            } else {
                if (task.status == ConvertStatus.Completed && onShowInFolder != null) SmallButton("Show in folder", onClick = onShowInFolder)
                SmallButton("Dismiss", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, color = disc.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(9.dp)).border(1.dp, disc.line, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = disc.text, fontSize = 16.sp)
    }
}

/**
 * The Convert to Audio card in an Inbox (phone and desktop), with Open progress reopening the
 * popup. Draws nothing when there's no job. [showInFolder]: offer Show in folder when it's done.
 */
@Composable
fun ConvertInboxSection(model: AppModel, showInFolder: Boolean, modifier: Modifier = Modifier) {
    val task by model.convertTask.collectAsState()
    val current = task ?: return
    var progressOpen by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf<dev.recorddisc.player.convert.ConvertSaved?>(null) }
    Box(modifier) {
        ConvertInboxCard(
            current,
            onOpenProgress = { progressOpen = true },
            onCancel = model::cancelConversion,
            onDismiss = model::dismissConversion,
            onShowInFolder = current.saved?.takeIf { showInFolder && it.folder != null }?.let { saved -> { reveal = saved } },
        )
    }
    if (progressOpen) {
        ConvertProgressDialog(
            current,
            onHide = { progressOpen = false },
            onCancel = model::cancelConversion,
            onDone = { progressOpen = false; model.dismissConversion() },
        )
    }
    reveal?.let { saved ->
        reveal = null
        viewFileLocation(saved.location, saved.folder)
    }
}
