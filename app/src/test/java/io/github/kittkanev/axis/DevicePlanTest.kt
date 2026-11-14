package io.github.kittkanev.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DevicePlanTest {
    @Test
    fun translatesModelPlanIntoReviewedCommands() {
        val plan = modelDevicePlan(
            """[{"action":"click","value":"Поиск"},{"action":"type","value":"Тест"},{"action":"read"}]""",
        )
        assertEquals(
            listOf(
                DeviceStep(DeviceStepKind.CLICK, "Поиск"),
                DeviceStep(DeviceStepKind.TYPE, "Тест"),
                DeviceStep(DeviceStepKind.READ),
            ),
            parseDevicePlan(plan),
        )
    }

    @Test
    fun rejectsDocumentLikeTextDisguisedAsStepsAndOutOfScopeActions() {
        for (raw in listOf(
            """[{"action":"click","value":"Кнопка\nback"}]""",
            """[{"action":"send","value":"Привет"}]""",
            """[{"action":"read","value":"Получатель"}]""",
            "[]",
        )) {
            assertThrows(AxisException::class.java) { modelDevicePlan(raw) }
        }
        assertThrows(AxisException::class.java) { parseDevicePlan(List(11) { "read" }.joinToString("\n")) }
    }
}
