package io.github.kittkanev.axis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAutomationTest {
    private val settings = DeviceApp("Settings", "com.android.settings")
    private val appScreen = ToolScreen(settings.packageName, 13, emptyList(), null)

    @Test
    fun openingAnAppContinuesTheSameRequestWithItsScreen() = runBlocking {
        var opened = false
        var changed = false
        val nextScreen = appScreen.copy(windowId = 14)
        val result = runDeviceCommand(
            Message(Role.USER, "Open Settings and scroll down"), Message(Role.SYSTEM, "Be concise."),
            setOf(AxisTool.OPEN, AxisTool.DOWN), initialScreen = null,
            enabled = { true },
            readScreen = { if (changed) nextScreen else appScreen.takeIf { opened } },
            open = {
                assertEquals("Settings", it)
                opened = true
                settings
            },
            execute = { screen, call ->
                assertEquals(appScreen, screen)
                assertEquals(AxisTool.DOWN, call.tool)
                changed = true
                true
            },
            respond = { messages, tools ->
                assertTrue(messages.last().text.startsWith("Open Settings and scroll down"))
                when {
                    !opened -> {
                        assertEquals(setOf(AxisTool.OPEN), tools)
                        ModelReply(tool = AxisToolCall(AxisTool.OPEN, "Settings"))
                    }
                    !changed -> {
                        assertTrue(AxisTool.DOWN in tools)
                        assertTrue(messages.last().text.contains("Opened Settings"))
                        ModelReply(tool = AxisToolCall(AxisTool.DOWN, ""))
                    }
                    else -> {
                        assertTrue(messages.last().text.contains(AxisTool.DOWN.label))
                        ModelReply("Scrolled down in Settings.")
                    }
                }
            },
        )
        assertEquals("Scrolled down in Settings.", result)
    }

    @Test
    fun disablingAutoControlWhileModelRespondsPreventsTheTap() = runBlocking {
        var enabled = true
        var executed = false
        val result = runCatching {
            runDeviceCommand(
                Message(Role.USER, "Scroll down"), Message(Role.SYSTEM, "Be concise."),
                setOf(AxisTool.DOWN), appScreen,
                enabled = { enabled },
                readScreen = { appScreen },
                open = { error("Unexpected app launch") },
                execute = { _, _ -> executed = true; true },
                respond = { _, _ ->
                    enabled = false
                    ModelReply(tool = AxisToolCall(AxisTool.DOWN, ""))
                },
            )
        }
        assertTrue(result.exceptionOrNull() is AxisException)
        assertFalse(executed)
    }
}
