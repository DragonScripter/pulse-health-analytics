package com.jma.pulsehealthanalytics

import android.app.Application
class PulseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}