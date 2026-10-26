package com.alkaidlab.dexlocal

import android.app.Application

class DexApplication : Application() {
    override fun onCreate() { super.onCreate(); DexConnection.initialize(this) }
}
