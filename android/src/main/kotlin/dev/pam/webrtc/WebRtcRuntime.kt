package dev.pam.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** Process-wide WebRTC factory, shared EGL context, sessions and renderer bindings. */
object WebRtcRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, RtcSession>()
    private val medias = ConcurrentHashMap<String, RtcLocalMedia>()
    private val observers = ConcurrentHashMap<String, CopyOnWriteArraySet<(VideoTrack?) -> Unit>>()

    val egl: EglBase by lazy { EglBase.create() }

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    internal fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        val application = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(application)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val audio = JavaAudioDeviceModule.builder(application)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also {
                factory = it
                audio.release()
            }
    }

    internal fun create(context: Context, config: RtcSessionConfig): RtcSession {
        require(config.id.matches(SESSION_ID)) { "Invalid session id" }
        require(!medias.containsKey(config.id)) { "Id ${config.id} belongs to a local media stream" }
        require(config.localMediaId.isEmpty() || config.localMediaId.matches(SESSION_ID)) { "Invalid local media id" }
        sessions.remove(config.id)?.close()
        return RtcSession(context.applicationContext, config, factory(context)).also {
            sessions[config.id] = it
        }
    }

    fun session(id: String): RtcSession? = sessions[id]

    internal fun sessionOrThrow(id: String): RtcSession =
        sessions[id] ?: throw IllegalStateException("RTC session $id not found")

    fun sessionIds(): Set<String> = sessions.keys.toSet()

    fun close(id: String) {
        sessions.remove(id)?.close()
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
        medias.keys.toList().forEach(::closeMedia)
    }

    internal fun createMedia(context: Context, config: RtcMediaConfig): RtcLocalMedia {
        require(config.id.matches(SESSION_ID)) { "Invalid local media id" }
        require(!sessions.containsKey(config.id)) { "Id ${config.id} belongs to a peer connection" }
        medias.remove(config.id)?.close()
        return RtcLocalMedia(context.applicationContext, config, factory(context)).also {
            medias[config.id] = it
        }
    }

    fun media(id: String): RtcLocalMedia? = medias[id]

    internal fun mediaOrThrow(id: String): RtcLocalMedia =
        medias[id] ?: throw IllegalStateException("Local media $id not found")

    fun mediaIds(): Set<String> = medias.keys.toSet()

    /** Closes a shared stream: attached peers stop sending it, then the capture is released. */
    fun closeMedia(id: String) {
        medias.remove(id)?.close()
    }

    /** Observes a session track (or a local media preview, by media id and [TRACK_LOCAL]); the callback runs on the main thread, immediately and on every change. */
    fun observe(sessionId: String, track: Int, observer: (VideoTrack?) -> Unit): AutoCloseable {
        val key = key(sessionId, track)
        observers.getOrPut(key) { CopyOnWriteArraySet() }.add(observer)
        val current = sessions[sessionId]?.videoTrack(track)
            ?: medias[sessionId]?.takeIf { track == TRACK_LOCAL }?.videoTrack
        runOnMain { observer(current) }
        return AutoCloseable { observers[key]?.remove(observer) }
    }

    internal fun publish(sessionId: String, track: Int, value: VideoTrack?, wait: Boolean = false) {
        val targets = observers[key(sessionId, track)]?.toList().orEmpty()
        if (targets.isEmpty()) return
        val deliver = Runnable { targets.forEach { runCatching { it(value) } } }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver.run()
        } else if (wait) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                deliver.run()
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
        } else {
            mainHandler.post(deliver)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun key(sessionId: String, track: Int) = "$sessionId#$track"

    const val TRACK_LOCAL = 1
    const val TRACK_REMOTE = 2
    private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
}
