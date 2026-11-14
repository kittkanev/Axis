package io.github.kittkanev.axis

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceControlService : AccessibilityService() {
    private var app: DeviceApp? = null
    private var steps = emptyList<DeviceStep>()
    private var index = 0
    private var overlay: View? = null
    private var stepLabel: TextView? = null
    private var output: TextView? = null
    private var next: Button? = null
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onServiceConnected() {
        service = WeakReference(this)
        connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        stop()
        if (instance === this) {
            service = null
            connected.value = false
        }
        super.onDestroy()
    }

    internal fun start(target: DeviceApp, plan: List<DeviceStep>): Boolean {
        stop()
        app = target
        steps = plan
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 10)
            setBackgroundColor(Color.rgb(36, 29, 62))
        }
        stepLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }.also(panel::addView)
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTextIsSelectable(true)
        }.also { text ->
            panel.addView(ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, (146 * resources.displayMetrics.density).toInt())
                addView(text)
                visibility = View.GONE
            })
        }
        val controls = LinearLayout(this)
        next = Button(this).apply {
            text = "Выполнить шаг"
            setOnClickListener { runStep() }
        }.also(controls::addView)
        controls.addView(Button(this).apply {
            text = "Стоп"
            setOnClickListener { stop() }
        })
        panel.addView(controls)
        val params = WindowManager.LayoutParams(
            -1, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            title = "Axis · управление"
        }
        return try {
            windows.addView(panel, params)
            overlay = panel
            updateLabel()
            true
        } catch (_: RuntimeException) {
            stop()
            false
        }
    }

    internal fun stop() {
        overlay?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }
        overlay = null
        stepLabel = null
        output = null
        next = null
        app = null
        steps = emptyList()
        index = 0
    }

    private fun updateLabel() {
        stepLabel?.text = steps.getOrNull(index)?.let {
            "Axis · ${app?.label}\n${index + 1}/${steps.size} · ${it.description}"
        } ?: "Axis · задача завершена"
        next?.isEnabled = index < steps.size
    }

    private fun report(message: String, showFull: Boolean = false) {
        if (showFull) {
            output?.text = message
            (output?.parent as? View)?.visibility = View.VISIBLE
        } else {
            (output?.parent as? View)?.visibility = View.GONE
            stepLabel?.append("\n$message")
        }
    }

    private fun runStep() {
        val target = app ?: return
        val step = steps.getOrNull(index) ?: return
        val root = rootInActiveWindow
        updateLabel()
        if (keyguard.isKeyguardLocked || root?.packageName?.toString() != target.packageName) {
            report("Откройте ${target.label}, чтобы продолжить.")
            return
        }
        root ?: return
        val nodes = visibleNodes(root)
        if (nodes.any { it.isPassword || (it.isEditable && it.isAccessibilityDataSensitiveCompat()) }) {
            report("Действие остановлено: открыто чувствительное поле.")
            next?.isEnabled = false
            return
        }
        val success = when (step.kind) {
            DeviceStepKind.CLICK -> {
                val choices = nodes.filter { node ->
                    !node.isAccessibilityDataSensitiveCompat() &&
                        (node.text?.toString()?.trim()?.equals(step.value, ignoreCase = true) == true ||
                            node.contentDescription?.toString()?.trim()?.equals(step.value, ignoreCase = true) == true)
                }.mapNotNull { node ->
                    generateSequence(node) { current -> current.parent?.takeIf { it.windowId == root.windowId } }
                        .take(8).firstOrNull {
                            it.isClickable && it.isEnabled && !it.isAccessibilityDataSensitiveCompat()
                        }
                }.distinct()
                choices.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            DeviceStepKind.TYPE -> {
                val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.takeIf { it.windowId == root.windowId && it.isEditable && it.isEnabled &&
                        it.isVisibleToUser && !it.isPassword && !it.isAccessibilityDataSensitiveCompat() }
                field?.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.value) },
                ) == true
            }
            DeviceStepKind.READ -> {
                val lines = nodes.asSequence().filterNot { it.isEditable || it.isAccessibilityDataSensitiveCompat() }
                    .mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf(String::isNotBlank) }
                    .distinct().take(35).joinToString("\n").take(3000)
                index++
                updateLabel()
                report(lines.ifBlank { "На экране нет доступного текста." }, showFull = true)
                return
            }
            DeviceStepKind.UP, DeviceStepKind.DOWN -> {
                val action = if (step.kind == DeviceStepKind.DOWN) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                nodes.firstOrNull { node -> node.isScrollable && node.actionList.any { it.id == action } }
                    ?.performAction(action) == true
            }
            DeviceStepKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (success) {
            index++
            updateLabel()
            report("Команда принята Android. Проверьте результат на экране.")
        } else {
            report("Шаг не выполнен. Проверьте экран и повторите или остановите задачу.")
        }
    }

    private fun visibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val results = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 300) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser) results.add(node)
            if (node.isAccessibilityDataSensitiveCompat()) continue
            for (i in 0 until minOf(node.childCount, 300 - pending.size)) {
                node.getChild(i)?.takeIf { it.windowId == root.windowId }?.let(pending::add)
            }
        }
        return results
    }

    companion object {
        private var service: WeakReference<DeviceControlService>? = null
        internal val instance: DeviceControlService? get() = service?.get()
        private val connected = MutableStateFlow(false)
        internal val isConnected = connected.asStateFlow()
    }
}

private fun AccessibilityNodeInfo.isAccessibilityDataSensitiveCompat(): Boolean =
    android.os.Build.VERSION.SDK_INT >= 34 && isAccessibilityDataSensitive
