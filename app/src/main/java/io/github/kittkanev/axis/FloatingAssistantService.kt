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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
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

class FloatingAssistantService : Service(), SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val app get() = application as AxisApplication
    private lateinit var controller: AssistantController
    private var view: ComposeView? = null
    private lateinit var layout: WindowManager.LayoutParams
    private var micForeground = false
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                controller.hide()
                view?.visibility = android.view.View.GONE
            } else {
                view?.visibility = android.view.View.VISIBLE
                controller.show(autoListen = false)
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
            else startActivity(mainIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, ::stopSelf, keepOpen = true)
        ContextCompat.registerReceiver(this, screenState, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            foreground(micForeground || intent?.action == ACTION_MIC)
        } catch (_: RuntimeException) {
            if (view == null) stopSelf()
            else controller.report("Android не разрешил включить микрофон.")
            return START_NOT_STICKY
        }
        if (view == null && !attach()) {
            app.workspace.report("Android не разрешил показать плавающую панель.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MIC) {
            controller.toggleMicrophone()
            if (!controller.state.value.listening && !controller.state.value.finishingSpeech && micForeground) {
                foreground(false)
            }
        }
        return START_STICKY
    }

    private fun attach(): Boolean {
        layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Axis floating assistant"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val panel = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent { FloatingAssistantPanel(app, controller, ::toggleMicrophone, ::setEditorFocus) }
        }
        view = panel
        reposition(app.preferences.state.value, false)
        try {
            windows.addView(panel, layout)
        } catch (_: RuntimeException) {
            return false
        }
        registry.currentState = Lifecycle.State.RESUMED
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (locked) panel.visibility = android.view.View.GONE else controller.show(autoListen = false)
        scope.launch {
            app.preferences.state.collect { reposition(it, true) }
        }
        scope.launch {
            controller.state.collect { input ->
                if (micForeground && !input.listening && !input.finishingSpeech) foreground(false)
            }
        }
        return true
    }

    private fun toggleMicrophone() {
        val input = controller.state.value
        if (input.listening || input.finishingSpeech) controller.toggleMicrophone()
        else if (!input.waiting && input.screen == null && input.proposal == null) openMicrophone()
    }

    private fun openMicrophone() {
        startActivity(Intent(this, OverlayMicrophoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun setEditorFocus(enabled: Boolean) {
        val next = if (enabled) layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (next != layout.flags) {
            layout.flags = next
            updateLayout()
        }
    }

    private fun reposition(prefs: AxisPreferences, update: Boolean) {
        layout.gravity = Gravity.BOTTOM or when (prefs.overlayPosition) {
            OverlayPosition.LEFT -> Gravity.LEFT
            OverlayPosition.CENTER -> Gravity.CENTER_HORIZONTAL
            OverlayPosition.RIGHT -> Gravity.RIGHT
        }
        val density = resources.displayMetrics.density
        layout.x = if (prefs.overlayPosition == OverlayPosition.CENTER) 0 else (12 * density).toInt()
        layout.y = (prefs.overlayOffset * density).toInt()
        if (update) updateLayout()
    }

    private fun updateLayout() {
        val panel = view?.takeIf { it.isAttachedToWindow } ?: return
        try {
            windows.updateViewLayout(panel, layout)
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
        controller.hide()
        view?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it); it.disposeComposition() }
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "axis-floating-overlay"
        private const val ACTION_STOP = "axis.stopOverlay"
        internal const val ACTION_MIC = "axis.startOverlayMicrophone"

        internal fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, FloatingAssistantService::class.java))
            true
        } catch (_: RuntimeException) {
            false
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
