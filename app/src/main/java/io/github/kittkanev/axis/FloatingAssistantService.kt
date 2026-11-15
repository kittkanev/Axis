package io.github.kittkanev.axis

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

class FloatingAssistantService : Service(), SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windows: WindowManager? = null
    private val app get() = application as AxisApplication
    private lateinit var controller: AssistantController
    private var view: ComposeView? = null
    private lateinit var layout: WindowManager.LayoutParams
    private var micForeground = false
    private var wakeArmed = false
    private var externalDictation = false
    private lateinit var wake: WakeWordListener
    private var dragPosition: Offset? = null
    private var dragAnchor: OverlayAnchor? = null
    private var screenOff = false
    private var showRequest by mutableIntStateOf(0)
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                screenOff = true
                refreshWake()
                controller.hide()
                setEditorFocus(false)
                showRequest = 0
                view?.visibility = android.view.View.GONE
            } else if (screenOff && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                screenOff = false
                view?.visibility = android.view.View.VISIBLE
                controller.show(autoListen = false, playSound = false)
                refreshWake()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        controller = AssistantController(this, app, { askMicrophone ->
            if (askMicrophone) openMicrophone()
            else openActivity(mainIntent(this))
        }, ::stopSelf, keepOpen = true, beforeRecording = { wake.update(null) })
        wake = WakeWordListener(this, detected = { command ->
            if (!screenOff && wakeArmed && app.preferences.state.value.wakeWord) {
                showRequest++
                controller.activateFromWake(command)
                refreshWake()
            }
        }, state = { wakeState.value = it })
        ContextCompat.registerReceiver(this, screenState, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(app.preferences.state, DeviceControlService.isConnected) { prefs, _ -> prefs }
                .collect { prefs ->
                    if (view != null && !ensureWindow(prefs)) {
                        app.workspace.report("Плавающая панель недоступна. Проверьте разрешения Axis.")
                        stopSelf()
                    }
                    if (!prefs.wakeWord) wakeArmed = false
                    refreshWake()
                }
        }
        scope.launch {
            combine(controller.state, controller.voice.speaking) { _, _ -> Unit }.collect { refreshWake() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !canStart(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val requestWake = intent?.action == ACTION_WAKE && app.preferences.state.value.wakeWord
        try {
            foreground(micForeground || requestWake || intent?.action == ACTION_MIC)
            if (requestWake) {
                wake.update(null)
                wakeArmed = true
            }
        } catch (_: RuntimeException) {
            if (requestWake) wakeState.value = WakeStatus.UNAVAILABLE
            if (view == null) stopSelf()
            else controller.report("Android не разрешил включить микрофон.")
            return START_NOT_STICKY
        }
        val firstWindow = view == null
        if (!ensureWindow(app.preferences.state.value)) {
            app.workspace.report("Android не разрешил показать плавающую панель.")
            stopSelf()
            return START_NOT_STICKY
        }
        val expanded = intent?.action == ACTION_EXPAND
        if (firstWindow && !screenOff) controller.show(autoListen = false, playSound = !expanded)
        active = this
        running.value = true
        if (expanded) expand()
        if (intent?.action == ACTION_MIC) controller.toggleMicrophone()
        refreshWake()
        return START_STICKY
    }

    private fun refreshWake() {
        val prefs = app.preferences.state.value
        val input = controller.state.value
        val recording = input.listening || input.finishingSpeech
        if (wakeArmed && !screenOff && !externalDictation && !recording && !input.waiting &&
            input.screen == null && input.proposal == null && !controller.voice.speaking.value
        ) wake.update(prefs.wakeLanguage)
        else wake.update(null)

        if (micForeground && !wakeArmed && !recording) foreground(false)
    }

    private fun ensureWindow(prefs: AxisPreferences): Boolean {
        val accessibility = DeviceControlService.instance?.takeIf { prefs.accessibilityOverlay }
        val normal = getSystemService(WindowManager::class.java).takeIf { Settings.canDrawOverlays(this) }
        val accessible = accessibility?.getSystemService(WindowManager::class.java)
        if (accessible != null && ensureWindow(accessible, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, prefs)) return true
        return normal != null && ensureWindow(normal, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, prefs)
    }

    @Suppress("DEPRECATION") // Android 10 also uses this mode to keep the editor above the IME.
    private fun ensureWindow(manager: WindowManager, type: Int, prefs: AxisPreferences): Boolean {
        if (view != null && windows === manager && layout.type == type) {
            reposition(prefs)
            return true
        }
        detachWindow()
        windows = manager
        layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Axis floating assistant"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val panel = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent {
                FloatingAssistantPanel(app, controller, ::toggleMicrophone, ::setEditorFocus, showRequest,
                    drag = ::dragPanel, finishDrag = ::finishDrag) {
                    openActivity(overlayIntent(this@FloatingAssistantService))
                }
            }
        }
        panel.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (panel === view && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                panel.post { if (panel === view) reposition(app.preferences.state.value) }
            }
        }
        screenOff = screenOff || getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (screenOff) panel.visibility = android.view.View.GONE
        reposition(prefs)
        return try {
            manager.addView(panel, layout)
            view = panel
            registry.currentState = Lifecycle.State.RESUMED
            true
        } catch (_: RuntimeException) {
            windows = null
            panel.disposeComposition()
            false
        }
    }

    private fun detachWindow() {
        val panel = view ?: return
        view = null
        dragPosition = null
        dragAnchor = null
        if (panel.isAttachedToWindow) {
            try { windows?.removeViewImmediate(panel) } catch (_: IllegalArgumentException) { }
        }
        windows = null
        panel.disposeComposition()
    }

    private fun expand() {
        if (screenOff) return
        showRequest++
        controller.playOpeningSound()
        val input = controller.state.value
        if (app.preferences.state.value.autoListen && !input.listening && !input.finishingSpeech &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) toggleMicrophone()
    }

    private fun openActivity(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            controller.report("Не удалось открыть окно Axis. Откройте Axis с домашнего экрана.")
        }
    }

    private fun toggleMicrophone() {
        val input = controller.state.value
        if (input.listening || input.finishingSpeech) controller.toggleMicrophone()
        else if (!input.waiting && input.screen == null && input.proposal == null) {
            if (micForeground) controller.toggleMicrophone() else openMicrophone()
        }
    }

    private fun openMicrophone() {
        openActivity(Intent(this, OverlayMicrophoneActivity::class.java))
    }

    private fun setEditorFocus(enabled: Boolean) {
        if (view == null) return
        val next = if (enabled) layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (next != layout.flags) {
            layout.flags = next
            updateLayout()
        }
    }

    @Suppress("DEPRECATION")
    private fun availableFrame(): Rect {
        val manager = windows ?: return Rect()
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val bounds = metrics.bounds
            return Rect(bounds.left + insets.left, bounds.top + insets.top,
                bounds.right - insets.right, bounds.bottom - insets.bottom)
        }
        return Rect().apply {
            view?.getWindowVisibleDisplayFrame(this)
            if (isEmpty) set(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    private fun dragPanel(delta: Offset) {
        val panel = view ?: return
        val frame = availableFrame()
        val maxX = (frame.width() - panel.width).coerceAtLeast(0)
        val maxY = (frame.height() - panel.height).coerceAtLeast(0)
        val start = dragPosition ?: IntArray(2).let { location ->
            panel.getLocationOnScreen(location)
            Offset((location[0] - frame.left).toFloat(), (location[1] - frame.top).toFloat())
        }
        val x = (start.x + delta.x).coerceIn(0f, maxX.toFloat())
        val y = (start.y + delta.y).coerceIn(0f, maxY.toFloat())
        dragPosition = Offset(x, y)
        dragAnchor = OverlayAnchor(
            if (maxX == 0) 0f else x / maxX,
            if (maxY == 0) 0f else y / maxY,
        )
        applyPosition(Gravity.TOP or Gravity.LEFT, x.roundToInt(), y.roundToInt())
    }

    private fun finishDrag() {
        val anchor = dragAnchor ?: return
        dragPosition = null
        dragAnchor = null
        app.preferences.update(app.preferences.state.value.copy(overlayAnchor = anchor))
    }

    private fun reposition(prefs: AxisPreferences) {
        if (dragPosition != null) return
        val custom = prefs.overlayAnchor
        val panel = view
        if (custom != null && panel != null) {
            val frame = availableFrame()
            val width = (frame.width() - panel.width).coerceAtLeast(0)
            val height = (frame.height() - panel.height).coerceAtLeast(0)
            applyPosition(Gravity.TOP or Gravity.LEFT, (width * custom.x).roundToInt(), (height * custom.y).roundToInt())
            return
        }
        val gravity = Gravity.BOTTOM or when (prefs.overlayPosition) {
            OverlayPosition.LEFT -> Gravity.LEFT
            OverlayPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            OverlayPosition.RIGHT -> Gravity.RIGHT
        }
        val density = resources.displayMetrics.density
        val x = if (prefs.overlayPosition == OverlayPosition.CENTER) 0 else (12 * density).toInt()
        applyPosition(gravity, x, (prefs.overlayOffset * density).toInt())
    }

    private fun applyPosition(gravity: Int, x: Int, y: Int) {
        if (layout.gravity == gravity && layout.x == x && layout.y == y) return
        layout.gravity = gravity
        layout.x = x
        layout.y = y
        updateLayout()
    }

    private fun updateLayout() {
        val panel = view?.takeIf { it.isAttachedToWindow } ?: return
        try {
            windows?.updateViewLayout(panel, layout)
        } catch (_: IllegalArgumentException) {
            stopSelf()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun foreground(microphone: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Плавающая панель Axis", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, mainIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_axis_tile).setContentTitle("Плавающая панель Axis")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Закрыть", close).build())
            .build()
        val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            (if (microphone && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ServiceCompat.startForeground(this, 43, notification, type)
        micForeground = microphone
    }

    override fun onDestroy() {
        unregisterReceiver(screenState)
        scope.cancel()
        wake.update(null)
        wakeState.value = WakeStatus.PAUSED
        controller.hide()
        detachWindow()
        if (active === this) {
            active = null
            running.value = false
        }
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "axis-floating-overlay"
        private const val ACTION_STOP = "axis.stopOverlay"
        private const val ACTION_EXPAND = "axis.expandOverlay"
        private const val ACTION_WAKE = "axis.armWakeWord"
        private var active: FloatingAssistantService? = null
        private val running = MutableStateFlow(false)
        internal val isRunning = running.asStateFlow()
        private val wakeState = MutableStateFlow(WakeStatus.PAUSED)
        internal val wakeStatus = wakeState.asStateFlow()

        internal fun setExternalDictation(active: Boolean) {
            val service = this.active ?: return
            service.externalDictation = active
            service.refreshWake()
        }
        internal const val ACTION_MIC = "axis.startOverlayMicrophone"

        internal fun canStart(context: Context): Boolean =
            Settings.canDrawOverlays(context) ||
                ((context.applicationContext as AxisApplication).preferences.state.value.accessibilityOverlay &&
                    DeviceControlService.instance != null)

        internal fun openRunning(): Boolean {
            val service = active ?: return false
            if (service.screenOff) return false
            service.expand()
            return true
        }

        internal fun start(context: Context, expand: Boolean = false, armWake: Boolean = false): Boolean {
            if (expand && context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            if (expand && openRunning()) return true
            if (!canStart(context)) return false
            return try {
                val request = Intent(context, FloatingAssistantService::class.java)
                if (armWake) request.action = ACTION_WAKE
                else if (expand) request.action = ACTION_EXPAND
                ContextCompat.startForegroundService(context, request)
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }
}

class OverlayMicrophoneActivity : ComponentActivity() {
    private var requested = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableRecording() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean("requested") == true
    }

    override fun onPostResume() {
        super.onPostResume()
        if (requested) return
        requested = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) enableRecording()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }

    private fun enableRecording() {
        try {
            startService(Intent(this, FloatingAssistantService::class.java).setAction(FloatingAssistantService.ACTION_MIC))
        } catch (_: IllegalStateException) {
            (application as AxisApplication).workspace.report("Android не разрешил запустить микрофон.")
        }
        // Keep the activity visible until the service receives the microphone start request.
        window.decorView.postDelayed({ finish() }, 350)
    }
}
