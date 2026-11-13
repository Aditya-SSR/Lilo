package com.lilo.app

import android.app.Application

class LiloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GuardHub.init(this)
    }
}
