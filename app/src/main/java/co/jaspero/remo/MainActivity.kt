package co.jaspero.remo

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.jaspero.remo.tv.Keys
import co.jaspero.remo.ui.RemoRoot
import co.jaspero.remo.ui.RemoTheme

class MainActivity : ComponentActivity() {
    private val controller get() = (application as RemoApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { RemoTheme { RemoRoot(controller, (application as RemoApp).installer) } }
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    override fun onStop() {
        super.onStop()
        controller.onBackground()
    }

    /** The phone's volume buttons turn the TV up and down while the remote is open. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            controller.settings.value.volumeKeys &&
            controller.conn.value == TvController.Conn.Connected
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                controller.key(if (code == KeyEvent.KEYCODE_VOLUME_UP) Keys.VOLUME_UP else Keys.VOLUME_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
