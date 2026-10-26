package com.alkaidlab.dexlocal

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import com.alkaidlab.dexlocal.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Runs under the Shizuku shell identity; contains no Sunshine engine or streaming server. */
@android.annotation.SuppressLint("WrongConstant")
class DexUserService(context: Context) : IDexService.Stub() {
    constructor() : this(systemContext())
    companion object {
        private fun systemContext(): Context {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("currentActivityThread").invoke(null) ?: type.getMethod("systemMain").invoke(null)
            return type.getMethod("getSystemContext").invoke(thread) as Context
        }
    }
    private val shell = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val displays = shell.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val instance = ServiceInstanceLease()
    private val settings = SettingsJournal()
    private val homes = HomeLease(shell)
    private val preferences = ListenerPreferences()
    private val lifecycleThread = HandlerThread("DeX-Home-Lifecycle").apply { start() }
    private val lifecycle = Handler(lifecycleThread.looper)
    private var receiver: SamsungLocalDex? = null
    private var canvas: DexCanvas? = null
    private var mirror: NativeDexMirror? = null
    private var owner: IBinder? = null
    private var surface: Surface? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var sourceId = -1
    private var sourceUniqueId = ""
    private var highRefresh = false
    private var sourcePreference: String? = null
    private var sourcePreferenceChanged = false
    private var sourceRequestReadback = ""
    private var error = ""
    private var detail = ""
    private var injected = 0L
    private var inputFailures = 0L
    private var touchGesture = false
    private var previewed = false
    private var ownerDiedAt = 0L
    private var homeRedirects = 0L
    private var nativeSessionSeen = false
    private var nativeExitHandled = 0L
    private var entryLaunched = false
    private var entryNeedsFocus = false
    private var lastReceiverAttempt = 0L
    private val death = IBinder.DeathRecipient {
        Thread {
            synchronized(this) {
                owner = null; ownerDiedAt = SystemClock.uptimeMillis()
                runCatching { hideDesktop() }
                // Keep the session for 30 seconds while Android recreates the
                // host, including the first preview before HOME is selected.
            }
        }.start()
    }
    private val monitor = object : Runnable {
        override fun run() {
            synchronized(this@DexUserService) {
                try {
                    if (homes.hasInstalled() && !homes.isSelected()) closeSession(preferences.enabled)
                    if (receiver != null && nativeSessionSeen && nativeExitRequested()) {
                        closeSession(preferences.enabled)
                        nativeExitHandled++
                        detail = "已恢复手机主页；接收服务继续运行，可从三星 DeX 再次连接"
                        android.util.Log.i("DEX_HOME_LIFECYCLE", "Native DeX exit restored the original HOME; count=$nativeExitHandled")
                        ShellCommand.run("am", "start", "--user", "0", "--display", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
                    }
                    if (owner == null && ownerDiedAt > 0 && SystemClock.uptimeMillis() - ownerDiedAt > 30000) {
                        closeSession(preferences.enabled)
                        ownerDiedAt = 0L
                        ShellCommand.run("am", "start", "--user", "0", "--display", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
                        if (!preferences.enabled) kotlin.system.exitProcess(0)
                    }
                    if (receiver?.failure != null && source() == null) {
                        val reason = receiver?.failure?.message
                        closeSession(preferences.enabled); error = "连接已结束，接收目标将继续待机：$reason"
                    }
                    if (preferences.enabled && receiver == null && SystemClock.uptimeMillis() - lastReceiverAttempt > 5000) {
                        lastReceiverAttempt = SystemClock.uptimeMillis()
                        ensureReceiver()
                    }
                    if (source() != null && preferences.automaticHome && !homes.isSelected() && !entryLaunched &&
                        !(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
                        homes.remember()
                        homes.install()
                        dismissPhoneRecents()
                        entryLaunched = true
                        entryNeedsFocus = true
                        ShellCommand.run("am", "start", "--user", "0", "--display", "0", "-a", "android.intent.action.MAIN", "-n",
                            "${BuildConfig.APPLICATION_ID}/.DexHostActivity", "--ez", "dex_home", "false")
                    }
                } catch (e: Exception) { error = "主页恢复尚未完成：${(e.cause ?: e).message}" }
            }
            lifecycle.postDelayed(this, if (preferences.enabled || receiver != null || homes.hasInstalled()) 1000 else 6000)
        }
    }
    init {
        if (homes.hasInstalled()) homes.restore()
        lifecycle.postDelayed(monitor, 1000)
    }
    private inline fun <T> privileged(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try { return block() } finally { Binder.restoreCallingIdentity(token) }
    }
    private fun dismissPhoneRecents() {
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val get = manager.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val tasks = (get.invoke(manager, 100, false, false) as List<*>).filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
        for (task in tasks) {
            if (android.app.TaskInfo::class.java.getField("displayId").getInt(task) == 0 &&
                task.baseActivity?.packageName == "com.sec.android.app.launcher" &&
                task.baseActivity?.className == "com.android.quickstep.RecentsActivity" &&
                task.topActivity?.className == "com.android.quickstep.RecentsActivity")
                manager.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(manager, task.taskId)
        }
    }
    @Synchronized override fun attachOwner(owner: IBinder) = privileged {
        if (this.owner != owner) {
            owner.linkToDeath(death, 0)
            this.owner?.unlinkToDeath(death, 0)
            this.owner = owner
            ownerDiedAt = 0L
        }
    }
    @Synchronized override fun startDex(): String = privileged {
        check(!(shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "请先解锁手机" }
        startService()
        if (source() == null) openDexPicker()
        snapshot()
    }
    @Synchronized override fun startService() = privileged {
        check(Build.MANUFACTURER.equals("samsung", true)) { "需要支持 DeX 的三星手机" }
        check(SamsungLocalDex.supported(shell)) { "此固件没有可用的 DeX for PC 组件" }
        preferences.enabled = true; preferences.save()
        ensureReceiver()
        detail = "接收服务运行中，在三星 DeX 列表中选择 DeX Home 即可连接"
    }
    private fun ensureReceiver() {
        if (receiver != null) return
        check(NativeDexMirror.activeDisplay(shell) == null) { "请先结束其他 DeX 会话，接收服务将自动重试" }
        check(!NativeDexMirror.hasExternalConnection(shell)) { "请先结束现有外屏或无线投屏" }
        check(SamsungLocalDex.supported(shell)) { "此固件没有可用的 DeX for PC 组件，当前手机路径不适用" }
        val wifi = DisplayManager::class.java.getMethod("getWifiDisplayStatus").invoke(displays)
        check(wifi.javaClass.getMethod("getActiveDisplayState").invoke(wifi) == 0) { "请先结束现有无线投屏" }
        settings.set("global", "force_desktop_mode_on_external_displays", "0")
        receiver = SamsungLocalDex(shell).also { it.start() }
        error = ""
    }
    @Synchronized override fun openDexPicker() = privileged {
        check(preferences.enabled) { "请先启动接收服务" }
        ensureReceiver()
        if (source() != null) return@privileged
        ShellCommand.run("am", "broadcast", "--user", "0", "-a",
            "com.samsung.android.desktopmode.action.DESKTOP_MODE_UPDATE_REQUEST", "--ei",
            "com.samsung.android.desktopmode.extra.DESKTOP_MODE_STATE", "1", "--ei",
            "com.samsung.android.desktopmode.extra.DESKTOP_MODE_SOURCE", "0")
        detail = "在三星 DeX 列表选择 DeX Home 并确认立即开始"
        Unit
    }
    private fun source(): Display? {
        val stage = receiver?.stage ?: return null
        if (stage == "advertising" || stage == "created" || stage == "closed" || stage.startsWith("advertise_failed")) return null
        return NativeDexMirror.activeDisplay(shell)?.takeIf { it.displayId > 0 }?.also { nativeSessionSeen = true }
    }
    private fun nativeExitRequested(): Boolean = try {
        val manager = shell.getSystemService("desktopmode") ?: error("缺少三星桌面服务")
        val value = manager.javaClass.getMethod("getDesktopModeState").invoke(manager)
        val enabled = value.javaClass.getMethod("getEnabled").invoke(value) as Int
        val type = value.javaClass.getMethod("getDisplayType").invoke(value) as Int
        enabled == 1 && type == 102 || enabled == 2 && type == 0
    } catch (_: Exception) { false }
    private fun identity(display: Display): String = Display::class.java.getMethod("getUniqueId").invoke(display) as String
    @Suppress("DEPRECATION") private fun size(display: Display): DisplayMetrics = DisplayMetrics().also { display.getRealMetrics(it) }
    @Synchronized override fun showDesktop(surface: Surface, width: Int, height: Int) = privileged {
        check(owner?.isBinderAlive == true) { "应用已退出" }
        require(width in 200..8192 && height in 200..8192 && surface.isValid)
        var source = source() ?: error("请先在三星界面确认 DeX 启动")
        sourceId = source.displayId; sourceUniqueId = identity(source)
        hideDesktop()
        if (receiver != null) {
            val resize = canvas ?: DexCanvas(displays, source.displayId).also { canvas = it }
            resize.apply(width, height)
            source = checkNotNull(source())
        }
        this.surface = surface
        viewWidth = width; viewHeight = height
        try { mirror = NativeDexMirror.create(source, width, height, surface) }
        catch (e: Exception) { hideDesktop(); throw e }
        previewed = true
        if (preferences.automaticHome && entryLaunched && !homes.isSelected()) homes.install()
        detail = "全屏 DeX 主页，触控发送至原生 DeX 显示 ${source.displayId}"
    }
    @Synchronized override fun hideDesktop() = privileged {
        try { mirror?.close() } finally {
            mirror = null
            surface?.release(); surface = null
            viewWidth = 0; viewHeight = 0
            touchGesture = false
        }
        Unit
    }
    @Synchronized override fun setHighRefresh(enabled: Boolean) = privileged {
        if (enabled == highRefresh) return@privileged
        if (!enabled) {
            restoreSourcePreference()
            settings.restore(highRefreshOnly = true)
            highRefresh = false
            detail = "已恢复高刷实验修改；源桌面和手机内屏帧率分别测量"
            return@privileged
        }
        val source = source() ?: error("请先开启 DeX")
        check(!(shell.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode) { "请先在系统关闭省电模式，再尝试高刷" }
        val primary = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: error("缺少手机内屏")
        val rawModes = try {
            val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = type.getMethod("getInstance").invoke(null)
            val info = type.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, Display.DEFAULT_DISPLAY)
            (info.javaClass.getField("supportedModes").get(info) as Array<*>).filterIsInstance<Display.Mode>()
        } catch (_: Exception) { primary.supportedModes.toList() }
        val highest = rawModes.maxOf { it.refreshRate }.coerceAtMost(120f)
        check(highest > 60.5f) { "内屏未公布高于 60Hz 的模式" }
        try {
            settings.set("secure", "refresh_rate_mode", "1")
            settings.set("system", "peak_refresh_rate", highest.toString())
            settings.set("system", "min_refresh_rate", highest.toString())
            if (receiver != null) {
                sourceId = source.displayId; sourceUniqueId = identity(source)
                sourcePreference = ShellCommand.run("cmd", "display", "get-user-preferred-display-mode", sourceId.toString())
                sourcePreferenceChanged = true
                val metrics = size(source)
                ShellCommand.run("cmd", "display", "set-user-preferred-display-mode", metrics.widthPixels.toString(),
                    metrics.heightPixels.toString(), highest.toString(), sourceId.toString())
                sourceRequestReadback = ShellCommand.run("cmd", "display", "get-user-preferred-display-mode", sourceId.toString())
            }
            highRefresh = true
            detail = "已请求 ${highest.toInt()}Hz；请运行帧率验证，只有桌面新画面超过 60fps 才算解锁"
        } catch (e: Exception) {
            try { restoreSourcePreference() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { settings.restore(highRefreshOnly = true) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }
    private fun restoreSourcePreference() {
        if (!sourcePreferenceChanged) return
        val current = displays.getDisplay(sourceId)
        if (current != null && identity(current) == sourceUniqueId) {
            val values = sourcePreference.orEmpty().substringAfter(":", "").trim().split(Regex("\\s+"))
            if (values.size == 3 && (values[0].toIntOrNull() ?: -1) > 0 && (values[1].toIntOrNull() ?: -1) > 0 &&
                (values[2].toFloatOrNull() ?: 0f) > 0f)
                ShellCommand.run("cmd", "display", "set-user-preferred-display-mode", values[0], values[1], values[2], sourceId.toString())
            else ShellCommand.run("cmd", "display", "clear-user-preferred-display-mode", sourceId.toString())
        }
        sourcePreferenceChanged = false; sourcePreference = null
    }
    private fun inject(event: InputEvent): Boolean {
        if ((shell.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return false
        val current = source() ?: return false
        if (current.displayId != sourceId || identity(current) != sourceUniqueId || mirror == null) return false
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, sourceId)
        val manager = shell.getSystemService(Context.INPUT_SERVICE)
        return manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, event, 0) == true
    }
    @Synchronized override fun touch(event: MotionEvent) = privileged {
        try {
            val source = source() ?: return@privileged
            if (viewWidth <= 0 || viewHeight <= 0) return@privileged
            val metrics = size(source)
            val viewport = CaptureGeometry.fit(metrics.widthPixels, metrics.heightPixels, viewWidth, viewHeight)
            // Ignore a gesture beginning in a black bar. Never turn it into an edge click.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchGesture =
                event.x >= viewport.x && event.x < viewport.x + viewport.width &&
                    event.y >= viewport.y && event.y < viewport.y + viewport.height
            if (!touchGesture) return@privileged
            val properties = Array(event.pointerCount) { MotionEvent.PointerProperties().also { p -> event.getPointerProperties(it, p) } }
            val coords = Array(event.pointerCount) { MotionEvent.PointerCoords().also { p ->
                event.getPointerCoords(it, p)
                p.x = ((p.x - viewport.x) * metrics.widthPixels / viewport.width).coerceIn(0f, metrics.widthPixels - 1f)
                p.y = ((p.y - viewport.y) * metrics.heightPixels / viewport.height).coerceIn(0f, metrics.heightPixels - 1f)
            } }
            val mapped = MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount, properties, coords,
                event.metaState, event.buttonState, 1f, 1f, event.deviceId, event.edgeFlags, event.source, event.flags)
            try { if (inject(mapped)) injected++ else inputFailures++ } finally { mapped.recycle() }
        } catch (e: Exception) { inputFailures++; error = "触控注入失败：${(e.cause ?: e).message}" }
        finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchGesture = false
            event.recycle()
        }
    }
    @Synchronized override fun key(keyCode: Int, action: Int, metaState: Int): Boolean = privileged {
        val time = SystemClock.uptimeMillis()
        inject(KeyEvent(time, time, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }
    @Synchronized override fun enableHome() = privileged {
        check(source() != null && previewed) { "先完成全屏预览，再切换默认主页" }
        try { homes.install() } catch (e: Exception) {
            runCatching { homes.restore() }; throw e
        }
        detail = "DeX 已设为默认主页；按 HOME 返回 DeX 桌面"
        preferences.automaticHome = true; preferences.save(); entryLaunched = true
        entryNeedsFocus = true
    }
    @Synchronized override fun showDexHome() = privileged {
        val display = source() ?: return@privileged
        check(receiver != null && display.displayId > 0) { "只允许返回本服务的 DeX 桌面" }
        // Focusing the HOME task leaves Samsung's freeform windows visible.
        // Use the native show-desktop operation on the owned DeX display only.
        val multiWindow = Class.forName("com.samsung.android.multiwindow.MultiWindowManager")
        multiWindow.getMethod("minimizeAllTasks", Int::class.javaPrimitiveType)
            .invoke(multiWindow.getConstructor().newInstance(), display.displayId)
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val getTasks = manager.javaClass.methods.first { it.name == "getTasks" && it.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) }
        val tasks = (getTasks.invoke(manager, 100, false, false) as List<*>).filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
        val info = android.app.TaskInfo::class.java
        val home = tasks.firstOrNull { task ->
            if (info.getField("displayId").getInt(task) != display.displayId ||
                task.baseActivity?.packageName != "com.sec.android.app.desktoplauncher") false else {
                val config = info.getField("configuration").get(task) as android.content.res.Configuration
                val window = android.content.res.Configuration::class.java.getField("windowConfiguration").get(config)
                window.javaClass.getMethod("getActivityType").invoke(window) == 2
            }
        }
        if (home != null) ShellCommand.run("am", "task", "focus", home.taskId.toString())
        else ShellCommand.run("am", "start", "--user", "0", "--display", display.displayId.toString(), "--activityType", "2", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME", "-n",
            "com.sec.android.app.desktoplauncher/com.android.launcher3.Launcher")
        homeRedirects++
        Unit
    }
    @Synchronized override fun confirmHomeVisible(taskId: Int) = privileged {
        if (!entryNeedsFocus || !homes.isSelected() || source() == null) return@privileged
        makeFullscreen(taskId) // Includes exact package and physical-display ownership checks.
        ShellCommand.run("am", "task", "focus", taskId.toString())
        entryNeedsFocus = false
        Unit
    }
    @Synchronized override fun makeFullscreen(taskId: Int) = privileged {
        // Samsung can retain the phone task as a popup after DeX consent.
        // Change only this application's own task on the physical phone display.
        val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val getTasks = manager.javaClass.methods.first { it.name == "getTasks" && it.parameterTypes.contentEquals(
            arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) }
        val tasks = getTasks.invoke(manager, 100, false, false) as List<*>
        val task = tasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>().firstOrNull { it.taskId == taskId }
            ?: error("找不到本机显示窗口")
        val taskInfo = android.app.TaskInfo::class.java
        check(taskInfo.getField("displayId").getInt(task) == Display.DEFAULT_DISPLAY && task.baseActivity?.packageName == BuildConfig.APPLICATION_ID) {
            "只允许调整本应用在手机内屏的窗口"
        }
        val configuration = taskInfo.getField("configuration").get(task) as android.content.res.Configuration
        val config = android.content.res.Configuration::class.java.getField("windowConfiguration").get(configuration)
        if (config.javaClass.getMethod("getWindowingMode").invoke(config) == 1) return@privileged
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val token = taskInfo.getField("token").get(task)
        val type = Class.forName("android.window.WindowContainerTransaction")
        val transaction = type.getConstructor().newInstance()
        type.getMethod("setWindowingMode", tokenType, Int::class.javaPrimitiveType).invoke(transaction, token, 1)
        type.getMethod("setBounds", tokenType, android.graphics.Rect::class.java).invoke(transaction, token, null)
        val organizer = Class.forName("android.window.WindowOrganizer")
        organizer.getMethod("applyTransaction", type).invoke(organizer.getConstructor().newInstance(), transaction)
        Unit
    }
    @Synchronized override fun snapshot(): String = privileged {
        val source = source()
        if (mirror == null) source?.let { sourceId = it.displayId; sourceUniqueId = identity(it) }
        val primary = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = source?.let { size(it) }
        JSONObject().put("model", Build.MODEL).put("android", Build.VERSION.RELEASE).put("uid", Process.myUid())
            .put("mode", "dex_home").put("home_selected", homes.isSelected()).put("previous_home", homes.previous())
            .put("previewed", previewed).put("home_redirects", homeRedirects)
            .put("native_exit_handled", nativeExitHandled)
            .put("listening", preferences.enabled).put("receiver_ready", receiver != null)
            .put("automatic_home", preferences.automaticHome)
            .put("home_entry_pending", entryNeedsFocus)
            .put("build", BuildConfig.VERSION_NAME).put("active", source != null).put("owned", receiver != null)
            .put("display_id", source?.displayId ?: -1).put("width", metrics?.widthPixels ?: 0).put("height", metrics?.heightPixels ?: 0)
            .put("source_refresh_hz", source?.refreshRate ?: 0f).put("panel_refresh_hz", primary?.refreshRate ?: 0f)
            .put("source_modes_hz", JSONArray(source?.supportedModes?.map { it.refreshRate } ?: emptyList<Float>()))
            .put("mirror_active", mirror != null).put("high_refresh_requested", highRefresh)
            .put("source_mode_request_readback", sourceRequestReadback)
            .put("injected_events", injected).put("input_failures", inputFailures).put("pending_settings", settings.pending())
            .put("stage", receiver?.stage ?: "idle").put("error", error.ifEmpty { receiver?.failure?.message.orEmpty() })
            .put("detail", detail).toString()
    }
    @Synchronized override fun stopDex() = privileged {
        preferences.enabled = false; preferences.save()
        closeSession(false)
    }
    private fun closeSession(keepListening: Boolean) {
        val failures = mutableListOf<Exception>()
        fun attempt(operation: () -> Unit) { try { operation() } catch (e: Exception) { failures += e } }
        attempt { homes.restore() }
        attempt { hideDesktop() }
        attempt { restoreSourcePreference() }
        attempt { canvas?.close() }; canvas = null
        attempt { receiver?.close() }
        receiver = null
        attempt { settings.restore(highRefreshOnly = keepListening) }
        highRefresh = false
        sourceId = -1; sourceUniqueId = ""
        previewed = false
        nativeSessionSeen = false
        entryLaunched = false
        entryNeedsFocus = false
        detail = "DeX 已退出，原主页与实验设置已恢复"
        error = failures.joinToString("；") { (it.cause ?: it).message.orEmpty() }
        check(failures.isEmpty()) { "清理尚未全部完成：$error；请重新连接后点退出并恢复" }
    }
    @Synchronized override fun destroy() {
        try { closeSession(false) } finally { instance.close(); lifecycleThread.quitSafely(); kotlin.system.exitProcess(0) }
    }
}
