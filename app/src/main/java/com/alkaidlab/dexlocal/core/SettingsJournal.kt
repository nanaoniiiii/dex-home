package com.alkaidlab.dexlocal.core

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Write recovery information before changing a setting, including for interrupted experiments. */
internal class SettingsJournal {
    private val file = File("/data/local/tmp/dexlocal-settings-v1.json")
    private val leases = mutableListOf<SettingLease>()
    init {
        if (file.exists()) {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                leases += SettingLease(item.getString("namespace"), item.getString("key"),
                    if (item.isNull("before")) null else item.getString("before"), item.getString("applied"))
            }
            restore()
        }
    }
    private fun get(namespace: String, key: String): String? = ShellCommand.run(
        "settings", "--user", "0", "get", namespace, key).takeUnless { it == "null" }
    private fun put(namespace: String, key: String, value: String?) {
        if (value == null) ShellCommand.run("settings", "--user", "0", "delete", namespace, key)
        else ShellCommand.run("settings", "--user", "0", "put", namespace, key, value)
    }
    private fun save() {
        val items = JSONArray()
        leases.forEach { items.put(JSONObject().put("namespace", it.namespace).put("key", it.key)
            .put("before", it.before ?: JSONObject.NULL).put("applied", it.applied)) }
        val pending = File(file.path + ".pending")
        pending.outputStream().use { out -> out.write(items.toString().toByteArray()); out.fd.sync() }
        Os.chmod(pending.path, 384) // 0600: only the shell-owned user service may read recovery state.
        Os.rename(pending.path, file.path)
    }
    fun set(namespace: String, key: String, value: String) {
        check(namespace in setOf("global", "secure", "system"))
        if (leases.none { it.namespace == namespace && it.key == key }) {
            leases += SettingLease(namespace, key, get(namespace, key), value)
            save()
        }
        put(namespace, key, value)
    }
    fun restore(highRefreshOnly: Boolean = false) {
        val errors = mutableListOf<Throwable>()
        leases.toList().asReversed().forEach { lease ->
            if (highRefreshOnly && lease.namespace == "global") return@forEach
            try {
                lease.restore({ get(lease.namespace, lease.key) }, { put(lease.namespace, lease.key, it) })
                leases.remove(lease)
            } catch (error: Exception) { errors += error }
        }
        save()
        check(errors.isEmpty()) { "部分显示设置尚未恢复：${errors.joinToString { it.message.orEmpty() }}" }
    }
    fun pending(): Int = leases.size
}
