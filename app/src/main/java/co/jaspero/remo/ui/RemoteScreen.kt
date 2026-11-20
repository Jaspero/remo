package co.jaspero.remo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.TvController
import co.jaspero.remo.TvController.Conn
import co.jaspero.remo.data.Preset
import co.jaspero.remo.tv.Keys
import co.jaspero.remo.tv.RemoteSession.Direction

@Composable
fun RemoteScreen(c: TvController, onOpenDevices: () -> Unit) {
    val tv by c.current.collectAsState()
    val conn by c.conn.collectAsState()
    val state by c.tvState.collectAsState()
    val presets by c.presets.collectAsState()
    val settings by c.settings.collectAsState()
    val haptics = settings.haptics

    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var addPreset by remember { mutableStateOf(false) }

    // The TV asked for a keyboard (a search box or login field got focus): bring ours up.
    LaunchedEffect(state.textField) {
        if (state.textField != null && conn == Conn.Connected) keyboardOpen = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RemoColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        // ---- Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenDevices)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(conn)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tv?.name ?: "No TV",
                            color = RemoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ArrowDropDown, null, tint = RemoColors.Muted)
                    }
                    Text(statusLine(conn, state, presets), color = RemoColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SettingsMenu(c, onOpenDevices)
            Spacer(Modifier.width(4.dp))
            RoundKey(
                Icons.Filled.PowerSettingsNew, "", haptics, size = 48.dp,
                tint = if (state.isOn == false) RemoColors.Muted else RemoColors.Power,
                background = RemoColors.Power.copy(alpha = 0.14f),
            ) { c.key(Keys.POWER) }
        }

        ConnectionBanner(conn, onRetry = c::retry, onPair = { tv?.let { c.startPairing(it.name, it.host) } })

        // ---- App presets
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(presets, key = { it.id }) { p ->
                PresetTile(p, active = p.pkg.isNotEmpty() && p.pkg == state.currentApp, onLaunch = { c.launch(p) }, onRemove = { c.removePreset(p) })
            }
            item {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, RemoColors.Outline, RoundedCornerShape(16.dp))
                        .clickable { addPreset = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "Add app", tint = RemoColors.Muted) }
            }
        }

        // ---- Volume level, when the TV reports it
        val vol = state.volume
        if (vol != null && vol.max > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (vol.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null,
                    tint = RemoColors.Muted, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { vol.level.toFloat() / vol.max },
                    modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                    color = if (vol.muted) RemoColors.Muted else RemoColors.Accent,
                    trackColor = RemoColors.Raised,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.width(8.dp))
                Text("${vol.level}", color = RemoColors.Muted, fontSize = 12.sp, modifier = Modifier.width(24.dp))
            }
        }

        // ---- D-pad / touchpad
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            if (settings.touchpad) {
                TouchPad(haptics, onKey = { c.key(it) }, modifier = Modifier.fillMaxSize())
            } else {
                DPad(
                    haptics,
                    onKey = { c.key(it) },
                    onLongStart = { c.key(Keys.CENTER, Direction.START_LONG) },
                    onLongEnd = { c.key(Keys.CENTER, Direction.END_LONG) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- Navigation keys
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", haptics) { c.key(Keys.BACK) }
            RoundKey(
                Icons.Filled.Home, "Home", haptics,
                onLongStart = { c.key(Keys.HOME, Direction.START_LONG) },
                onLongEnd = { c.key(Keys.HOME, Direction.END_LONG) },
            ) { c.key(Keys.HOME) }
            RoundKey(Icons.Filled.Keyboard, "Keyboard", haptics, tint = RemoColors.Accent) { keyboardOpen = true }
            MoreKeys(c, haptics)
        }

        // ---- Volume and media
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyPill(
                listOf(
                    Triple(Icons.AutoMirrored.Filled.VolumeDown, "Volume down") { c.key(Keys.VOLUME_DOWN) },
                    Triple(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { c.key(Keys.MUTE) },
                    Triple(Icons.AutoMirrored.Filled.VolumeUp, "Volume up") { c.key(Keys.VOLUME_UP) },
                ),
                haptics,
                repeatable = setOf("Volume down", "Volume up"),
            )
            if (settings.showMedia) {
                KeyPill(
                    listOf(
                        Triple(Icons.Filled.FastRewind, "Rewind") { c.key(Keys.REWIND) },
                        Triple(Icons.Filled.PlayArrow, "Play/pause") { c.key(Keys.PLAY_PAUSE) },
                        Triple(Icons.Filled.FastForward, "Fast forward") { c.key(Keys.FAST_FORWARD) },
                    ),
                    haptics,
                )
            }
        }
    }

    if (keyboardOpen) {
        KeyboardSheet(c, state.textField, onDismiss = { keyboardOpen = false })
    }
    if (addPreset) {
        AddPresetDialog(onDismiss = { addPreset = false }, onAdd = { name, target -> c.addPreset(name, target); addPreset = false })
    }
}

private fun statusLine(conn: Conn, state: co.jaspero.remo.tv.RemoteSession.State, presets: List<Preset>): String = when (conn) {
    Conn.Connected -> {
        val app = state.currentApp
        val name = presets.firstOrNull { it.pkg.isNotEmpty() && it.pkg == app }?.name
            ?: app.substringAfterLast('.').replaceFirstChar { it.uppercase() }.takeIf { app.isNotEmpty() }
        when {
            state.isOn == false -> "Connected · screen off"
            name != null -> "Connected · $name"
            else -> "Connected"
        }
    }
    Conn.Connecting -> "Connecting…"
    Conn.NeedsPairing -> "Needs pairing"
    is Conn.Unreachable -> "Not reachable"
    Conn.NoTv -> "Add a TV"
}

@Composable
private fun StatusDot(conn: Conn) {
    val color = when (conn) {
        Conn.Connected -> RemoColors.Good
        Conn.Connecting -> RemoColors.Warn
        else -> RemoColors.Power
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ConnectionBanner(conn: Conn, onRetry: () -> Unit, onPair: () -> Unit) {
    val (text, action, onAction) = when (conn) {
        is Conn.Unreachable -> Triple(conn.message, "Retry", onRetry)
        Conn.NeedsPairing -> Triple("This TV doesn't recognise your phone yet (or forgot it).", "Pair", onPair)
        else -> Triple(null, null, null)
    }
    AnimatedVisibility(text != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(RemoColors.Power.copy(alpha = 0.12f))
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text.orEmpty(), color = RemoColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(p: Preset, active: Boolean, onLaunch: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(width = 84.dp, height = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(p.background))
                .then(if (active) Modifier.border(2.dp, RemoColors.Text, RoundedCornerShape(16.dp)) else Modifier)
                .combinedClickable(onClick = onLaunch, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                p.name,
                color = Color(p.foreground),
                fontWeight = FontWeight.Black,
                fontSize = if (p.name.length > 7) 13.sp else 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open ${p.name}") }, onClick = { menu = false; onLaunch() })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
private fun MoreKeys(c: TvController, haptics: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundKey(Icons.Filled.MoreHoriz, "More", haptics) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, icon: ImageVector, key: Int) = @Composable {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, null) },
                    onClick = { c.key(key) },
                )
            }
            item("Channel up", Icons.Filled.KeyboardArrowUp, Keys.CHANNEL_UP)()
            item("Channel down", Icons.Filled.KeyboardArrowDown, Keys.CHANNEL_DOWN)()
            HorizontalDivider()
            item("Previous", Icons.Filled.SkipPrevious, Keys.PREVIOUS)()
            item("Next", Icons.Filled.SkipNext, Keys.NEXT)()
            HorizontalDivider()
            item("Input / source", Icons.Filled.Input, Keys.TV_INPUT)()
            item("Guide", Icons.Filled.LiveTv, Keys.GUIDE)()
            item("Captions", Icons.Filled.ClosedCaption, Keys.CAPTIONS)()
            item("Info", Icons.Filled.Info, Keys.INFO)()
            item("Menu", Icons.Filled.Menu, Keys.MENU)()
            item("Search", Icons.Filled.Search, Keys.SEARCH)()
            item("TV settings", Icons.Filled.Settings, Keys.SETTINGS)()
        }
    }
}

@Composable
private fun SettingsMenu(c: TvController, onOpenDevices: () -> Unit) {
    val settings by c.settings.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Settings", tint = RemoColors.Muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("TVs") },
                leadingIcon = { Icon(Icons.Filled.Tv, null) },
                onClick = { open = false; onOpenDevices() },
            )
            HorizontalDivider()
            Toggle("Phone volume buttons control TV", settings.volumeKeys) { v -> c.updateSettings { it.copy(volumeKeys = v) } }
            Toggle("Touchpad instead of arrows", settings.touchpad) { v -> c.updateSettings { it.copy(touchpad = v) } }
            Toggle("Media buttons", settings.showMedia) { v -> c.updateSettings { it.copy(showMedia = v) } }
            Toggle("Vibrate on press", settings.haptics) { v -> c.updateSettings { it.copy(haptics = v) } }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Restore YouTube, Netflix, Disney+, Kodi") }, onClick = { open = false; c.restoreDefaultPresets() })
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label, modifier = Modifier.padding(end = 8.dp)) },
        trailingIcon = { Switch(checked = value, onCheckedChange = null) },
        onClick = { onChange(!value) },
    )
}

@Composable
private fun AddPresetDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    target, { target = it }, singleLine = true,
                    label = { Text("App package or link") },
                    placeholder = { Text("com.amazon.amazonvideo.livingroom") },
                )
                Text(
                    "Use the app's package name (from its Play Store link: …?id=package) or a link like https://www.primevideo.com.",
                    color = RemoColors.Muted, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, target) }, enabled = name.isNotBlank() && target.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
