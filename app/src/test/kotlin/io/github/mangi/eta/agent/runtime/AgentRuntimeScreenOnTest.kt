package io.github.mangi.eta.agent.runtime

import android.app.Application
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeScreenOnTest {
    @Test
    fun pauseResumeAndCancellationUpdateBothAppAndGuiWindowState() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("active-run")
            val capsule = ComposeView(service)
            service.addActiveSession(session)
            service.setField("capsuleView", capsule)

            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            service.invoke("requestPause")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            session.controller.cancel()
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun completedRunStopsKeepingScreenOnBeforeServiceDestruction() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("completed-run")
            service.addActiveSession(session)
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            session.complete(AgentRuntimeWire.RunResult(session.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun destroyingServiceClearsActiveRunScreenRequest() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.addActiveSession(AgentRuntimeSession("destroyed-run"))
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
        assertFalse(AgentRuntimeService.keepScreenOn.value)
    }

    /** 3.3.3 起 activeSession 是 sessions 集合的计算属性，只能通过集合注入会话。 */
    private fun AgentRuntimeService.addActiveSession(session: AgentRuntimeSession) {
        val field = AgentRuntimeService::class.java.getDeclaredField("sessions").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        (field.get(this) as MutableSet<AgentRuntimeSession>).add(session)
    }

    private fun AgentRuntimeService.setField(name: String, value: Any) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(this, value)
    }

    private fun AgentRuntimeService.invoke(name: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(this)
    }
}
