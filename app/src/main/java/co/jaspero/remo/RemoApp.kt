package co.jaspero.remo

import android.app.Application

class RemoApp : Application() {
    lateinit var controller: TvController
        private set
    lateinit var installer: AppInstaller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = TvController(this)
        installer = AppInstaller(this, controller)
        controller.adbLauncher = installer::launch
    }
}
