package com.alkaidlab.dexlocal.core

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.system.Os
import com.alkaidlab.dexlocal.BuildConfig
import org.json.JSONObject
import java.io.File

/** A saved user-0 HOME selection; do not overwrite a different later user choice. */
internal class HomeLease(private val context: Context) {
    private val file = File("/data/local/tmp/dexlocal-home-v1.json")
    private val ownPackage = BuildConfig.APPLICATION_ID
    private val ownComponent = ComponentName(ownPackage, "$ownPackage.HomeEntryActivity")
    private var before = if (file.exists()) JSONObject(file.readText()).getString("before") else ""
    private var installed = file.exists() && JSONObject(file.readText()).optBoolean("installed")
    private fun save() {
        val pending = File(file.path + ".pending")
        pending.outputStream().use { out -> out.write(JSONObject().put("before", before).put("installed", installed).toString().toByteArray()); out.fd.sync() }
        Os.chmod(pending.path, 384)
        Os.rename(pending.path, file.path)
    }
    fun currentPackage(): String = try {
        val roles = context.getSystemService(Context.ROLE_SERVICE)
        val holders = roles.javaClass.getMethod("getRoleHoldersAsUser", String::class.java, UserHandle::class.java)
            .invoke(roles, RoleManager.ROLE_HOME, UserHandle.getUserHandleForUid(0)) as List<*>
        holders.firstOrNull() as? String ?: ""
    } catch (_: Exception) {
        ShellCommand.run("cmd", "role", "get-role-holders", "--user", "0", RoleManager.ROLE_HOME).lineSequence().firstOrNull().orEmpty().trim()
    }
    fun isSelected(): Boolean = currentPackage() == ownPackage
    fun previous(): String = before
    fun hasInstalled(): Boolean = installed
    fun remember() {
        if (before.isNotBlank()) return
        check(!isSelected()) { "缺少原主页记录，请先在系统设置选择手机主页，再重新启用" }
        val output = ShellCommand.run("cmd", "package", "resolve-activity", "--user", "0", "--brief", "-a",
            Intent.ACTION_MAIN, "-c", Intent.CATEGORY_HOME)
        val component = output.lineSequence().mapNotNull { ComponentName.unflattenFromString(it.trim()) }.lastOrNull()
            ?: error("无法确认当前主页，保留原设置")
        check(component.packageName != ownPackage) { "请先选择原手机主页" }
        before = component.flattenToString()
        save()
    }
    fun install() {
        remember()
        installed = true; save() // Recovery exists before mutating the role.
        ShellCommand.run("cmd", "package", "set-home-activity", "--user", "0", ownComponent.flattenToString())
        check(isSelected()) { "系统未切换默认主页，请在系统默认主页设置选择 DeX 主页" }
    }
    fun restore() {
        if (before.isBlank()) return
        val old = ComponentName.unflattenFromString(before) ?: error("原主页记录异常")
        if (HomeRestorePolicy.shouldRestore(currentPackage(), ownPackage, old.packageName)) {
            ShellCommand.run("cmd", "package", "set-home-activity", "--user", "0", old.flattenToString())
            check(currentPackage() == old.packageName) { "原主页尚未恢复，请在系统主页设置手动选择" }
        }
        before = ""; installed = false
        check(!file.exists() || file.delete()) { "无法清理已恢复的主页记录" }
    }
}
