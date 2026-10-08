package com.v2picker.app

import android.app.Application

class App : Application() {
    override fun onCreate() { super.onCreate(); Core.init(this) }
}
