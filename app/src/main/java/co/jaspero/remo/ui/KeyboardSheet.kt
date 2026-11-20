package co.jaspero.remo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.jaspero.remo.TvController
import co.jaspero.remo.tv.Keys
import co.jaspero.remo.tv.RemoteSession
import kotlinx.coroutines.delay

/**
 * Your phone's keyboard, typing live into whatever is focused on the TV. Opens by itself when the
 * TV shows a text field, and can be opened by hand on any screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSheet(c: TvController, field: RemoteSession.TextField?, onDismiss: () -> Unit) {
    val settings by c.settings.collectAsState()
    val initial = field?.value.orEmpty()
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    // What the TV's field holds as far as we know: edits are sent as the difference from this.
    var sent by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = RemoColors.Surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding()) {
            Text(
                field?.label?.takeIf { it.isNotBlank() } ?: "Type on your TV",
                color = RemoColors.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    if (v.text != sent) {
                        c.typeDiff(sent, v.text)
                        sent = v.text
                    }
                    value = v
                },
                singleLine = true,
                placeholder = { Text("Start typing…") },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onAny = { c.key(Keys.ENTER) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundKey(Icons.AutoMirrored.Filled.Backspace, "Delete", settings.haptics, size = 52.dp, repeat = true) {
                    // Always send it: the TV's field may hold text we never saw.
                    c.key(Keys.DEL)
                    if (sent.isNotEmpty()) {
                        sent = sent.dropLast(1)
                        value = TextFieldValue(sent, TextRange(sent.length))
                    }
                }
                RoundKey(Icons.Filled.Clear, "Clear", settings.haptics, size = 52.dp) {
                    val count = maxOf(sent.length, 40)
                    repeat(count) { c.key(Keys.DEL) }
                    sent = ""
                    value = TextFieldValue("")
                }
                RoundKey(Icons.AutoMirrored.Filled.KeyboardReturn, "Enter", settings.haptics, size = 52.dp, tint = RemoColors.Accent) {
                    c.key(Keys.ENTER)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(150)
        focus.requestFocus()
        keyboard?.show()
    }
}
