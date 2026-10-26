package com.alkaidlab.dexlocal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

/** Android HOME for the physical phone; its contents are the real Samsung DeX display. */
class DexHostActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var screen: SurfaceView
    private lateinit var fallback: LinearLayout
    private lateinit var hint: TextView
    private var resumed = false
    private var attaching = false
    private var attached = false
    private var showNativeHome = false
    private var wasActive = false
    private var prepareWhenConnected = false
    private var lastAttach = 0L
    private var appliedRate = Float.NaN
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { update() }
    private val poll = object : Runnable { override fun run() {
        if (resumed) { DexConnection.poll(); handler.postDelayed(this, 1800) }
    } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        showNativeHome = intent.getBooleanExtra("dex_home", true)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        screen = SurfaceView(this).apply {
            visibility = View.INVISIBLE
            holder.addCallback(this@DexHostActivity)
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                runCatching { DexConnection.service?.touch(event) }.isSuccess
            }
        }
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        fallback = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(20), dp(32), dp(20))
            hint = TextView(this@DexHostActivity).apply { textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }; addView(hint)
            addView(Button(this@DexHostActivity).apply { text = "连接并准备 DeX"; isAllCaps = false; setOnClickListener {
                prepareWhenConnected = true
                if (DexConnection.service == null) DexConnection.connect() else update()
            } })
            addView(Button(this@DexHostActivity).apply { text = "管理与恢复主页"; isAllCaps = false; setOnClickListener { openSettings() } })
            addView(Button(this@DexHostActivity).apply { text = "系统默认主页设置"; isAllCaps = false; setOnClickListener {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } })
        }
        root.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.decorView.post { immersive() }
        DexConnection.addListener(listener)
        if (DexConnection.available()) DexConnection.connect()
    }
    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    @Suppress("DEPRECATION") private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun update() {
        if (!::hint.isInitialized) return
        val status = DexConnection.status
        val active = status.optBoolean("active") && DexConnection.service != null
        fallback.visibility = if (active) View.GONE else View.VISIBLE
        screen.visibility = if (active) View.VISIBLE else View.INVISIBLE
        hint.text = status.optString("error").ifBlank { DexConnection.operationError.ifBlank { DexConnection.message } }
        if (prepareWhenConnected && DexConnection.service != null && !DexConnection.busy) {
            prepareWhenConnected = false
            DexConnection.execute { it.startDex() }
        }
        if (active) {
            wasActive = true
            if (showNativeHome && !DexConnection.busy) {
                showNativeHome = false
                DexConnection.execute { it.showDexHome() }
            }
            attachIfReady()
            val rate = if (status.optBoolean("high_refresh_requested")) 120f else 0f
            if (rate != appliedRate) {
                if (window.attributes.preferredRefreshRate != rate) window.attributes = window.attributes.apply { preferredRefreshRate = rate }
                if (Build.VERSION.SDK_INT >= 30 && screen.holder.surface.isValid)
                    runCatching { screen.holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                appliedRate = rate
            }
        } else if (wasActive && !status.optBoolean("home_selected") && DexConnection.service != null) {
            wasActive = false
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
        }
    }
    private fun attachIfReady() {
        if (attached || attaching || !screen.holder.surface.isValid || screen.width < 200 || screen.height < 200 ||
            SystemClock.uptimeMillis() - lastAttach < 1500) return
        attaching = true; lastAttach = SystemClock.uptimeMillis()
        val surface = screen.holder.surface
        val width = screen.width; val height = screen.height
        DexConnection.execute(onDone = {
            attaching = false; attached = DexConnection.status.optBoolean("mirror_active") && DexConnection.operationError.isBlank()
            if (attached) {
                reportFullyDrawn()
                if (DexConnection.status.optBoolean("home_entry_pending")) handler.postDelayed({
                    DexConnection.execute { it.confirmHomeVisible(taskId) }
                }, 250)
            }
        }) { if (surface.isValid) it.showDesktop(surface, width, height) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) { showNativeHome = intent.getBooleanExtra("dex_home", true); update() }
    }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        attached = false; lastAttach = 0; appliedRate = Float.NaN; attachIfReady()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attached = false
        if (DexConnection.service != null) DexConnection.execute { it.hideDesktop() }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER)) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP) { showNativeHome = true; update() }; return true
        }
        return runCatching { DexConnection.service?.key(event.keyCode, event.action, event.metaState) == true }.getOrDefault(false)
    }
    override fun onResume() {
        super.onResume(); resumed = true; immersive(); handler.post(poll)
        if (DexConnection.service != null) DexConnection.execute { it.makeFullscreen(taskId) }
        update()
    }
    override fun onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onDestroy() { DexConnection.removeListener(listener); super.onDestroy() }
}
