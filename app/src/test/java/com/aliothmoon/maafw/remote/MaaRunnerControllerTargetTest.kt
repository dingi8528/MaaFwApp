package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.bridge.DriverClass
import com.aliothmoon.maafw.maa.MaaAgentClientLibrary
import com.aliothmoon.maafw.maa.MaaAgentClientLoader
import com.aliothmoon.maafw.maa.MaaFrameworkLibrary
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.maa.MaaStatus
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.PrimaryInputGuard
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.runner.RunOutcome
import com.aliothmoon.maafw.runner.RunPlanPayload
import com.sun.jna.Pointer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Drives the runner's real handle preparation without loading Android native libraries. */
class MaaRunnerControllerTargetTest {
    private lateinit var runner: MaaRunner
    private val lib = mockk<MaaFrameworkLibrary>(relaxed = true)
    private val agentLib = mockk<MaaAgentClientLibrary>(relaxed = true)
    private val session = mockk<AgentSession>(relaxed = true)
    private val resource = Pointer(100)
    private val client = Pointer(200)
    private val configs = mutableListOf<String>()
    private val controllers = mutableListOf<Pointer>()
    private val taskers = mutableListOf<Pointer>()

    @Before
    fun setUp() {
        mockkObject(PrimaryDisplayManager, VirtualDisplayManager, MaaAgentClientLoader)
        every { MaaAgentClientLoader.library } returns agentLib
        every { PrimaryDisplayManager.getCaptureSize() } returns (1280 to 720)
        every { VirtualDisplayManager.getDisplayId() } returns 7
        every { VirtualDisplayManager.getConfig() } returns VirtualDisplayManager.DisplayConfig(1280, 720, 240)
        every { lib.MaaAndroidNativeControllerCreate(any()) } answers {
            configs += firstArg<String>()
            Pointer(1000L + controllers.size).also { controllers += it }
        }
        every { lib.MaaControllerConnected(any()) } returns 1
        every { lib.MaaControllerPostConnection(any()) } returns 1L
        every { lib.MaaControllerWait(any(), any()) } returns MaaStatus.SUCCEEDED
        every { lib.MaaTaskerCreate() } answers {
            Pointer(2000L + taskers.size).also { taskers += it }
        }
        every { lib.MaaTaskerBindResource(any(), any()) } returns 1
        every { lib.MaaTaskerBindController(any(), any()) } returns 1
        every { lib.MaaTaskerInited(any()) } returns 1
        every { agentLib.MaaAgentClientRegisterResourceSink(any(), any()) } returns 1
        every { agentLib.MaaAgentClientRegisterControllerSink(any(), any()) } returns 1
        every { agentLib.MaaAgentClientRegisterTaskerSink(any(), any()) } returns 1
        runner = MaaRunner(mockk())
        setField(runner, "resource", resource)
    }

    @After
    fun tearDown() {
        PrimaryInputGuard.clear()
        (field(runner, "primaryInputStopper") as ExecutorService).shutdownNow()
        unmockkAll()
    }

    @Test
    fun `same display resize rebinds live agent before retiring old native pointers`() {
        attachAgent()
        val first = snapshot()
        assertNull(prepareController(first))
        val oldController = controllers.single()
        val oldTasker = taskers.single()

        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
        val resized = snapshot()
        assertNull(prepareController(resized))
        assertEquals(2, controllers.size)
        assertEquals(2, taskers.size)
        assertConfig(0, 1280, 720, 0, false)
        assertConfig(1, 1920, 1080, 0, false)
        assertSame(resized, field(runner, "boundControllerTarget"))
        verifyOrder {
            agentLib.MaaAgentClientRegisterControllerSink(client, controllers[1])
            agentLib.MaaAgentClientRegisterTaskerSink(client, taskers[1])
            lib.MaaTaskerPostStop(oldTasker)
            lib.MaaTaskerDestroy(oldTasker)
            lib.MaaControllerDestroy(oldController)
        }
        verify(exactly = 0) { session.close() }
        verify(exactly = 0) { agentLib.MaaAgentClientDestroy(any()) }
    }

    @Test
    fun `unchanged capture reuses controller and tasker while disconnected one rebuilds`() {
        assertNull(prepareController(snapshot()))
        assertNull(prepareController(snapshot()))
        assertEquals(1, controllers.size)
        assertEquals(1, taskers.size)
        every { lib.MaaControllerConnected(controllers[0]) } returns 0
        assertNull(prepareController(snapshot()))
        assertEquals(2, controllers.size)
        verify(exactly = 1) { lib.MaaControllerDestroy(controllers[0]) }
    }

    @Test
    fun `snapshot is read once and later display changes cannot alter its config`() {
        every { PrimaryDisplayManager.getCaptureSize() } returnsMany listOf(1280 to 720, 1920 to 1080)
        val captured = snapshot()
        assertNull(prepareController(captured))
        assertConfig(0, 1280, 720, 0, false)
        verify(exactly = 1) { PrimaryDisplayManager.getCaptureSize() }
    }

    @Test
    fun `failed replacement connection retains original controller key and tasker`() {
        val original = snapshot()
        assertNull(prepareController(original))
        every { PrimaryDisplayManager.getCaptureSize() } returns (1920 to 1080)
        every { lib.MaaControllerWait(any(), any()) } returns MaaStatus.FAILED
        assertNotNull(prepareController(snapshot()))
        assertSame(original, field(runner, "boundControllerTarget"))
        assertEquals(controllers[0], field(runner, "controller"))
        assertEquals(taskers[0], field(runner, "tasker"))
        verify(exactly = 1) { lib.MaaControllerDestroy(controllers[1]) }
        verify(exactly = 0) { lib.MaaControllerDestroy(controllers[0]) }
        verify(exactly = 0) { lib.MaaTaskerDestroy(any()) }
    }

    @Test
    fun `controller release clears its key and stops tasker before controller destruction`() {
        assertNull(prepareController(snapshot()))
        invoke("releaseController", lib)
        assertNull(field(runner, "boundControllerTarget"))
        assertNull(field(runner, "controller"))
        assertNull(field(runner, "tasker"))
        verifyOrder {
            lib.MaaTaskerPostStop(taskers[0])
            lib.MaaTaskerDestroy(taskers[0])
            lib.MaaControllerDestroy(controllers[0])
        }
        assertNull(prepareController(snapshot()))
        assertEquals(2, controllers.size)
    }

    @Test
    fun `background uses actual virtual capture size despite stale payload or wide primary`() {
        every { PrimaryDisplayManager.getCaptureSize() } returns (2160 to 720)
        val background = payload(DisplayMode.BACKGROUND).copy(screenWidth = 2160, screenHeight = 720)
        assertNull(prepareController(snapshot(background)))
        assertConfig(0, 1280, 720, 7, true)
        verify(exactly = 0) { PrimaryDisplayManager.getCaptureSize() }
        verify(exactly = 1) { VirtualDisplayManager.getConfig() }
    }

    @Test
    fun `wide primary fails actual task preparation before resources agents or native handles`() {
        every { PrimaryDisplayManager.getCaptureSize() } returns (2160 to 720)
        val failure = invoke("prepare", lib, payload())
        assertNotNull(failure)
        assertEquals(RunOutcome.FOREGROUND_LANDSCAPE_REQUIRED, field(failure!!, "outcome"))
        val reason = field(failure, "reason") as String
        assertTrue(reason.contains("2160×720"))
        assertTrue(reason.contains("后台模式"))
        assertTrue(reason.contains("调整主屏分辨率"))
        verify(exactly = 1) { PrimaryDisplayManager.getCaptureSize() }
        verify(exactly = 0) { lib.MaaResourceCreate() }
        verify(exactly = 0) { lib.MaaAndroidNativeControllerCreate(any()) }
        verify(exactly = 0) { agentLib.MaaAgentClientCreateV2(any()) }
    }

    @Test
    fun `portrait capture is rejected before resources even if payload says landscape`() {
        every { PrimaryDisplayManager.getCaptureSize() } returns (1080 to 1920)
        val failure = invoke("prepare", lib, payload().copy(screenWidth = 1920, screenHeight = 1080))!!
        assertEquals(RunOutcome.FOREGROUND_LANDSCAPE_REQUIRED, field(failure, "outcome"))
        assertTrue((field(failure, "reason") as String).contains("手动打开游戏"))
        verify(exactly = 1) { PrimaryDisplayManager.getCaptureSize() }
        verify(exactly = 0) { lib.MaaResourceCreate() }
        verify(exactly = 0) { lib.MaaAndroidNativeControllerCreate(any()) }
        verify(exactly = 0) { agentLib.MaaAgentClientCreateV2(any()) }
    }

    @Test
    fun `changed primary input requests native task stop once with an explicit failure`() {
        mockkObject(MaaFrameworkLoader)
        every { MaaFrameworkLoader.library } returns lib
        setField(runner, "running", true)
        assertNull(prepareController(snapshot()))
        every { PrimaryDisplayManager.getCaptureSize() } returns (720 to 1280)
        assertFalse(PrimaryInputGuard.allowsInput(0))
        assertFalse(PrimaryInputGuard.allowsInput(0))
        invoke("awaitPrimaryInputStopRequests")
        assertTrue((field(runner, "primaryCaptureFailure") as String).contains("1280×720 → 720×1280"))
        verify(exactly = 1) { lib.MaaTaskerPostStop(taskers.single()) }
    }

    @Test
    fun `manual stop disarms bound primary input before another round`() {
        mockkObject(MaaFrameworkLoader)
        every { MaaFrameworkLoader.library } returns lib
        setField(runner, "running", true)
        assertNull(prepareController(snapshot()))
        assertTrue(PrimaryInputGuard.allowsInput(0))
        assertTrue(runner.stop())
        assertFalse(PrimaryInputGuard.allowsInput(0))
        assertNull(field(runner, "primaryCaptureFailure"))
        verify(exactly = 1) { lib.MaaTaskerPostStop(taskers.single()) }
    }

    @Test
    fun `manual stop clears primary input before releasing the lifecycle lock to a new run`() {
        mockkObject(MaaFrameworkLoader)
        every { MaaFrameworkLoader.library } returns lib
        setField(runner, "running", true)
        assertNull(prepareController(snapshot()))
        mockkStatic(DriverClass::class)
        var cleared = false
        every { DriverClass.cancelPrimaryInput() } answers {
            assertTrue(Thread.holdsLock(field(runner, "lifecycleLock")))
            assertFalse(PrimaryInputGuard.allowsInput(0))
            cleared = true
        }
        assertTrue(runner.stop())
        assertTrue(cleared)
    }

    @Test
    fun `a delayed size callback from the previous run cannot stop a new run with the same bounds`() {
        setField(runner, "running", true)
        val target = snapshot()
        assertNull(prepareController(target))
        setField(runner, "runGeneration", 1L)
        invoke("onPrimaryCaptureChanged", target, 720 to 1280, 0L)
        invoke("awaitPrimaryInputStopRequests")
        assertNull(field(runner, "primaryCaptureFailure"))
        verify(exactly = 0) { lib.MaaTaskerPostStop(any()) }
    }

    @Test
    fun `input callback queues without lifecycle lock and delayed stop cannot reach the next run`() {
        setField(runner, "running", true)
        val target = snapshot()
        assertNull(prepareController(target))
        val returned = CountDownLatch(1)
        val callback = Thread {
            invoke("onPrimaryCaptureChanged", target, 720 to 1280, 0L)
            returned.countDown()
        }
        synchronized(field(runner, "lifecycleLock")!!) {
            callback.start()
            assertTrue("input callback must not wait for lifecycleLock", returned.await(2, TimeUnit.SECONDS))
            // The old run ends and a new one reuses the same target before the queued stop gets the lock.
            setField(runner, "runGeneration", 1L)
        }
        callback.join(2000)
        invoke("awaitPrimaryInputStopRequests")
        assertNull(field(runner, "primaryCaptureFailure"))
        verify(exactly = 0) { lib.MaaTaskerPostStop(any()) }
    }

    @Test
    fun `binder stop and changed input finish when native stop synchronously releases a touch`() {
        mockkObject(MaaFrameworkLoader)
        every { MaaFrameworkLoader.library } returns lib
        setField(runner, "running", true)
        assertNull(prepareController(snapshot()))
        every { PrimaryDisplayManager.getCaptureSize() } returns (720 to 1280)
        val inputHeld = CountDownLatch(1)
        val nativeStopEntered = CountDownLatch(1)
        val allowChangedInput = CountDownLatch(1)
        every { lib.MaaTaskerPostStop(any()) } answers {
            nativeStopEntered.countDown()
            DriverClass.touchUp(100, 200, 0, 0) // v5.14.3 auto_release_pressed calls this synchronously.
            1L
        }
        val input = Thread {
            synchronized(DriverClass::class.java) {
                inputHeld.countDown()
                check(allowChangedInput.await(2, TimeUnit.SECONDS))
                PrimaryInputGuard.allowsInput(0)
            }
        }
        val stop = Thread { runner.stop() }
        input.start()
        assertTrue(inputHeld.await(2, TimeUnit.SECONDS))
        stop.start()
        assertTrue(nativeStopEntered.await(2, TimeUnit.SECONDS))
        allowChangedInput.countDown()
        input.join(2000)
        stop.join(2000)
        assertFalse("input callback deadlocked with Binder stop", input.isAlive)
        assertFalse("native touch release deadlocked with input", stop.isAlive)
        invoke("awaitPrimaryInputStopRequests")
        assertNull(field(runner, "primaryCaptureFailure"))
        verify(exactly = 1) { lib.MaaTaskerPostStop(taskers.single()) }
    }

    @Test
    fun `missing primary capture has an explicit prepare failure without fallback dimensions`() {
        every { PrimaryDisplayManager.getCaptureSize() } returns null
        assertNull(invoke("snapshotControllerTarget", payload()))
        val failure = invoke("prepare", lib, payload())!!
        assertEquals("主屏采集未启动", field(failure, "reason"))
        verify(exactly = 0) { lib.MaaAndroidNativeControllerCreate(any()) }
    }

    private fun payload(mode: Int = DisplayMode.PRIMARY) = RunPlanPayload(
        resourcePaths = emptyList(), screenWidth = 0, screenHeight = 0, displayMode = mode, tasks = emptyList(),
    )

    private fun snapshot(payload: RunPlanPayload = payload()): Any = invoke("snapshotControllerTarget", payload)!!
    private fun prepareController(target: Any): Any? = invoke("prepareController", lib, target)

    private fun attachAgent() {
        val type = MaaRunner::class.java.declaredClasses.single { it.simpleName == "ActiveAgent" }
        val constructor = type.getDeclaredConstructor(Pointer::class.java, AgentSession::class.java)
        constructor.isAccessible = true
        setField(runner, "agents", listOf(constructor.newInstance(client, session)))
    }

    private fun assertConfig(index: Int, width: Int, height: Int, displayId: Int, forceStop: Boolean) {
        val config = Json.parseToJsonElement(configs[index]).jsonObject
        val size = config.getValue("screen_resolution").jsonObject
        assertEquals(width, size.getValue("width").jsonPrimitive.int)
        assertEquals(height, size.getValue("height").jsonPrimitive.int)
        assertEquals(displayId, config.getValue("display_id").jsonPrimitive.int)
        assertEquals(forceStop, config.getValue("force_stop").jsonPrimitive.boolean)
    }

    private fun invoke(name: String, vararg args: Any): Any? {
        val method = MaaRunner::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return method.invoke(runner, *args)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(owner)
    }

    private fun setField(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        set(owner, value)
    }
}
