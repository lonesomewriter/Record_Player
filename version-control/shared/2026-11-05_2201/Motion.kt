package dev.recorddisc.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/*
 * Desktop motion (2026-11-05, as in the desktop mockup): pages fade in while rising slightly,
 * dialogs and overlays fade in while rising and growing slightly. Only the desktop layout turns it
 * on (LocalDesktopMotion), and never when the system asks for less motion. The phone has its own
 * slide transitions.
 */

/** True inside the desktop layout: the modifiers below animate only there. */
val LocalDesktopMotion = staticCompositionLocalOf { false }

/** Page change: 0.28 s, rising 8 dp (the mockup's `.view` transition). */
private const val PAGE_MS = 280

/** Dialog / overlay card: 0.22 s, rising 10 dp from 98.5% size (the mockup's `.overlay-card`). */
private const val POP_MS = 220

/** Dimmed background behind a dialog: 0.2 s fade (the mockup's `.overlay::before`). */
private const val SCRIM_MS = 200

@Composable
private fun motionOn(): Boolean = LocalDesktopMotion.current && !rememberReduceMotion()

/**
 * Fades the content in while it rises 8 dp each time [key] changes, so a new page arrives
 * smoothly. Nothing happens the first time it's shown, on the phone, or with reduced motion.
 */
@Composable
fun Modifier.pageEnter(key: Any?): Modifier {
    if (!motionOn()) return this
    val progress = remember { Animatable(1f) }
    val shown = remember { BooleanArray(1) }
    LaunchedEffect(key) {
        if (!shown[0]) { shown[0] = true; return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(PAGE_MS, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

/** A dialog or overlay card appearing: fades in while rising 10 dp and growing from 98.5%. */
@Composable
fun Modifier.popIn(): Modifier {
    if (!motionOn()) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(POP_MS, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
        val scale = 0.985f + 0.015f * progress.value
        scaleX = scale
        scaleY = scale
    }
}

/** The dimmed background behind a dialog or overlay fading in. */
@Composable
fun Modifier.scrimIn(): Modifier {
    if (!motionOn()) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(SCRIM_MS)) }
    return graphicsLayer { alpha = progress.value }
}
