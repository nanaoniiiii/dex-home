package com.alkaidlab.dexlocal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val ink = Color.rgb(23, 49, 46)
    private val muted = Color.rgb(91, 113, 107)
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var rates: TextView
    private lateinit var connect: Button
    private lateinit var prepare: Button
    private lateinit var openDex: Button
    private lateinit var stopReceiver: Button
    private lateinit var preview: Button
    private lateinit var enable: Button
    private lateinit var experiment: Switch
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false
    private var resumed = false
    private var openWhenReady = false
    private val listener: () -> Unit = { update() }
    private val poll = object : Runnable { override fun run() {
        if (resumed) { DexConnection.poll(); handler.postDelayed(this, 1500) }
    } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float = 16f, color: Int = ink): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(4).toFloat(), 1f)
    }
    private fun LinearLayout.space(value: Int) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(value))) }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; textSize = 15f; minHeight = dp(50)
        setTextColor(if (primary) Color.WHITE else ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(20, 125, 112) else Color.rgb(225, 237, 231))
        setOnClickListener { action() }
    }
    private fun LinearLayout.card(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
            build()
        }, LinearLayout.LayoutParams(-1, -2)); space(14)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openWhenReady = state?.getBoolean("openWhenReady") ?: false
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(24)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
        page.apply {
            addView(label("DeX 主页", 31f).apply { typeface = Typeface.DEFAULT_BOLD })
            space(4); addView(label("全屏桌面 · 按 HOME 回到 DeX · 免 Root", 16f, muted)); space(22)
            card {
                title = label("连接权限服务", 20f).apply { typeface = Typeface.DEFAULT_BOLD }; addView(title)
                space(8); detail = label("", 14f, muted); addView(detail); space(12)
                connect = button("连接 Shizuku／Stellar") { DexConnection.connect() }; addView(connect)
                prepare = button("启动接收服务", true) { DexConnection.execute { it.startService() } }; addView(prepare)
                openDex = button("打开三星 DeX") { openWhenReady = true; DexConnection.execute { it.openDexPicker() } }; addView(openDex)
                preview = button("2  全屏预览") { showHome() }; addView(preview)
                enable = button("3  设为默认主页并进入", true) {
                    DexConnection.execute(onDone = {
                        if (DexConnection.status.optBoolean("home_selected")) { showHome(); finish() }
                    }) { it.enableHome() }
                }; addView(enable)
                space(8); addView(label("首次连接：在三星列表选择「DeX Home」，确认「立即开始」后返回。先预览，再替换主页。", 13f, muted))
            }
            card {
                addView(label("刷新率实验", 20f).apply { typeface = Typeface.DEFAULT_BOLD }); space(6)
                rates = label("连接后分别读取 DeX 与内屏刷新率", 15f, muted); addView(rates)
                experiment = Switch(this@MainActivity).apply {
                    text = "尝试 120Hz"; textSize = 16f; setTextColor(ink); setPadding(0, dp(12), 0, dp(12))
                    setOnCheckedChangeListener { _, selected -> if (!updating) DexConnection.execute { it.setHighRefresh(selected) } }
                }; addView(experiment)
                addView(label("分别核对桌面新画面 FPS 和内屏 Hz。退出时恢复实验设置；省电模式需要先关闭。", 13f, muted)); space(10)
            }
            addView(label("退出 DeX：打开任务栏最左侧的应用菜单，选择「退出 DeX」。系统退出后会自动恢复原手机主页与默认主页设置。", 14f, muted))
            space(6); addView(label("接收服务继续常驻，之后可从三星 DeX 列表再次选择 DeX Home，自动回到 DeX 主页。", 14f, muted))
            space(10)
            stopReceiver = button("停止接收服务") { DexConnection.execute { it.stopDex() } }; addView(stopReceiver)
            addView(button("系统默认主页设置") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
            addView(button("分享诊断信息") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, DexConnection.diagnosticText()), "分享 DeX 诊断"))
            })
            space(14); addView(label("v${BuildConfig.VERSION_NAME} · 三星原生 DeX 由本机第二屏提供，DeX 主页负责全屏显示和 HOME 行为。", 12f, muted))
        }
        DexConnection.addListener(listener)
        if (DexConnection.available()) DexConnection.connect()
    }
    private fun showHome(goToDesktop: Boolean = true) {
        val options = android.app.ActivityOptions.makeBasic().apply { launchDisplayId = 0 }
        startActivity(Intent(this, DexHostActivity::class.java).setAction(Intent.ACTION_MAIN)
            .putExtra("dex_home", goToDesktop).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), options.toBundle())
    }
    private fun update() {
        if (!::title.isInitialized) return
        val status = DexConnection.status
        val connected = DexConnection.service != null
        val active = status.optBoolean("active")
        val home = status.optBoolean("home_selected")
        val listening = status.optBoolean("listening")
        title.text = when { DexConnection.busy -> "正在准备…"; home && active -> "DeX 已作为默认主页"; active -> "DeX 就绪，可以全屏预览";
            listening && status.optBoolean("receiver_ready") -> "接收服务运行，等待 DeX 连接"; listening -> "接收服务运行，等待网络"; connected -> "启动接收服务"; else -> "连接权限服务" }
        detail.text = when {
            DexConnection.operationError.isNotBlank() -> DexConnection.operationError
            status.optString("error").isNotBlank() -> status.optString("error")
            status.optString("detail").isNotBlank() -> status.optString("detail")
            else -> DexConnection.message
        }
        connect.isEnabled = !connected && !DexConnection.binding
        prepare.isEnabled = connected && !listening && !DexConnection.busy
        openDex.isEnabled = connected && listening && status.optBoolean("receiver_ready") && !active && !DexConnection.busy
        stopReceiver.isEnabled = connected && listening && !active && !DexConnection.busy
        preview.isEnabled = active && !DexConnection.busy
        enable.isEnabled = active && status.optBoolean("previewed") && !home && !DexConnection.busy
        experiment.isEnabled = active && !DexConnection.busy
        listOf(connect, prepare, openDex, preview, enable, stopReceiver).forEach { it.alpha = if (it.isEnabled) 1f else .4f }
        updating = true; experiment.isChecked = status.optBoolean("high_refresh_requested"); updating = false
        rates.text = if (active) String.format(java.util.Locale.ROOT, "DeX 桌面 %.1f Hz  ·  手机内屏 %.1f Hz", status.optDouble("source_refresh_hz"), status.optDouble("panel_refresh_hz"))
            else "连接后分别读取 DeX 与内屏刷新率"
        if (resumed && openWhenReady && active) { openWhenReady = false; showHome() }
    }
    override fun onResume() { super.onResume(); resumed = true; handler.post(poll); update() }
    override fun onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("openWhenReady", openWhenReady); super.onSaveInstanceState(out) }
    override fun onDestroy() { DexConnection.removeListener(listener); super.onDestroy() }
}
