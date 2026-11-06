package dev.recorddisc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.recorddisc.player.app.AccentPreset
import dev.recorddisc.player.app.AppModel
import dev.recorddisc.player.app.AppSettings
import dev.recorddisc.player.app.ThemeMode
import dev.recorddisc.player.app.ScanState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** On/off switch drawn like the mockups: accent track with a dark knob when on. */
@Composable
fun SettingSwitch(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track = if (checked) disc.accent else disc.tint.copy(alpha = 0.08f)
    Box(
        Modifier.size(44.dp, 24.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(50)).background(track)
            .then(if (checked) Modifier else Modifier.border(1.dp, disc.line, RoundedCornerShape(50)))
            .clickable(enabled = enabled, role = Role.Switch, onClickLabel = label) { onChange(!checked) }
            .semantics { contentDescription = label; stateDescription = if (checked) "On" else "Off" }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) disc.accentInk else disc.muted))
    }
}

/** Small outlined button ("Change", "Rescan"). */
@Composable
fun SmallButton(text: String, enabled: Boolean = true, textSize: Int = 13, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        Modifier.alpha(if (enabled) 1f else 0.6f).clip(shape).background(disc.tint.copy(alpha = 0.03f)).border(1.dp, disc.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = disc.text, fontSize = textSize.sp,
    )
}

/** A settings row: label (and a smaller line under it) on the left, the control on the right. */
@Composable
fun SettingRow(label: String, sub: String? = null, divider: Boolean = true, textSize: Int = 15, soon: Boolean = false, control: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = if (soon) disc.muted else disc.text, fontSize = textSize.sp)
                    if (soon) SoonTag()
                }
                if (sub != null) Text(sub, Modifier.padding(top = 2.dp), color = disc.muted, fontSize = (textSize - 3).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            control()
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(disc.line))
    }
}

@Composable
fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = disc.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.em)

/** Theme: System, Dark or Light; the current one is shown and ticked. */
@Composable
fun ThemeChooser(model: AppModel, textSize: Int = 13) {
    val current = model.settings.collectAsState().value?.theme ?: ThemeMode.Dark
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(disc.tint.copy(alpha = 0.03f)).border(1.dp, disc.line, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose theme") { open = true }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(current.name, color = disc.text, fontSize = textSize.sp)
            Text("▾", color = disc.muted, fontSize = textSize.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (mode in ThemeMode.entries) {
                DropdownMenuItem(
                    text = { Text(mode.name, color = disc.text) },
                    trailingIcon = { if (mode == current) Icon(DiscIcons.Check, null, Modifier.size(16.dp), tint = disc.accentReadable) },
                    onClick = { open = false; model.setTheme(mode) },
                )
            }
        }
    }
}

/**
 * The six preset accents and the custom colour, as in the mockups. Picking one turns Dynamic
 * color (Android) or Use system accent (desktop, [systemOn]) off.
 */
@Composable
fun AccentSwatches(model: AppModel, settings: AppSettings, dynamicOn: Boolean, size: Dp, gap: Dp, onCustom: () -> Unit, systemOn: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        fun pick(action: () -> Unit) {
            action()
            if (dynamicOn) model.setFlag(AppSettings.DYNAMIC_COLOR, false)
            if (systemOn) model.setFlag(AppSettings.USE_SYSTEM_ACCENT, false)
        }
        for (preset in AccentPreset.entries) {
            Swatch(size, selected = !dynamicOn && !systemOn && settings.accent == preset, label = preset.name, fill = Brush.linearGradient(listOf(Color(preset.argb), Color(preset.argb)))) {
                pick { model.setAccent(preset) }
            }
        }
        Swatch(size, selected = !dynamicOn && !systemOn && settings.accent == null, label = "Custom colour", fill = Brush.sweepGradient(RAINBOW), inner = Color(settings.customAccent)) {
            pick(onCustom)
        }
    }
}

@Composable
private fun Swatch(size: Dp, selected: Boolean, label: String, fill: Brush, inner: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).then(if (selected) Modifier.border(2.dp, disc.text, CircleShape) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label; if (selected) stateDescription = "Selected" }
            .padding(if (selected) 4.dp else 0.dp).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (inner != null) Box(Modifier.size(size * 0.45f).clip(CircleShape).background(inner).border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape))
    }
}

private val RAINBOW = listOf(Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000))

/** The desktop's accent colour for "Use system accent"; [name] when the desktop gives one (GNOME). */
data class SystemAccent(val argb: Long, val name: String?)

/** The desktop's current accent colour, or null when it doesn't share one (and on Android). */
val LocalSystemAccent = compositionLocalOf<SystemAccent?> { null }

/** The name under "Accent color". [system] is the followed desktop accent, if any. */
fun AppSettings.accentName(dynamicOn: Boolean, system: SystemAccent? = null): String = when {
    system != null -> "System · ${system.name ?: "desktop accent"}"
    dynamicOn -> "Cover art"
    accent == null -> "Custom"
    else -> accent.name
}

/** What the Rescan button says: "Rescan", or the live count while scanning. */
@Composable
fun rescanLabel(model: AppModel): Pair<String, Boolean> {
    val scan by model.scan.collectAsState()
    val s = scan
    return if (s is ScanState.Running) "Scanning… ${s.checked}" to true else "Rescan" to false
}

// ---------------------------------------------------------------- Custom accent picker

/** Hue (0–360), saturation and value (0–1) of an ARGB colour. */
internal fun toHsv(argb: Long): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val d = hi - lo
    val h = when {
        d == 0f -> 0f
        hi == r -> 60f * (((g - b) / d) % 6f)
        hi == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0) it + 360f else it }
    return Triple(h, if (hi == 0f) 0f else d / hi, hi)
}

/** "#3674B3". */
internal fun hexOf(color: Color): String = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/**
 * The "Custom accent" dialog over a dimmed screen: a saturation/brightness square, a hue bar,
 * a preview with the hex value, Cancel and Apply. [eyebrow] is "Appearance" on desktop.
 */
@Composable
fun BoxScope.AccentPicker(start: Long, eyebrow: String? = null, onCancel: () -> Unit, onApply: (Long) -> Unit) {
    val (h0, s0, v0) = remember(start) { toHsv(start) }
    var hue by remember { mutableFloatStateOf(h0) }
    var sat by remember { mutableFloatStateOf(s0) }
    var value by remember { mutableFloatStateOf(v0) }
    val color = Color.hsv(hue, sat, value)
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.matchParentSize().scrimIn().background(disc.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).popIn().widthIn(max = 390.dp).fillMaxWidth().clip(shape).background(disc.panel2).border(1.dp, disc.line, shape)
                // Clicks inside the card don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (eyebrow != null) Eyebrow(eyebrow)
                    Text("Custom accent", color = disc.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                CircleIconButton("✕", "Close", onCancel)
            }
            SatValSquare(hue, sat, value) { s, v -> sat = s; value = v }
            HueBar(hue) { hue = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(color))
                Text("Preview", Modifier.weight(1f), color = disc.muted, fontSize = 13.sp)
                Text(hexOf(color), color = disc.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Same height for both, as in the mockup.
                OutlineButton("Cancel", Modifier.weight(1f).height(46.dp), onClick = onCancel)
                PrimaryButton("Apply", Modifier.weight(1f).height(46.dp)) { onApply(color.toArgb().toLong() and 0xFFFFFFFF) }
            }
        }
    }
}

@Composable
private fun SatValSquare(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    fun pick(p: Offset, w: Int, h: Int) = onChange((p.x / w).coerceIn(0f, 1f), 1f - (p.y / h).coerceIn(0f, 1f))
    Canvas(
        Modifier.fillMaxWidth().height(170.dp).clip(shape)
            .semantics { contentDescription = "Saturation and brightness" }
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width, size.height) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position, size.width, size.height) } },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val knob = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.hsv(hue, sat, value), 9.dp.toPx(), knob)
        drawCircle(Color.White, 9.dp.toPx(), knob, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    fun pick(x: Float, w: Int) = onChange((x / w).coerceIn(0f, 1f) * 360f)
    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .semantics { contentDescription = "Hue" }
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position.x, size.width) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(RAINBOW)))
        Canvas(Modifier.fillMaxSize()) {
            val x = (hue / 360f) * size.width
            val c = Offset(x.coerceIn(11.dp.toPx(), size.width - 11.dp.toPx()), size.height / 2)
            drawCircle(Color.White, 11.dp.toPx(), c)
            drawCircle(Color.Black.copy(alpha = 0.2f), 11.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------- Android

/** Android Settings, as in the mockup: Appearance, Library and Playback. */
@Composable
fun PhoneSettings(model: AppModel, onBack: () -> Unit, onChooseFolder: () -> Unit, onCustomAccent: () -> Unit) {
    val settings = model.settings.collectAsState().value ?: return
    val dynamicOn = settings.dynamicColor
    val (rescan, scanning) = rescanLabel(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 96.dp)) {
        Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton("←", "Back to disc", onBack)
            Text("Settings", color = disc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        SettingsSectionLabel("Appearance")
        SettingRow("Theme") { ThemeChooser(model) }
        SettingRow("Accent color", settings.accentName(dynamicOn)) {
            AccentSwatches(model, settings, dynamicOn, 26.dp, 5.dp, onCustomAccent)
        }
        SettingRow("Dynamic color", "From current cover art") {
            SettingSwitch(dynamicOn, "Dynamic color", enabled = true) { model.setFlag(AppSettings.DYNAMIC_COLOR, it) }
        }
        ShineRow(model, settings, textSize = null)
        BatterySaverRow(model, settings, textSize = null)
        SettingsSectionLabel("Library", Modifier.padding(top = 22.dp))
        SettingRow("Music folder", settings.musicFolder?.let(model.platform::folderLabel)) { SmallButton("Change", enabled = !scanning, onClick = onChooseFolder) }
        SettingRow("Rescan library", divider = false) { SmallButton(rescan, enabled = !scanning, onClick = model::rescanNow) }
        SettingsSectionLabel("Playback", Modifier.padding(top = 22.dp))
        SettingRow("Resume on start", "Reopen the last song where you left off") {
            SettingSwitch(settings.resumeOnStart, "Resume on start") { model.setFlag(AppSettings.RESUME_ON_START, it) }
        }
        SettingRow("Pause on headphone unplug") {
            SettingSwitch(settings.pauseOnUnplug, "Pause on headphone unplug") { model.setFlag(AppSettings.PAUSE_ON_UNPLUG, it) }
        }
        MuteRows(model, settings, textSize = null)
    }
}

/**
 * Pause when muted (on by default) and Resume when unmuted (off by default, only if muting paused
 * it; greyed out while Pause when muted is off). 2026-11-04 mockups.
 */
/**
 * Settings → Shine brightness (2026-11-06 mockups): a slider from Off to Bright under the label,
 * 0 to 300 % of the default shine in 5 % steps; the subtitle shows the level live while dragging.
 */
@Composable
private fun ShineRow(model: AppModel, settings: AppSettings, textSize: Int?) {
    val size = textSize ?: 15
    var dragging by remember { mutableStateOf<Float?>(null) }
    fun percent(fraction: Float) = (fraction * AppSettings.SHINE_MAX / 5).roundToInt() * 5
    val shown = dragging?.let(::percent) ?: settings.shineLevel
    Column {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("Shine brightness", color = disc.text, fontSize = size.sp)
            Text(
                "How bright the light on the record is · ${if (shown == 0) "Off" else "$shown%"}",
                Modifier.padding(top = 2.dp), color = disc.muted, fontSize = (size - 3).sp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Off", color = disc.muted, fontSize = (size - 4).sp)
                ThinSlider(
                    settings.shineLevel / AppSettings.SHINE_MAX.toFloat(),
                    { model.setShineLevel(percent(it)) },
                    "Shine brightness",
                    Modifier.weight(1f),
                    trackHeight = 3.dp,
                    onDrag = { dragging = it },
                )
                Text("Bright", color = disc.muted, fontSize = (size - 4).sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(disc.line))
    }
}

/**
 * Settings → Battery Saver (2026-11-05 mockups): manual, off by default. Turns off all motion, which
 * is what costs battery while the app is on screen (the spinning disc most).
 */
@Composable
private fun BatterySaverRow(model: AppModel, settings: AppSettings, textSize: Int?) {
    val sub = "Turns off all motion: the spinning disc and transitions"
    SettingRow("Battery Saver", sub, divider = false, textSize = textSize ?: 15) {
        SettingSwitch(settings.batterySaver, "Battery Saver") { model.setFlag(AppSettings.BATTERY_SAVER, it) }
    }
}

@Composable
private fun MuteRows(model: AppModel, settings: AppSettings, textSize: Int?, pauseSub: String? = null) {
    val size = textSize ?: 15
    SettingRow("Pause when muted", pauseSub, textSize = size) {
        SettingSwitch(settings.pauseOnMute, "Pause when muted") { model.setFlag(AppSettings.PAUSE_ON_MUTE, it) }
    }
    // Last row on Android (no divider); the desktop card continues with Gapless playback.
    SettingRow("Resume when unmuted", "Only if muting paused it", divider = textSize != null, textSize = size) {
        SettingSwitch(settings.resumeOnUnmute, "Resume when unmuted", enabled = settings.pauseOnMute) {
            model.setFlag(AppSettings.RESUME_ON_UNMUTE, it)
        }
    }
}

// ---------------------------------------------------------------- Desktop

@Composable
private fun SettingsCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(disc.tint.copy(alpha = 0.02f)).border(1.dp, disc.line, shape).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        SettingsSectionLabel(title, Modifier.padding(bottom = 4.dp))
        content()
    }
}

/**
 * Desktop Settings: Appearance and Library on the left, Playback and About on the right (one
 * column when narrow). [chooseFolder] opens the folder dialog.
 */
@Composable
fun DesktopSettings(model: AppModel, narrow: Boolean, appVersion: String, chooseFolder: () -> String?, onCustomAccent: () -> Unit) {
    val settings = model.settings.collectAsState().value ?: return
    val tracks by model.tracks.collectAsState()
    val (rescan, scanning) = rescanLabel(model)
    val appearance: @Composable (Modifier) -> Unit = { m ->
        SettingsCard("Appearance", m) {
            SettingRow("Theme", textSize = 13) { ThemeChooser(model, textSize = 12) }
            // "Use system accent" (2026-11-04 mockup; was "Dynamic color", Soon). Off by default; greyed
            // out when the desktop doesn't share an accent colour.
            val system = LocalSystemAccent.current
            val followingSystem = settings.useSystemAccent && system != null
            SettingRow("Accent color", settings.accentName(false, system.takeIf { followingSystem }), textSize = 13) {
                AccentSwatches(model, settings, dynamicOn = false, 22.dp, 7.dp, onCustomAccent, systemOn = followingSystem)
            }
            SettingRow(
                "Use system accent",
                if (system != null) "Follows your desktop's accent${system.name?.let { " · $it" } ?: ""}" else "Your desktop doesn't share an accent color",
                textSize = 13,
            ) {
                SettingSwitch(followingSystem, "Use system accent", enabled = system != null) { model.setFlag(AppSettings.USE_SYSTEM_ACCENT, it) }
            }
            ShineRow(model, settings, textSize = 13)
            BatterySaverRow(model, settings, textSize = 13)
        }
    }
    val library: @Composable (Modifier) -> Unit = { m ->
        SettingsCard("Library", m) {
            SettingRow("Music folder", settings.musicFolder?.let(model.platform::folderLabel), textSize = 13) {
                SmallButton("Change", enabled = !scanning, textSize = 12) { chooseFolder()?.let(model::scan) }
            }
            SettingRow("Rescan library", textSize = 13) { SmallButton(rescan, enabled = !scanning, textSize = 12, onClick = model::rescanNow) }
            SettingRow("Watch for changes", "Add new files automatically", divider = false, textSize = 13) {
                SettingSwitch(settings.watchFolder, "Watch for changes") { model.setFlag(AppSettings.WATCH_FOLDER, it) }
            }
        }
    }
    val playback: @Composable (Modifier) -> Unit = { m ->
        SettingsCard("Playback", m) {
            SettingRow("Resume on start", "Reopen the last song where you left off", textSize = 13) {
                SettingSwitch(settings.resumeOnStart, "Resume on start") { model.setFlag(AppSettings.RESUME_ON_START, it) }
            }
            SettingRow("Pause on headphone unplug", "When headphones or a Bluetooth device disconnect", textSize = 13) {
                SettingSwitch(settings.pauseOnUnplug, "Pause on headphone unplug") { model.setFlag(AppSettings.PAUSE_ON_UNPLUG, it) }
            }
            MuteRows(model, settings, textSize = 13, pauseSub = "System mute, or the volume slider at 0")
            SettingRow("Gapless playback", divider = false, textSize = 13) {
                SettingSwitch(settings.gapless, "Gapless playback") { model.setFlag(AppSettings.GAPLESS, it) }
            }
        }
    }
    val about: @Composable (Modifier) -> Unit = { m ->
        SettingsCard("About", m) {
            SettingRow("Record Player Desktop", textSize = 13) { Text(appVersion, color = disc.muted, fontSize = 11.sp) }
            val formats = tracks.map { it.format.uppercase() }.distinct().sorted().joinToString(", ").ifEmpty { "—" }
            SettingRow("Library format", textSize = 13) { Text("Local · $formats", color = disc.muted, fontSize = 11.sp, textAlign = TextAlign.End) }
            SettingRow("Privacy", "No account, no telemetry", divider = false, textSize = 13) {
                Text(
                    "OFFLINE", Modifier.clip(RoundedCornerShape(50)).background(disc.soft).border(1.dp, disc.accent.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = disc.accentReadable, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.em,
                )
            }
        }
    }
    if (narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (card in listOf(appearance, playback, library, about)) card(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                appearance(Modifier.fillMaxWidth())
                library(Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                playback(Modifier.fillMaxWidth())
                about(Modifier.fillMaxWidth())
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
