package co.jaspero.remo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.tv.YouTubeLinks
import co.jaspero.remo.ui.RemoColors
import co.jaspero.remo.ui.RemoTheme
import kotlinx.coroutines.delay

/**
 * "Play on TV" in the share menu: takes a YouTube link from any app and plays it on the TV,
 * in SmartTube when it's there. Shows a small card and closes itself.
 */
class ShareActivity : ComponentActivity() {
    private val app get() = application as RemoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString.orEmpty()
        val video = YouTubeLinks.find(text)
        setContent { RemoTheme { PlaySheet(app.installer, video, onClose = ::finish, onOpenRemo = ::openRemo) } }
    }

    override fun onStart() {
        super.onStart()
        app.controller.onForeground()
    }

    override fun onStop() {
        super.onStop()
        app.controller.onBackground()
    }

    private fun openRemo() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

private sealed interface PlayUi {
    data object Sending : PlayUi
    data class Done(val title: String, val detail: String?) : PlayUi
    data class Failed(val message: String) : PlayUi
}

@Composable
private fun PlaySheet(installer: AppInstaller, video: YouTubeLinks.Video?, onClose: () -> Unit, onOpenRemo: () -> Unit) {
    var ui by remember { mutableStateOf<PlayUi>(PlayUi.Sending) }
    LaunchedEffect(video) {
        ui = if (video == null) {
            PlayUi.Failed("That isn't a link to a YouTube video.")
        } else when (val r = installer.play(video)) {
            is AppInstaller.PlayResult.Playing -> PlayUi.Done(
                "Playing on ${r.tvName}",
                if (r.inSmartTube) "In SmartTube, without ads" else "In the TV's YouTube app. Install SmartTube from Remo to skip the ads.",
            )
            is AppInstaller.PlayResult.Failed -> PlayUi.Failed(r.message)
        }
        if (ui is PlayUi.Done) {
            delay(1800)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = true, enter = slideInVertically { it }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(RemoColors.Surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (ui) {
                        PlayUi.Sending -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        is PlayUi.Done -> Icon(Icons.Filled.CheckCircle, null, tint = RemoColors.Good)
                        is PlayUi.Failed -> Icon(Icons.Filled.ErrorOutline, null, tint = RemoColors.Power)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        when (val u = ui) {
                            PlayUi.Sending -> "Sending to your TV…"
                            is PlayUi.Done -> u.title
                            is PlayUi.Failed -> "Couldn't play it"
                        },
                        color = RemoColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                val detail = when (val u = ui) {
                    PlayUi.Sending -> "If the TV asks to allow debugging, choose Always allow."
                    is PlayUi.Done -> u.detail
                    is PlayUi.Failed -> u.message
                }
                if (detail != null) Text(detail, color = RemoColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 36.dp))
                if (ui !is PlayUi.Sending) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onOpenRemo) { Text("Open remote") }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }
            }
        }
    }
}
