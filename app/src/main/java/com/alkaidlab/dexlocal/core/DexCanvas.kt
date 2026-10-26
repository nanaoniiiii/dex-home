package com.alkaidlab.dexlocal.core

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Match only this session's external DeX canvas to the full HOME surface. */
internal class DexCanvas(private val displays: DisplayManager, private val id: Int) : AutoCloseable {
    init { require(id > 0) }
    private val unique = identity(checkNotNull(displays.getDisplay(id)))
    private var before: String? = null
    private var saved = false
    private fun identity(display: Display): String = Display::class.java.getMethod("getUniqueId").invoke(display) as String
    private fun current(): Display = checkNotNull(displays.getDisplay(id)).also {
        check(identity(it) == unique) { "DeX 显示器已改变" }
    }
    @Suppress("DEPRECATION") private fun size(): Pair<Int, Int> = DisplayMetrics().also { current().getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    fun apply(width: Int, height: Int) {
        require(width in 240..8192 && height in 240..8192)
        if (size() == width to height) return
        if (!saved) {
            val snapshot = ShellCommand.run("wm", "size", "-d", id.toString())
            check(snapshot.contains("Physical size:")) { "无法读取 DeX 原尺寸" }
            before = Regex("Override size: (\\d+x\\d+)").find(snapshot)?.groupValues?.get(1)
            saved = true
        }
        try {
            repeat(2) {
                val rotated = current().rotation % 2 == 1
                val value = if (rotated) "${height}x$width" else "${width}x$height"
                val result = ShellCommand.run("wm", "size", value, "-d", id.toString())
                check(!result.contains("Error", true) && !result.contains("Exception")) { result }
                repeat(25) { if (size() == width to height) return; Thread.sleep(80) }
            }
            error("系统未应用全屏 DeX 尺寸 ${width}x$height")
        } catch (e: Exception) { runCatching { close() }; throw e }
    }
    override fun close() {
        if (!saved) return
        val display = displays.getDisplay(id)
        if (display != null && identity(display) == unique)
            ShellCommand.run("wm", "size", before ?: "reset", "-d", id.toString())
        saved = false
    }
}
