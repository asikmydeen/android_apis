package dev.asik.devicebridge

import android.app.Application

class DeviceBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeRuntime.init(this)
    }
}
