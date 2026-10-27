package com.chaoxing.nativeapp

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        StudyWorker.ensureChannel(this)
    }
}
