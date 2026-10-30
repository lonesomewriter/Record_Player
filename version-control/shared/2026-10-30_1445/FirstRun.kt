package dev.recorddisc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.ScanState

private const val FORMATS = "We looked for MP3, FLAC, M4A, OGG, Opus and WAV files in that folder and the folders inside it."

/** Which first-run step a scan state shows. */
private enum class Step { Intro, Scan, Done, Empty }

private fun ScanState.step(): Step = when (this) {
    ScanState.Idle -> Step.Intro
    is ScanState.Running -> Step.Scan
    is ScanState.Done -> if (songs > 0) Step.Done else Step.Empty
    is ScanState.Failed -> Step.Empty
}

private val ScanState.folder: String?
    get() = when (this) {
        ScanState.Idle -> null
        is ScanState.Running -> folder
        is ScanState.Done -> folder
        is ScanState.Failed -> folder
    }

/** Android first run: blank disc, then Android's folder picker, scanning, and the result. */
@Composable
fun PhoneWelcome(
    scan: ScanState,
    spin: DiscSpin,
    folderLabel: (String) -> String,
    onChooseFolder: () -> Unit,
    onCancelScan: () -> Unit,
    onStart: () -> Unit,
) {
    val step = scan.step()
    val label = scan.folder?.let(folderLabel).orEmpty()
    Column(Modifier.fillMaxSize().background(disc.panel).padding(horizontal = 20.dp, vertical = 22.dp)) {
        PhoneTopRow()
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            MarqueeArc(320.dp, running = step == Step.Scan, bothSides = true, dim = step != Step.Scan)
            Disc(222.dp, spin, art = null, blank = true)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                Step.Intro -> {
                    Heading("Let's find your music", 22.sp)
                    Body("Choose the folder where your songs live. Record Disc plays them from there.")
                    Promises(
                        "It can only see the folder you choose",
                        "No account, no internet, nothing uploaded",
                        "Change the folder anytime in Settings",
                    )
                    PrimaryButton("Choose music folder", Modifier.fillMaxWidth(), onChooseFolder)
                    Text("Opens Android's folder picker", color = disc.muted, fontSize = 11.sp, letterSpacing = 0.04.em)
                }
                Step.Scan -> {
                    Heading("Finding your music…", 22.sp)
                    FolderChip(label)
                    BigCount((scan as ScanState.Running).checked)
                    Body("songs found")
                    Text(scan.fileName, color = disc.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Cancel", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancelScan).padding(6.dp), color = disc.accentReadable, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Step.Done -> {
                    val done = scan as ScanState.Done
                    Heading("You're all set", 22.sp)
                    BigCount(done.songs)
                    Body("${plural(done.songs, "song")} · ${plural(done.albums, "album")} · ${plural(done.artists, "artist")}\nin $label")
                    PrimaryButton("Start listening", Modifier.fillMaxWidth(), onStart)
                }
                Step.Empty -> {
                    Heading(if (scan is ScanState.Failed) "Couldn't read $label" else "No music in $label", 22.sp)
                    Body(if (scan is ScanState.Failed) scan.message else FORMATS)
                    PrimaryButton("Choose another folder", Modifier.fillMaxWidth(), onChooseFolder)
                }
            }
        }
    }
}

/**
 * Desktop first run, in the Home layout: blank disc on the left; on the right the detected
 * Music folder with Change…, then scanning and the result.
 */
@Composable
fun DesktopWelcome(
    scan: ScanState,
    spin: DiscSpin,
    folder: String?,
    folderLabel: (String) -> String,
    onChangeFolder: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onStart: () -> Unit,
    narrow: Boolean,
) {
    val step = scan.step()
    val discSize = if (narrow) 280.dp else 360.dp
    val discPart: @Composable () -> Unit = {
        Box(Modifier.size(discSize * 1.3f), contentAlignment = Alignment.Center) {
            MarqueeRing(discSize * 424f / 360f, running = step == Step.Scan, fast = true, dim = step != Step.Scan)
            Disc(discSize, spin, art = null, blank = true)
        }
    }
    val textPart: @Composable ColumnScope.() -> Unit = {
        val shown = scan.folder ?: folder
        val label = shown?.let(folderLabel).orEmpty()
        when (step) {
            Step.Intro -> {
                Eyebrow("Welcome")
                Heading("Let's find your music", 40.sp, TextAlign.Start)
                Body("Record Disc plays the music already on this computer, straight from your music folder.", TextAlign.Start)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(disc.soft)
                        .border(1.dp, disc.accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("📁", fontSize = 22.sp)
                    Column(Modifier.weight(1f)) {
                        Text(label.ifEmpty { "No folder chosen" }, color = disc.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (folder != null && folder == shown) "Your Music folder, from your desktop settings" else "Chosen by you",
                            color = disc.muted, fontSize = 11.5.sp,
                        )
                    }
                    OutlineButton("Change…", onClick = onChangeFolder)
                }
                Promises(
                    "Only this folder (and the folders inside it) is read",
                    "No account, no internet, nothing uploaded",
                    "Change it anytime in Settings",
                )
                if (shown != null) PrimaryButton("Scan and start", onClick = onScan)
            }
            Step.Scan -> {
                Eyebrow("Scanning $label")
                Heading("Finding your music…", 40.sp, TextAlign.Start)
                BigCount((scan as ScanState.Running).checked, 64.sp)
                Body("songs found", TextAlign.Start)
                Text(scan.fileName, color = disc.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlineButton("Cancel", onClick = onCancelScan)
            }
            Step.Done -> {
                val done = scan as ScanState.Done
                Eyebrow("Ready")
                Heading("You're all set", 40.sp, TextAlign.Start)
                BigCount(done.songs, 64.sp)
                Body("${plural(done.songs, "song")} · ${plural(done.albums, "album")} · ${plural(done.artists, "artist")} in $label", TextAlign.Start)
                PrimaryButton("Start listening", onClick = onStart)
            }
            Step.Empty -> {
                Eyebrow("Nothing found")
                Heading(if (scan is ScanState.Failed) "Couldn't read $label" else "No music in $label", 40.sp, TextAlign.Start)
                Body(if (scan is ScanState.Failed) scan.message else FORMATS, TextAlign.Start)
                PrimaryButton("Choose another folder", onClick = onChangeFolder)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(disc.shell).padding(horizontal = 40.dp, vertical = 34.dp)) {
        BrandRow(Modifier.align(Alignment.TopStart), showSub = !narrow)
        if (narrow) {
            Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                discPart()
                Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = textPart)
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.weight(1.05f), contentAlignment = Alignment.Center) { discPart() }
                Column(Modifier.weight(0.95f).widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = textPart)
            }
        }
    }
}

fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun Heading(text: String, size: TextUnit, align: TextAlign = TextAlign.Center) =
    Text(text, color = disc.text, fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, textAlign = align, lineHeight = size * 1.06f)

@Composable
private fun Body(text: String, align: TextAlign = TextAlign.Center) =
    Text(text, color = disc.muted, fontSize = 13.5.sp, lineHeight = 21.sp, textAlign = align)

@Composable
private fun BigCount(n: Int, size: TextUnit = 40.sp) =
    Text(n.toString(), color = disc.accentReadable, fontSize = size, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, lineHeight = size)

@Composable
fun Eyebrow(text: String) =
    Text(text.uppercase(), color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.18.em)

@Composable
private fun FolderChip(label: String) =
    Text("📁 $label", Modifier.border(1.dp, disc.line, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp), color = disc.text, fontSize = 12.sp)

@Composable
private fun Promises(vararg lines: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in lines) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(DiscIcons.Check, null, Modifier.size(16.dp), tint = disc.accentReadable)
                Text(line, color = disc.text, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(disc.accent).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = disc.accentInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, disc.line, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 15.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = disc.text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/** "MUSIC   ● LOCAL" across the top of the phone's home screens. */
@Composable
fun PhoneTopRow() {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("MUSIC", color = disc.text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.24.em)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalLight()
            Text("LOCAL", color = disc.muted, fontSize = 10.sp, letterSpacing = 0.2.em)
        }
    }
}

/** The desktop brand: a small record with the accent label, "Record Disc / Desktop player". */
@Composable
fun BrandRow(modifier: Modifier = Modifier, showSub: Boolean = true, markSize: Dp = 34.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(markSize).clip(RoundedCornerShape(50)).background(disc.bg).border(1.dp, disc.line, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(markSize - 22.dp).clip(RoundedCornerShape(50)).background(disc.accent))
        }
        Column {
            Text("RECORD DISC", color = disc.text, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.19.em)
            if (showSub) Text("DESKTOP PLAYER", color = disc.muted, fontSize = 9.sp, letterSpacing = 0.16.em)
        }
    }
}
