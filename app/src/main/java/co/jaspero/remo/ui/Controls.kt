package co.jaspero.remo.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.tv.Keys
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Remote-style press handling. With [repeat], the action fires on touch and repeats while held
 * (for arrows and volume). Otherwise it fires on release, or [onLongStart]/[onLongEnd] fire for a
 * long press when given.
 */
fun Modifier.remotePress(
    haptics: Boolean,
    repeat: Boolean = false,
    onPressedChange: (Boolean) -> Unit = {},
    onLongStart: (() -> Unit)? = null,
    onLongEnd: (() -> Unit)? = null,
    onTap: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val longStart by rememberUpdatedState(onLongStart)
    val longEnd by rememberUpdatedState(onLongEnd)
    val pressedChange by rememberUpdatedState(onPressedChange)
    val haptic by rememberUpdatedState(haptics)
    pointerInput(repeat) {
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                pressedChange(true)
                if (haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (repeat) tap()
                var long = false
                val job = launch {
                    delay(if (repeat) 380 else 450)
                    long = true
                    if (repeat) {
                        while (isActive) {
                            tap()
                            delay(85)
                        }
                    } else if (longStart != null) {
                        if (haptic) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        longStart?.invoke()
                    }
                }
                val up = waitForUpOrCancellation()
                job.cancel()
                pressedChange(false)
                if (!repeat) {
                    when {
                        long && longStart != null -> longEnd?.invoke()
                        up != null -> tap()
                    }
                }
            }
        }
    }
}

@Composable
fun RoundKey(
    icon: ImageVector,
    label: String,
    haptics: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    tint: Color = RemoColors.Text,
    background: Color = RemoColors.Raised,
    repeat: Boolean = false,
    onLongStart: (() -> Unit)? = null,
    onLongEnd: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (pressed) RemoColors.RaisedPressed else background)
                .remotePress(haptics, repeat, { pressed = it }, onLongStart, onLongEnd, onTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = tint, modifier = Modifier.size(size * 0.42f))
        }
        if (label.isNotEmpty()) Text(label, color = RemoColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A row of keys sharing one pill-shaped background, like a volume rocker. */
@Composable
fun KeyPill(
    keys: List<Triple<ImageVector, String, () -> Unit>>,
    haptics: Boolean,
    modifier: Modifier = Modifier,
    repeatable: Set<String> = emptySet(),
    height: Dp = 56.dp,
) {
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(RemoColors.Raised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { (icon, label, action) ->
            var pressed by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .width(height * 1.05f)
                    .fillMaxHeight()
                    .background(if (pressed) RemoColors.RaisedPressed else Color.Transparent)
                    .remotePress(haptics, label in repeatable, { pressed = it }, onTap = action),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = RemoColors.Text, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun DPad(haptics: Boolean, onKey: (Int) -> Unit, onLongStart: () -> Unit, onLongEnd: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight, 320.dp)
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(RemoColors.Surface)
                .border(1.dp, RemoColors.Outline, CircleShape),
        ) {
            val arm = size * 0.30f
            val span = size * 0.46f
            DirKey(Icons.Filled.KeyboardArrowUp, "Up", haptics, Modifier.align(Alignment.TopCenter).width(span).height(arm), RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)) { onKey(Keys.UP) }
            DirKey(Icons.Filled.KeyboardArrowDown, "Down", haptics, Modifier.align(Alignment.BottomCenter).width(span).height(arm), RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)) { onKey(Keys.DOWN) }
            DirKey(Icons.Filled.KeyboardArrowLeft, "Left", haptics, Modifier.align(Alignment.CenterStart).width(arm).height(span), RoundedCornerShape(topEnd = 40.dp, bottomEnd = 40.dp)) { onKey(Keys.LEFT) }
            DirKey(Icons.Filled.KeyboardArrowRight, "Right", haptics, Modifier.align(Alignment.CenterEnd).width(arm).height(span), RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp)) { onKey(Keys.RIGHT) }

            var pressed by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(size * 0.36f)
                    .clip(CircleShape)
                    .background(if (pressed) RemoColors.RaisedPressed else RemoColors.Raised)
                    .remotePress(haptics, false, { pressed = it }, onLongStart, onLongEnd) { onKey(Keys.CENTER) },
                contentAlignment = Alignment.Center,
            ) {
                Text("OK", color = RemoColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun DirKey(icon: ImageVector, label: String, haptics: Boolean, modifier: Modifier, shape: Shape, onTap: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) RemoColors.RaisedPressed else Color.Transparent)
            .remotePress(haptics, repeat = true, onPressedChange = { pressed = it }, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = RemoColors.Text, modifier = Modifier.size(34.dp))
    }
}

/**
 * Swipe to move, tap for OK. A long swipe keeps moving: every [stepDp] of travel is one more step.
 */
@Composable
fun TouchPad(haptics: Boolean, onKey: (Int) -> Unit, modifier: Modifier = Modifier, stepDp: Dp = 44.dp) {
    val view = LocalView.current
    val step = with(LocalDensity.current) { stepDp.toPx() }
    val key by rememberUpdatedState(onKey)
    val haptic by rememberUpdatedState(haptics)
    var active by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(if (active) RemoColors.Raised else RemoColors.Surface)
            .border(1.dp, RemoColors.Outline, RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    active = true
                    var acc = Offset.Zero
                    var moved = false
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val d = change.positionChange()
                        acc += d
                        total += abs(d.x) + abs(d.y)
                        change.consume()
                        while (abs(acc.x) >= step || abs(acc.y) >= step) {
                            moved = true
                            if (abs(acc.x) > abs(acc.y)) {
                                key(if (acc.x > 0) Keys.RIGHT else Keys.LEFT)
                                acc = acc.copy(x = acc.x - step * kotlin.math.sign(acc.x), y = 0f)
                            } else {
                                key(if (acc.y > 0) Keys.DOWN else Keys.UP)
                                acc = acc.copy(y = acc.y - step * kotlin.math.sign(acc.y), x = 0f)
                            }
                            if (haptic) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (!change.pressed) break
                    }
                    active = false
                    if (!moved && total < step / 2) {
                        if (haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        key(Keys.CENTER)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.TouchApp, contentDescription = null, tint = RemoColors.Muted.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
            Text("Swipe to move · tap for OK", color = RemoColors.Muted.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}
