package dev.pam.webrtc

import android.content.Context
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

internal data class RtcSessionConfig(
    val id: String,
    val iceServersJson: String,
    val video: Boolean,
    val facing: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val relayOnly: Boolean,
    /** Shared [RtcLocalMedia] feeding this peer; empty opens a capture for this session only. */
    val localMediaId: String = "",
)

/** One native RTCPeerConnection with its local capture pipeline and event channel. */
class RtcSession internal constructor(
    private val context: Context,
    private val config: RtcSessionConfig,
    private val factory: PeerConnectionFactory,
) : PeerConnection.Observer {
    val id: String = config.id
    internal val events = EventChannel()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "PamRtc-${config.id}") }
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var capturing = false
    private var front = config.facing != FACING_BACK
    @Volatile
    private var sharedAudioSender: RtpSender? = null

    @Volatile
    private var sharedVideoSender: RtpSender? = null

    /** Shared stream this peer sends, or null when it owns its capture (1:1 calls). */
    @Volatile
    var localMedia: RtcLocalMedia? = null
        private set

    val usesSharedMedia: Boolean get() = config.localMediaId.isNotEmpty()

    @Volatile
    var localVideo: VideoTrack? = null
        private set

    @Volatile
    var remoteVideo: VideoTrack? = null
        private set

    @Volatile
    var connectionState: Int = STATE_NEW
        private set

    @Volatile
    private var closed = false
    private val peer: PeerConnection

    init {
        val configuration = PeerConnection.RTCConfiguration(parseIceServers(config.iceServersJson)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            if (config.relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
        peer = factory.createPeerConnection(configuration, this)
            ?: throw IllegalStateException("Could not create peer connection")
    }

    fun videoTrack(track: Int): VideoTrack? =
        if (track == WebRtcRuntime.TRACK_LOCAL) localVideo else remoteVideo

    fun hasLocalAudio(): Boolean = localAudio != null || sharedAudioSender != null

    fun isCapturing(): Boolean = localMedia?.isCapturing() ?: capturing

    /** Tracks this peer currently sends from its shared stream (audio, video). */
    fun sharedSenderCount(): Int = listOfNotNull(sharedAudioSender, sharedVideoSender).size

    internal fun startLocal(completion: ModuleCompletion) {
        if (usesSharedMedia) return startShared(completion)
        startOwn(completion)
    }

    private fun startShared(completion: ModuleCompletion) = execute(completion) {
        val media = WebRtcRuntime.mediaOrThrow(config.localMediaId)
        localMedia = media
        media.attach(this)
        media.ensureStarted()
        attachSharedTracks(media)
        emit(EVENT_LOCAL_READY)
        completion.success()
    }

    /** Called by the shared stream when its tracks appear; idempotent. */
    internal fun syncLocalMedia() {
        val media = localMedia ?: return
        runCatching { executor.execute { attachSharedTracks(media) } }
    }

    private fun attachSharedTracks(media: RtcLocalMedia) {
        if (closed || localMedia !== media) return
        val streams = listOf("stream-${media.id}")
        val audio = media.audioTrack
        if (audio != null && sharedAudioSender == null) sharedAudioSender = peer.addTrack(audio, streams)
        val video = media.videoTrack
        if (video != null && sharedVideoSender == null) {
            sharedVideoSender = peer.addTrack(video, streams)
            localVideo = video
            WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, video)
        }
    }

    /** The shared stream is closing: stop sending its tracks (blocks until done). */
    internal fun detachLocalMedia(media: RtcLocalMedia) {
        if (localMedia !== media) return
        WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, null, wait = true)
        val work = Runnable {
            if (!closed) {
                listOfNotNull(sharedAudioSender, sharedVideoSender).forEach { runCatching { peer.removeTrack(it) } }
            }
            sharedAudioSender = null
            sharedVideoSender = null
            localVideo = null
            localMedia = null
        }
        runCatching { executor.submit(work).get(3, TimeUnit.SECONDS) }.onFailure { work.run() }
    }

    private fun startOwn(completion: ModuleCompletion) = execute(completion) {
        if (localAudio == null) {
            audioSource = factory.createAudioSource(MediaConstraints())
            localAudio = factory.createAudioTrack("audio-$id", audioSource).also {
                peer.addTrack(it, listOf("stream-$id"))
            }
        }
        if (config.video && localVideo == null) {
            val enumerator = Camera2Enumerator(context)
            val names = enumerator.deviceNames
            val wanted = names.firstOrNull { enumerator.isFrontFacing(it) == front } ?: names.firstOrNull()
                ?: throw IllegalStateException("No camera available")
            front = enumerator.isFrontFacing(wanted)
            val source = factory.createVideoSource(false).also { videoSource = it }
            val helper = SurfaceTextureHelper.create("PamRtcCapture-$id", WebRtcRuntime.egl.eglBaseContext)
                .also { textureHelper = it }
            capturer = (enumerator.createCapturer(wanted, null) ?: throw IllegalStateException("Could not open camera")).also {
                it.initialize(helper, context, source.capturerObserver)
                it.startCapture(config.width, config.height, config.fps)
            }
            capturing = true
            localVideo = factory.createVideoTrack("video-$id", source).also {
                peer.addTrack(it, listOf("stream-$id"))
            }
            WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, localVideo)
        }
        emit(EVENT_LOCAL_READY)
        completion.success()
    }

    internal fun createOffer(iceRestart: Boolean, completion: ModuleCompletion) = execute(completion) {
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peer.createOffer(localDescriptionObserver(completion), constraints)
    }

    internal fun createAnswer(completion: ModuleCompletion) = execute(completion) {
        peer.createAnswer(localDescriptionObserver(completion), MediaConstraints())
    }

    internal fun setRemote(type: Int, sdp: String, completion: ModuleCompletion) = execute(completion) {
        val sdpType = when (type) {
            SDP_OFFER -> SessionDescription.Type.OFFER
            SDP_ANSWER -> SessionDescription.Type.ANSWER
            else -> throw IllegalArgumentException("Unsupported SDP type")
        }
        peer.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = completion.success()
            override fun onSetFailure(message: String?) = completion.failure(message ?: "setRemoteDescription failed")
        }, SessionDescription(sdpType, sdp))
    }

    internal fun addCandidate(mid: String, line: Int, candidate: String): Boolean =
        !closed && peer.addIceCandidate(IceCandidate(mid, line, candidate))

    internal fun setMicrophone(enabled: Boolean) {
        localMedia?.setMicrophone(enabled)
        localAudio?.setEnabled(enabled)
    }

    internal fun setCamera(enabled: Boolean, completion: ModuleCompletion) {
        val media = localMedia ?: return setOwnCamera(enabled, completion)
        media.setCamera(enabled, completion)
    }

    private fun setOwnCamera(enabled: Boolean, completion: ModuleCompletion) = execute(completion) {
        val camera = capturer
        if (camera != null && enabled != capturing) {
            if (enabled) camera.startCapture(config.width, config.height, config.fps) else camera.stopCapture()
            capturing = enabled
        }
        localVideo?.setEnabled(enabled)
        completion.success()
    }

    internal fun switchCamera(completion: ModuleCompletion) {
        localMedia?.let { return it.switchCamera(completion) }
        val camera = capturer ?: return completion.failure("No camera is capturing")
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                front = isFrontCamera
                completion.success(mapOf("facing" to WireValue.Integer(if (isFrontCamera) FACING_FRONT.toLong() else FACING_BACK.toLong())))
            }

            override fun onCameraSwitchError(errorDescription: String?) =
                completion.failure(errorDescription ?: "Camera switch failed")
        })
    }

    internal fun stats(completion: ModuleCompletion) {
        if (closed) return completion.failure("Session closed")
        peer.getStats { report ->
            var bytesSent = 0L
            var bytesReceived = 0L
            var packetsLost = 0L
            var jitter = 0.0
            var fps = 0.0
            var rtt = 0.0
            var outgoing = 0L
            var localCandidateId = ""
            val stats = report.statsMap.values
            stats.forEach { stat ->
                val members = stat.members
                when (stat.type) {
                    "outbound-rtp" -> bytesSent += members.number("bytesSent").toLong()
                    "inbound-rtp" -> {
                        bytesReceived += members.number("bytesReceived").toLong()
                        packetsLost += members.number("packetsLost").toLong()
                        jitter = maxOf(jitter, members.number("jitter").toDouble())
                        if (members["kind"] == "video") fps = members.number("framesPerSecond").toDouble()
                    }
                    "candidate-pair" -> if (members["state"] == "succeeded" && members["nominated"] == true) {
                        rtt = members.number("currentRoundTripTime").toDouble()
                        outgoing = members.number("availableOutgoingBitrate").toLong()
                        localCandidateId = members["localCandidateId"] as? String ?: ""
                    }
                }
            }
            val relayed = stats.any { it.id == localCandidateId && it.members["candidateType"] == "relay" }
            completion.success(
                mapOf(
                    "bytesSent" to WireValue.Integer(bytesSent),
                    "bytesReceived" to WireValue.Integer(bytesReceived),
                    "packetsLost" to WireValue.Integer(packetsLost),
                    "roundTripTimeMs" to WireValue.Decimal(rtt * 1000.0),
                    "jitterMs" to WireValue.Decimal(jitter * 1000.0),
                    "inboundFramesPerSecond" to WireValue.Decimal(fps),
                    "availableOutgoingBitrate" to WireValue.Integer(outgoing),
                    "relayed" to WireValue.Flag(relayed),
                ),
            )
        }
    }

    internal fun close() {
        if (closed) return
        closed = true
        events.close()
        // A shared stream outlives its peers: only this peer's senders go away with it.
        localMedia?.detach(this)
        // Detach renderers before tracks are disposed: removing a sink from a disposed track throws.
        WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_LOCAL, null, wait = true)
        WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_REMOTE, null, wait = true)
        localVideo = null
        remoteVideo = null
        executor.execute {
            runCatching { capturer?.stopCapture() }
            runCatching { peer.dispose() }
            runCatching { capturer?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { textureHelper?.dispose() }
            capturer = null
            capturing = false
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun localDescriptionObserver(completion: ModuleCompletion) = object : SimpleSdpObserver() {
        override fun onCreateSuccess(description: SessionDescription?) {
            val local = description ?: return completion.failure("Empty session description")
            peer.setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() = completion.success(
                    mapOf(
                        "sdp" to WireValue.Text(local.description),
                        "type" to WireValue.Integer(if (local.type == SessionDescription.Type.ANSWER) SDP_ANSWER.toLong() else SDP_OFFER.toLong()),
                    ),
                )

                override fun onSetFailure(message: String?) = completion.failure(message ?: "setLocalDescription failed")
            }, local)
        }

        override fun onCreateFailure(message: String?) = completion.failure(message ?: "Could not create session description")
    }

    private fun execute(completion: ModuleCompletion, block: () -> Unit) {
        if (closed) return completion.failure("Session closed")
        runCatching {
            executor.execute {
                runCatching(block).onFailure {
                    if (it is SecurityException || it.message?.contains("camera", ignoreCase = true) == true) {
                        emit(EVENT_FAILURE, mapOf("message" to WireValue.Text(it.message ?: "Media capture failed")))
                    }
                    completion.failure(it.message ?: it.javaClass.simpleName)
                }
            }
        }.onFailure { completion.failure("Session closed") }
    }

    private fun emit(kind: Int, values: Map<String, WireValue> = emptyMap()) {
        if (closed) return
        events.offer(linkedMapOf<String, WireValue>("kind" to WireValue.Integer(kind.toLong())).apply { putAll(values) })
    }

    override fun onIceCandidate(candidate: IceCandidate) = emit(
        EVENT_ICE_CANDIDATE,
        mapOf(
            "candidate" to WireValue.Text(candidate.sdp),
            "sdpMid" to WireValue.Text(candidate.sdpMid.orEmpty()),
            "sdpMLineIndex" to WireValue.Integer(candidate.sdpMLineIndex.toLong()),
        ),
    )

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        connectionState = when (newState) {
            PeerConnection.PeerConnectionState.NEW -> STATE_NEW
            PeerConnection.PeerConnectionState.CONNECTING -> 2
            PeerConnection.PeerConnectionState.CONNECTED -> 3
            PeerConnection.PeerConnectionState.DISCONNECTED -> 4
            PeerConnection.PeerConnectionState.FAILED -> 5
            PeerConnection.PeerConnectionState.CLOSED -> 6
        }
        emit(EVENT_CONNECTION_STATE, mapOf("state" to WireValue.Integer(connectionState.toLong())))
    }

    override fun onTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track() ?: return
        val video = track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
        if (video) {
            remoteVideo = track as VideoTrack
            WebRtcRuntime.publish(id, WebRtcRuntime.TRACK_REMOTE, remoteVideo)
        }
        emit(EVENT_REMOTE_TRACK, mapOf("track" to WireValue.Integer(if (video) 2 else 1)))
    }

    override fun onRenegotiationNeeded() = emit(EVENT_RENEGOTIATION_NEEDED)
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

    internal companion object {
        const val EVENT_ICE_CANDIDATE = 1
        const val EVENT_CONNECTION_STATE = 2
        const val EVENT_REMOTE_TRACK = 3
        const val EVENT_RENEGOTIATION_NEEDED = 4
        const val EVENT_LOCAL_READY = 5
        const val EVENT_FAILURE = 6
        const val STATE_NEW = 1
        const val SDP_OFFER = 1
        const val SDP_ANSWER = 2
        const val FACING_FRONT = 1
        const val FACING_BACK = 2

        fun parseIceServers(json: String): List<PeerConnection.IceServer> {
            val array = JSONArray(json.ifBlank { "[]" })
            return List(array.length()) { index ->
                val server = array.getJSONObject(index)
                val urls = server.getJSONArray("urls").let { list -> List(list.length()) { list.getString(it) } }
                PeerConnection.IceServer.builder(urls)
                    .setUsername(server.optString("username"))
                    .setPassword(server.optString("credential"))
                    .createIceServer()
            }
        }
    }
}

private fun Map<String, Any?>.number(key: String): Number = (get(key) as? Number) ?: 0

internal open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(message: String?) = Unit
    override fun onSetFailure(message: String?) = Unit
}
