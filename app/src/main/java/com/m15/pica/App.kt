package com.m15.pica

import android.app.Application
import com.m15.pica.di.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

