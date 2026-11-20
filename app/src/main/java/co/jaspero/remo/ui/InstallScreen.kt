package co.jaspero.remo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.AppInstaller
import co.jaspero.remo.AppInstaller.State
import co.jaspero.remo.TvController
import co.jaspero.remo.tv.Keys

@Composable
fun InstallScreen(c: TvController, installer: AppInstaller, onClose: () -> Unit) {
    val state by installer.state.collectAsState()
    val tv by c.current.collectAsState()
    val haptics = c.settings.collectAsState().value.haptics
    var setupOpen by rememberSaveable { mutableStateOf(true) }
    var linkDialog by remember { mutableStateOf(false) }
    val busy = state is State.Working
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installer.installFromPhone(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RemoColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Install apps on TV", color = RemoColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { installer.reset(); onClose() }) { Icon(Icons.Filled.Close, "Close", tint = RemoColors.Muted) }
        }
        Text(
            "Puts apps on ${tv?.name ?: "your TV"} straight from your phone, including ones that aren't in the TV's Play Store.",
            color = RemoColors.Muted, fontSize = 14.sp,
        )

        // ---- One-time setup
        Column(
            Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(RemoColors.Surface),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { setupOpen = !setupOpen }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("One-time setup on the TV", color = RemoColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (setupOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = RemoColors.Muted)
            }
            AnimatedVisibility(setupOpen) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Step(1, "On the TV open Settings → System → About.")
                    Step(2, "Select \"Android TV OS build\" 7 times, until it says you're a developer.")
                    Step(3, "Go back to Settings → System → Developer options and turn on \"USB debugging\".")
                    Step(4, "The first time you install, the TV asks \"Allow USB debugging?\". Tick \"Always allow\" and choose OK. You can use the arrows below.")
                }
            }
        }

        // ---- SmartTube
        Column(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1E1E1E))
                .padding(16.dp),
        ) {
            Text("SmartTube", color = Color(0xFFFF4E45), fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(
                "YouTube for your TV with no ads and sponsor skipping. Free and open source; Remo gets the latest stable build from its official GitHub releases.",
                color = RemoColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = installer::installSmartTube,
                enabled = !busy && tv != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E45), contentColor = Color.White),
            ) { Text("Install SmartTube") }
        }

        // ---- Other apps
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = !busy && tv != null) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("APK on this phone")
            }
            OutlinedButton(onClick = { linkDialog = true }, enabled = !busy && tv != null) {
                Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("From a link")
            }
        }

        // ---- Progress / result
        StatusCard(state, haptics, c, installer)
        Spacer(Modifier.height(32.dp))
    }

    if (linkDialog) LinkDialog(onDismiss = { linkDialog = false }) { link ->
        linkDialog = false
        installer.installFromLink(link)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Box(Modifier.size(22.dp).clip(CircleShape).background(RemoColors.Raised), contentAlignment = Alignment.Center) {
            Text("$n", color = RemoColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = RemoColors.Text, fontSize = 14.sp)
    }
}

@Composable
private fun StatusCard(state: State, haptics: Boolean, c: TvController, installer: AppInstaller) {
    if (state == State.Idle) return
    Column(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RemoColors.Surface)
            .padding(16.dp),
    ) {
        when (state) {
            is State.Working -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.progress == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.RadioButtonChecked, null, tint = RemoColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(state.step, color = RemoColors.Text, modifier = Modifier.weight(1f))
                    TextButton(onClick = installer::cancel) { Text("Cancel") }
                }
                state.progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape),
                        trackColor = RemoColors.Raised,
                        drawStopIndicator = {},
                    )
                }
                if (state.waitingForTv) {
                    Text(
                        "If the TV asks \"Allow USB debugging?\", tick \"Always allow\" and choose OK:",
                        color = RemoColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                    )
                    MiniPad(haptics) { c.key(it) }
                }
            }
            is State.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = RemoColors.Good)
                    Spacer(Modifier.width(12.dp))
                    Text("${state.label} is installed", color = RemoColors.Text, fontWeight = FontWeight.SemiBold)
                }
                if (state.addedButton) {
                    Text("It has its own button on the remote now.", color = RemoColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (state.pkg.isNotEmpty()) {
                    Button(onClick = { installer.launch(state.pkg) }, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open on TV")
                    }
                }
            }
            is State.Failed -> {
                Row {
                    Icon(Icons.Filled.ErrorOutline, null, tint = RemoColors.Power)
                    Spacer(Modifier.width(12.dp))
                    Text(state.message, color = RemoColors.Text, fontSize = 14.sp)
                }
            }
            State.Idle -> Unit
        }
    }
}

/** Enough of a remote to answer the TV's prompt without leaving this screen. */
@Composable
private fun MiniPad(haptics: Boolean, onKey: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundKey(Icons.Filled.KeyboardArrowLeft, "", haptics, size = 48.dp, repeat = true) { onKey(Keys.LEFT) }
        RoundKey(Icons.Filled.KeyboardArrowUp, "", haptics, size = 48.dp, repeat = true) { onKey(Keys.UP) }
        RoundKey(Icons.Filled.CheckCircle, "", haptics, size = 48.dp, tint = RemoColors.Accent) { onKey(Keys.CENTER) }
        RoundKey(Icons.Filled.KeyboardArrowDown, "", haptics, size = 48.dp, repeat = true) { onKey(Keys.DOWN) }
        RoundKey(Icons.Filled.KeyboardArrowRight, "", haptics, size = 48.dp, repeat = true) { onKey(Keys.RIGHT) }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    val valid = link.trim().startsWith("http://") || link.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install from a link") },
        text = {
            OutlinedTextField(
                link, { link = it }, singleLine = true,
                label = { Text("Link to an .apk file") },
                placeholder = { Text("https://…/app.apk") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = { TextButton(onClick = { onInstall(link) }, enabled = valid) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
