package com.alkaidlab.dexlocal

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@android.annotation.SuppressLint("StaticFieldLeak") // Always stores the application context.
internal object DexConnection {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "DexLocal-client").apply { isDaemon = true } }
    private val owner = Binder()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val polling = AtomicBoolean(false)
    private lateinit var args: Shizuku.UserServiceArgs
    @Volatile var service: IDexService? = null; private set
    @Volatile var status = JSONObject(); private set
    @Volatile var message = "启动 Shizuku 或 Stellar 后，点击连接权限服务"; private set
    @Volatile var operationError = ""; private set
    @Volatile var busy = false; private set
    var binding = false; private set
    private var retry = 0
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binder.pingBinder()) { disconnected(); return }
            val remote = IDexService.Stub.asInterface(binder)
            service = remote; binding = false; retry = 0
            try { binder.linkToDeath({ disconnected() }, 0) } catch (_: Exception) { disconnected(); return }
            execute { it.attachOwner(owner); message = "权限服务已连接" }
        }
        override fun onServiceDisconnected(name: ComponentName) { disconnected() }
    }
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DexUserService::class.java.name))
            .daemon(true).processNameSuffix("dexhome").tag("dexlocal-home-v3").version((version xor (version ushr 32)).toInt())
        Shizuku.addBinderReceivedListenerSticky { main.post { if (available()) connect(); notifyState() } }
        Shizuku.addBinderDeadListener { disconnected() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            main.post {
                if (result == PackageManager.PERMISSION_GRANTED) connect() else {
                    message = "尚未获得 Shizuku 授权，请在管理器允许 DeX Local"; notifyState()
                }
            }
        }
    }
    fun available(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun connect() {
        if (service != null || binding) return
        try {
            check(Shizuku.pingBinder()) { "请先启动 Shizuku 或 Stellar，再返回连接" }
            if (!available()) { Shizuku.requestPermission(1); return }
            binding = true; message = "正在连接本机 DeX 权限服务…"; notifyState()
            Shizuku.bindUserService(args, connection)
            main.postDelayed({
                if (binding && service == null) {
                    binding = false
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    message = "服务连接超时。请打开当前 Shizuku／Stellar 管理器后返回重试，无需清除授权"
                    notifyState()
                    if (retry++ == 0 && available()) main.postDelayed({ connect() }, 500)
                }
            }, 12000)
        } catch (e: Exception) { binding = false; message = describe(e); notifyState() }
    }
    private fun disconnected() {
        service = null
        main.post {
            binding = false; status = JSONObject(); message = "正在重新连接接收服务…"; notifyState()
            if (available()) main.postDelayed({ connect() }, 400)
        }
    }
    fun addListener(listener: () -> Unit) { listeners += listener; listener() }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyState() { listeners.forEach { it() } }
    private fun describe(error: Exception): String = (error.cause ?: error).message ?: error.javaClass.simpleName
    fun execute(onDone: (() -> Unit)? = null, action: (IDexService) -> Unit) {
        busy = true; main.post { notifyState() }
        worker.execute {
            val remote = service
            try {
                operationError = ""
                check(remote != null) { "请先连接权限服务" }
                action(remote)
                status = JSONObject(remote.snapshot())
                saveDiagnostic()
            } catch (e: Exception) { operationError = describe(e); message = operationError }
            main.post { busy = false; notifyState(); onDone?.invoke() }
        }
    }
    fun poll() {
        if (service == null || !polling.compareAndSet(false, true)) return
        worker.execute {
            try { service?.let { status = JSONObject(it.snapshot()); saveDiagnostic() } }
            catch (e: Exception) { message = describe(e) }
            finally { polling.set(false); main.post { notifyState() } }
        }
    }
    fun diagnosticText(): String {
        val sample = context.getSharedPreferences("cadence", Context.MODE_PRIVATE).getString("sample", "{}") ?: "{}"
        val parsed = JSONObject(sample)
        val recentNativeProbe = parsed.optInt("display_id", -1) == status.optInt("display_id", -2) &&
            SystemClock.elapsedRealtime() - parsed.optLong("elapsed_ms", 0) in 0..15000 && status.optBoolean("active")
        return JSONObject().put("captured_elapsed_ms", SystemClock.elapsedRealtime()).put("session", status)
            .put("last_probe", if (recentNativeProbe) parsed else JSONObject()).put("message", message).toString(2)
    }
    private fun saveDiagnostic() {
        runCatching { java.io.File(context.getExternalFilesDir(null), "diagnostics.json").writeText(diagnosticText()) }
    }
}
