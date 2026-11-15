package io.github.kittkanev.axis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AxisAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

class AxisAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AxisAssistantSession(this)
}

private class AxisAssistantSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner {

    private val app = context.applicationContext as AxisApplication
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    private var view: ComposeView? = null
    private var floating = false
    private var transfer: Job? = null
    private val controller = AssistantController(context, app, ::openAxis, ::hide)

    init { setTheme(R.style.Theme_Axis_Assistant) }

    override fun onCreate() {
        super.onCreate()
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        view = this
        setViewTreeLifecycleOwner(this@AxisAssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AxisAssistantSession)
        setContent { AssistantPanel(app, controller, ::pinOverlay) }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        floating = FloatingAssistantService.openRunning()
        setUiEnabled(!floating)
    }

    @Suppress("DEPRECATION") // Also request resize on Android 10.
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (floating) {
            // Let Android acknowledge the headless show before closing its session.
            context.mainExecutor.execute { hide() }
            return
        }
        window?.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setKeepAwake(false)
        registry.currentState = Lifecycle.State.RESUMED
        controller.hide()
        val starting = FloatingAssistantService.start(context, expand = true)
        controller.show(autoListen = !starting, playSound = !starting)
        if (starting) transfer = lifecycleScope.launch {
            if (awaitFloatingPanel()) hide()
            else controller.playOpeningSound()
        }
    }

    override fun onHide() {
        transfer?.cancel()
        transfer = null
        controller.hide()
        registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        transfer?.cancel()
        controller.hide()
        view?.disposeComposition()
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun pinOverlay() {
        context.startActivity(overlayIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }

    private fun openAxis(askMicrophone: Boolean) {
        app.requestMicrophone = askMicrophone
        val intent = mainIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } finally {
            hide()
        }
    }
}

class AssistantPanelActivity : ComponentActivity() {
    private lateinit var controller: AssistantController
    private var transfer: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AxisApplication
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        controller = AssistantController(this, app, { askMicrophone ->
            app.requestMicrophone = askMicrophone
            startActivity(mainIntent(this))
            finish()
        }, ::finish)
        setContent { AssistantPanel(app, controller, ::pinOverlay) }
    }

    private fun pinOverlay() {
        startActivity(overlayIntent(this))
        finish()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (FloatingAssistantService.openRunning()) {
            finish()
            return
        }
        val starting = FloatingAssistantService.start(this, expand = true)
        controller.show(autoListen = !starting, playSound = !starting)
        if (starting) transfer = lifecycleScope.launch {
            if (awaitFloatingPanel()) finish()
            else controller.playOpeningSound()
        }
    }

    override fun onStop() {
        transfer?.cancel()
        transfer = null
        controller.hide()
        super.onStop()
    }
}

class AxisTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openPanel) else openPanel()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Android 13 and older only.
    private fun openPanel() {
        val intent = Intent(this, AssistantPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

private suspend fun awaitFloatingPanel(): Boolean = withTimeoutOrNull(2_500) {
    FloatingAssistantService.isRunning.first { it }
} == true

internal const val EXTRA_OPEN_OVERLAY = "io.github.kittkanev.axis.OPEN_OVERLAY"
internal fun overlayIntent(context: Context) = mainIntent(context).putExtra(EXTRA_OPEN_OVERLAY, true)

internal fun mainIntent(context: Context) =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
