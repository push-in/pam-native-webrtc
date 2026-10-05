package dev.pam.webrtc

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val module = WebRtcModule(context)
    private val audioModule = CallAudioModule(context)

    private data class Result(val ok: Boolean, val values: Map<String, WireValue>, val message: String)

    @Before
    fun grant() {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
    }

    @After
    fun cleanup() {
        WebRtcRuntime.closeAll()
        CallAudioSession.get(context).stop()
    }

    private fun call(target: dev.pam.nativeapp.modules.NativeModule, method: String, values: Map<String, WireValue>, timeout: Long = 15): Result {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>()
        target.invoke(method, WireMap.encode(values), ModuleCompletion { status, payload ->
            result.set(
                if (status == ModuleResultStatus.SUCCESS) {
                    Result(true, WireMap.decode(payload), "")
                } else {
                    Result(false, emptyMap(), String(payload))
                },
            )
            latch.countDown()
        })
        assertTrue("$method timed out", latch.await(timeout, TimeUnit.SECONDS))
        return result.get()
    }

    private fun rtc(method: String, session: String, vararg values: Pair<String, WireValue>) =
        call(module, method, mapOf("sessionId" to WireValue.Text(session), *values))

    private fun create(session: String, video: Boolean) = rtc(
        "create",
        session,
        "iceServersJson" to WireValue.Text("[]"),
        "video" to WireValue.Flag(video),
        "facing" to WireValue.Integer(1),
        "width" to WireValue.Integer(640),
        "height" to WireValue.Integer(480),
        "fps" to WireValue.Integer(15),
    ).also { assertTrue(it.message, it.ok) }

    /** Forwards every event of [from] to [to] and records connection states, like a signaling server would. */
    private fun pump(from: String, to: String, states: MutableList<Long>, remoteTracks: MutableList<Long>): Thread =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                val event = runCatching { rtc("next", from) }.getOrNull() ?: continue
                if (!event.ok) break
                runCatching {
                    when ((event.values["kind"] as WireValue.Integer).value) {
                        1L -> rtc(
                            "addIceCandidate",
                            to,
                            "candidate" to event.values.getValue("candidate"),
                            "sdpMid" to event.values.getValue("sdpMid"),
                            "sdpMLineIndex" to event.values.getValue("sdpMLineIndex"),
                        )
                        2L -> synchronized(states) { states += (event.values["state"] as WireValue.Integer).value }
                        3L -> synchronized(remoteTracks) { remoteTracks += (event.values["track"] as WireValue.Integer).value }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    @Test
    fun loopbackCallConnectsRendersRemoteVideoAndReportsStats() {
        create("caller", video = true)
        create("callee", video = false)
        val callerStates = mutableListOf<Long>()
        val calleeStates = mutableListOf<Long>()
        val calleeTracks = mutableListOf<Long>()
        pump("caller", "callee", callerStates, mutableListOf())
        pump("callee", "caller", calleeStates, calleeTracks)

        assertTrue(rtc("startLocal", "caller").ok)
        assertTrue(rtc("startLocal", "callee").ok)
        assertNotNull(WebRtcRuntime.session("caller")!!.localVideo)

        val offer = rtc("createOffer", "caller")
        assertTrue(offer.message, offer.ok)
        assertEquals(1L, (offer.values["type"] as WireValue.Integer).value)
        assertTrue(rtc("setRemoteDescription", "callee", "type" to WireValue.Integer(1), "sdp" to offer.values.getValue("sdp")).ok)
        val answer = rtc("createAnswer", "callee")
        assertTrue(answer.message, answer.ok)
        assertEquals(2L, (answer.values["type"] as WireValue.Integer).value)
        assertTrue(rtc("setRemoteDescription", "caller", "type" to WireValue.Integer(2), "sdp" to answer.values.getValue("sdp")).ok)

        waitUntil(20_000) { synchronized(calleeStates) { 3L in calleeStates } && synchronized(callerStates) { 3L in callerStates } }
        assertTrue(synchronized(calleeTracks) { 2L in calleeTracks && 1L in calleeTracks })

        // Remote video reaches a renderer bound through the view factory.
        val factory = RtcVideoViewFactory(context)
        val container = onMain { factory.create(context) {} as RtcVideoContainer }
        onMain {
            factory.update(
                container,
                mapOf(
                    "sessionId" to WireValue.Text("callee"),
                    "track" to WireValue.Integer(2),
                    "fit" to WireValue.Integer(2),
                    "mirror" to WireValue.Flag(false),
                ),
            )
        }
        waitUntil(15_000) { container.renderer.framesRendered > 5 }
        assertNotNull(onMain { container.renderer.boundTrack() })

        val stats = rtc("stats", "caller")
        assertTrue(stats.ok)
        assertTrue((stats.values["bytesSent"] as WireValue.Integer).value > 0)
        assertFalse((stats.values["relayed"] as WireValue.Flag).value)

        // Camera toggles keep the session alive; closing detaches renderers before disposal.
        assertTrue(rtc("setCamera", "caller", "enabled" to WireValue.Flag(false)).ok)
        assertFalse(WebRtcRuntime.session("caller")!!.isCapturing())
        assertTrue(rtc("setCamera", "caller", "enabled" to WireValue.Flag(true)).ok)
        assertTrue(WebRtcRuntime.session("caller")!!.isCapturing())
        assertTrue(rtc("setMicrophone", "caller", "enabled" to WireValue.Flag(false)).ok)

        assertTrue(rtc("close", "callee").ok)
        assertNull(onMain { container.renderer.boundTrack() })
        assertNull(WebRtcRuntime.session("callee"))
        assertFalse(rtc("next", "callee").ok)
        onMain { factory.release(container) }
    }

    @Test
    fun multipleSessionsAreIndependent() {
        create("one", video = false)
        create("two", video = false)
        assertEquals(setOf("one", "two"), WebRtcRuntime.sessionIds())
        assertTrue(rtc("startLocal", "one").ok)
        assertTrue(rtc("close", "one").ok)
        assertEquals(setOf("two"), WebRtcRuntime.sessionIds())
        assertTrue(rtc("startLocal", "two").ok)
        val offer = rtc("createOffer", "two")
        assertTrue(offer.message, offer.ok)
        assertTrue((offer.values["sdp"] as WireValue.Text).value.contains("m=audio"))
        assertFalse(rtc("createOffer", "missing").ok)
        assertFalse(rtc("create", "bad id!").ok)
    }

    @Test
    fun renegotiationAndIceEventsArriveThroughTheChannelWithoutPolling() {
        create("events", video = false)
        assertTrue(rtc("startLocal", "events").ok)
        assertTrue(rtc("createOffer", "events").ok)
        val kinds = mutableListOf<Long>()
        while (1L !in kinds && kinds.size < 8) {
            kinds += (rtc("next", "events").values["kind"] as WireValue.Integer).value
        }
        assertTrue("kinds=$kinds", 4L in kinds && 5L in kinds && 1L in kinds)
    }

    @Test
    fun eventChannelBuffersReplacesAndCloses() {
        val channel = EventChannel(capacity = 2)
        repeat(3) { channel.offer(mapOf("n" to WireValue.Integer(it.toLong()))) }
        assertEquals(2, channel.pendingCount())
        val first = AtomicReference<ByteArray>()
        channel.next { _, payload -> first.set(payload) }
        assertEquals(1L, (WireMap.decode(first.get())["n"] as WireValue.Integer).value)
        channel.next { _, _ -> }
        val replaced = AtomicReference<ModuleResultStatus>()
        channel.next { status, _ -> replaced.set(status) }
        channel.next { _, _ -> }
        assertEquals(ModuleResultStatus.FAILURE, replaced.get())
        channel.close()
        val closed = AtomicReference<ModuleResultStatus>()
        channel.next { status, _ -> closed.set(status) }
        assertEquals(ModuleResultStatus.FAILURE, closed.get())
    }

    @Test
    fun callAudioSwitchesModeRouteTonesAndRestores() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.mode
        assertTrue(call(audioModule, "start", mapOf("mode" to WireValue.Integer(1))).ok)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        val session = CallAudioSession.get(context)
        assertTrue(session.active)
        val firstRoute = session.currentRoute()
        assertTrue(firstRoute in 1..4)

        assertTrue(call(audioModule, "setSpeaker", mapOf("enabled" to WireValue.Flag(true))).ok)
        waitUntil(3_000) { session.currentRoute() == CallAudioSession.ROUTE_SPEAKER }
        assertFalse(session.isProximityHeld())
        val routeEvent = call(audioModule, "next", emptyMap())
        assertTrue(routeEvent.ok)
        assertTrue((routeEvent.values["route"] as WireValue.Integer).value in 1L..4L)

        assertTrue(call(audioModule, "setRingback", mapOf("enabled" to WireValue.Flag(true))).ok)
        assertTrue(session.isRingbackPlaying())
        assertTrue(call(audioModule, "setRingback", mapOf("enabled" to WireValue.Flag(false))).ok)
        assertFalse(session.isRingbackPlaying())
        assertTrue(call(audioModule, "setRingtone", mapOf("enabled" to WireValue.Flag(true))).ok)
        assertTrue(call(audioModule, "setRingtone", mapOf("enabled" to WireValue.Flag(false))).ok)
        assertFalse(session.isRingtonePlaying())

        assertTrue(call(audioModule, "setMicrophoneMute", mapOf("muted" to WireValue.Flag(true))).ok)
        assertTrue(audio.isMicrophoneMute)
        assertTrue(call(audioModule, "stop", emptyMap()).ok)
        assertFalse(session.active)
        assertFalse(audio.isMicrophoneMute)
        assertEquals(before, audio.mode)
        assertFalse(call(audioModule, "unknown", emptyMap()).ok)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(block())
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Condition not met within ${timeoutMillis}ms", condition())
    }
}
