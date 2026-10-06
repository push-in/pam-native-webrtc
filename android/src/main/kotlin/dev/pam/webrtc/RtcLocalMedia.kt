package dev.pam.webrtc

import android.content.Context
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

internal data class RtcMediaConfig(
    val id: String,
    val video: Boolean,
    val facing: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
)

/**
 * Shared local media stream: one microphone track and one camera capturer whose
 * tracks are added to every attached peer connection (group mesh calls).
 *
 * Toggles and camera switching act on the single capture, so they apply to all
 * peers at once. Peers attach and detach freely; the capture lives until the
 * stream itself is closed.
 */
class RtcLocalMedia internal constructor(
    private val context: Context,
    private val config: RtcMediaConfig,
    private val factory: PeerConnectionFactory,
) {
    val id: String = config.id
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "PamRtcMedia-${config.id}") }
    private val attached = CopyOnWriteArraySet<RtcSession>()
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    @Volatile
    private var front = config.facing != RtcSession.FACING_BACK

    @Volatile
    private var capturersOpened = 0

    @Volatile
    var audioTrack: AudioTrack? = null
        private set

    @Volatile
    var videoTrack: VideoTrack? = null
        private set

    @Volatile
    var microphoneEnabled = true
        private set

    @Volatile
    var cameraEnabled = true
        private set

    @Volatile
    private var capturing = false

    @Volatile
    private var closed = false

    val hasVideo: Boolean get() = config.video

    fun isCapturing(): Boolean = capturing

    fun isFrontFacing(): Boolean = front

    /** Camera capturers opened over the stream lifetime (one, however many peers attach). */
    fun capturerCount(): Int = capturersOpened

    fun attachedSessionIds(): Set<String> = attached.mapTo(linkedSetOf()) { it.id }

    /** Opens the microphone (and camera) once; idempotent and safe from any thread. */
    internal fun ensureStarted() {
        synchronized(lock) {
            check(!closed) { "Local media closed" }
            if (audioTrack == null) {
                audioSource = factory.createAudioSource(MediaConstraints())
                audioTrack = factory.createAudioTrack("audio-$id", audioSource).also { it.setEnabled(microphoneEnabled) }
            }
            if (config.video && videoTrack == null) {
                val enumerator = Camera2Enumerator(context)
                val names = enumerator.deviceNames
                val wanted = names.firstOrNull { enumerator.isFrontFacing(it) == front } ?: names.firstOrNull()
                    ?: throw IllegalStateException("No camera available")
                front = enumerator.isFrontFacing(wanted)
                val source = factory.createVideoSource(false).also { videoSource = it }
                val helper = SurfaceTextureHelper.create("PamRtcMediaCapture-$id", WebRtcRuntime.egl.eglBaseContext)
                    .also { textureHelper = it }
                capturer = (enumerator.createCapturer(wanted, null) ?: throw IllegalStateException("Could not open camera")).also {
                    it.initialize(helper, context, source.capturerObserver)
                    capturersOpened++
                    if (cameraEnabled) {
                        it.startCapture(config.width, config.height, config.fps)
                        capturing = true
                    }
                }
                videoTrack = factory.createVideoTrack("video-$id", source).also { it.setEnabled(cameraEnabled) }
                WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, videoTrack)
            }
        }
        attached.forEach { it.syncLocalMedia() }
    }

    internal fun start(completion: ModuleCompletion) = execute(completion) {
        ensureStarted()
        completion.success(snapshot())
    }

    /** Adds [session] to the peers fed by this stream and gives it the current tracks. */
    internal fun attach(session: RtcSession) {
        check(!closed) { "Local media closed" }
        attached.add(session)
    }

    internal fun detach(session: RtcSession) {
        attached.remove(session)
    }

    internal fun setMicrophone(enabled: Boolean) {
        microphoneEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    internal fun setCamera(enabled: Boolean, completion: ModuleCompletion) = execute(completion) {
        synchronized(lock) {
            cameraEnabled = enabled
            val camera = capturer
            if (camera != null && enabled != capturing) {
                if (enabled) camera.startCapture(config.width, config.height, config.fps) else camera.stopCapture()
                capturing = enabled
            }
            videoTrack?.setEnabled(enabled)
        }
        completion.success(snapshot())
    }

    internal fun switchCamera(completion: ModuleCompletion) {
        val camera = capturer ?: return completion.failure("No camera is capturing")
        if (!capturing) return completion.failure("Camera is off")
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                front = isFrontCamera
                completion.success(snapshot())
            }

            override fun onCameraSwitchError(errorDescription: String?) =
                completion.failure(errorDescription ?: "Camera switch failed")
        })
    }

    internal fun snapshot(): Map<String, WireValue> = mapOf(
        "facing" to WireValue.Integer(if (front) RtcSession.FACING_FRONT.toLong() else RtcSession.FACING_BACK.toLong()),
        "microphone" to WireValue.Flag(microphoneEnabled),
        "camera" to WireValue.Flag(cameraEnabled),
        "video" to WireValue.Flag(videoTrack != null),
        "peers" to WireValue.Integer(attached.size.toLong()),
    )

    /** Detaches every peer (their senders stop), then releases the single capture. */
    internal fun close() {
        if (closed) return
        closed = true
        val sessions = attached.toList()
        attached.clear()
        sessions.forEach { it.detachLocalMedia(this) }
        WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, null, wait = true)
        executor.execute {
            synchronized(lock) {
                runCatching { capturer?.stopCapture() }
                runCatching { capturer?.dispose() }
                runCatching { videoTrack?.dispose() }
                runCatching { audioTrack?.dispose() }
                runCatching { videoSource?.dispose() }
                runCatching { audioSource?.dispose() }
                runCatching { textureHelper?.dispose() }
                capturer = null
                videoTrack = null
                audioTrack = null
                capturing = false
            }
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun execute(completion: ModuleCompletion, block: () -> Unit) {
        if (closed) return completion.failure("Local media closed")
        runCatching {
            executor.execute {
                runCatching(block).onFailure { completion.failure(it.message ?: it.javaClass.simpleName) }
            }
        }.onFailure { completion.failure("Local media closed") }
    }
}
