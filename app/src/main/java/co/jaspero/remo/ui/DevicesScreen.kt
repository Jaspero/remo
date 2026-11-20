package co.jaspero.remo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.TvController
import co.jaspero.remo.TvController.Pairing
import co.jaspero.remo.data.SavedTv

@Composable
fun RemoRoot(c: TvController) {
    val current by c.current.collectAsState()
    var showDevices by rememberSaveable { mutableStateOf(false) }

    // Picking or pairing a TV takes you back to the remote.
    LaunchedEffect(current?.name) { showDevices = false }
    BackHandler(enabled = showDevices && current != null) { showDevices = false }

    if (current == null || showDevices) {
        DevicesScreen(c, onClose = if (current != null) ({ showDevices = false }) else null)
    } else {
        RemoteScreen(c, onOpenDevices = { showDevices = true })
    }
    PairingDialog(c)
}

@Composable
fun DevicesScreen(c: TvController, onClose: (() -> Unit)?) {
    val tvs by c.tvs.collectAsState()
    val current by c.current.collectAsState()
    val found by c.discovery.found.collectAsState()
    val searching by c.discovery.searching.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<SavedTv?>(null) }

    DisposableEffect(Unit) {
        c.discovery.start()
        onDispose { c.discovery.stop() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RemoColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your TVs", color = RemoColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = RemoColors.Muted) }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tvs.isNotEmpty()) {
                item { SectionLabel("Paired") }
                items(tvs, key = { "saved-" + it.name }) { tv ->
                    TvRow(
                        name = tv.name,
                        detail = tv.host,
                        selected = tv.name == current?.name,
                        onClick = { c.select(tv); onClose?.invoke() },
                        trailing = {
                            IconButton(onClick = { forget = tv }) { Icon(Icons.Filled.Delete, "Forget", tint = RemoColors.Muted) }
                        },
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    SectionLabel("On this Wi-Fi")
                    if (searching) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = RemoColors.Muted)
                    }
                }
            }
            val unpaired = found.filter { f -> tvs.none { it.name == f.name && it.host == f.host } }
            if (unpaired.isEmpty()) {
                item {
                    Text(
                        if (searching) "Looking for Google TV and Android TV devices…" else "No TVs found yet.",
                        color = RemoColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(unpaired, key = { "found-" + it.name }) { f ->
                val known = tvs.any { it.name == f.name }
                TvRow(
                    name = f.name,
                    detail = if (known) "${f.host} · tap to pair again" else "${f.host} · tap to pair",
                    selected = false,
                    onClick = { c.startPairing(f.name, f.host) },
                )
            }
            item {
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enter the TV's IP address")
                }
            }
            item {
                Text(
                    "Works with Google TV and Android TV (Chromecast with Google TV, Sony, TCL, Hisense, Philips, " +
                        "Nvidia Shield and others). Your phone and TV need to be on the same Wi-Fi. " +
                        "The TV's IP address is in its Settings → Network & Internet.",
                    color = RemoColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (manual) ManualDialog(onDismiss = { manual = false }) { name, host ->
        manual = false
        c.startPairing(name, host)
    }
    forget?.let { tv ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Forget ${tv.name}?") },
            text = { Text("You'll need to pair again to use it.") },
            confirmButton = { TextButton(onClick = { c.forget(tv); forget = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = RemoColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun TvRow(name: String, detail: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RemoColors.Surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tv, null, tint = if (selected) RemoColors.Accent else RemoColors.Muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = RemoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = RemoColors.Muted, fontSize = 12.sp)
        }
        if (selected) Icon(Icons.Filled.CheckCircle, "In use", tint = RemoColors.Accent, modifier = Modifier.padding(end = 8.dp))
        trailing()
    }
}

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onPair: (String, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val valid = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a TV by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    host, { host = it }, singleLine = true,
                    label = { Text("IP address") }, placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name (optional)") }, placeholder = { Text("Living room TV") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onPair(name.trim().ifEmpty { "TV ${host.trim()}" }, host.trim()) }, enabled = valid) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PairingDialog(c: TvController) {
    val state by c.pairing.collectAsState()
    when (val s = state) {
        Pairing.Idle -> Unit
        is Pairing.Starting -> AlertDialog(
            onDismissRequest = c::cancelPairing,
            title = { Text("Pairing with ${s.name}") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Asking the TV for a code…")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = c::cancelPairing) { Text("Cancel") } },
        )
        is Pairing.EnterCode -> CodeDialog(c, s)
        is Pairing.Failed -> AlertDialog(
            onDismissRequest = c::cancelPairing,
            title = { Text("Couldn't pair") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = c::cancelPairing) { Text("OK") } },
        )
    }
}

@Composable
private fun CodeDialog(c: TvController, s: Pairing.EnterCode) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.error) { if (s.error != null) code = "" }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        focus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = c::cancelPairing,
        title = { Text("Enter the code on your TV") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("${s.name} is showing a 6-character code.", color = RemoColors.Muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v ->
                        code = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        if (code.length == 6 && !s.verifying) c.submitCode(code)
                    },
                    singleLine = true,
                    enabled = !s.verifying,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 28.sp, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp,
                        textAlign = TextAlign.Center, color = RemoColors.Text,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6) c.submitCode(code) }),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth().focusRequester(focus),
                )
                Box(Modifier.padding(top = 10.dp)) {
                    when {
                        s.verifying -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        s.error != null -> Text(s.error, color = RemoColors.Power, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.submitCode(code) }, enabled = code.length == 6 && !s.verifying) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = c::cancelPairing) { Text("Cancel") } },
    )
}
