package co.jaspero.remo

import android.app.Application

class RemoApp : Application() {
    lateinit var controller: TvController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = TvController(this)
    }
}
