package com.alkaidlab.dexlocal.core

import android.system.Os
import org.json.JSONObject
import java.io.File

internal class ListenerPreferences {
    private val file = File("/data/local/tmp/dexlocal-listener-v1.json")
    private val saved = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    var enabled = saved.optBoolean("enabled")
    var automaticHome = saved.optBoolean("automatic_home")
    fun save() {
        val pending = File(file.path + ".pending")
        pending.outputStream().use { out ->
            out.write(JSONObject().put("enabled", enabled).put("automatic_home", automaticHome).toString().toByteArray())
            out.fd.sync()
        }
        Os.chmod(pending.path, 384)
        Os.rename(pending.path, file.path)
    }
}
