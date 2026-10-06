import AVFoundation
import Foundation
import PamNative
import WebRTC

struct RtcMediaConfig {
    let id: String
    let video: Bool
    let facing: Int
    let width: Int
    let height: Int
    let fps: Int
}

/// Camera helpers shared by per-session capture and `RtcLocalMedia`.
enum RtcCamera {
    static func device(front: Bool) -> AVCaptureDevice? {
        let devices = RTCCameraVideoCapturer.captureDevices()
        return devices.first { ($0.position == .front) == front } ?? devices.first
    }

    static func start(
        _ capturer: RTCCameraVideoCapturer,
        device: AVCaptureDevice,
        width: Int,
        height: Int,
        fps: Int,
        completion: @escaping (Error?) -> Void
    ) {
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let target = width * height
        let format = formats.min { lhs, rhs in
            let a = CMVideoFormatDescriptionGetDimensions(lhs.formatDescription)
            let b = CMVideoFormatDescriptionGetDimensions(rhs.formatDescription)
            return abs(Int(a.width) * Int(a.height) - target) < abs(Int(b.width) * Int(b.height) - target)
        }
        guard let format else {
            completion(RtcError("Could not open camera"))
            return
        }
        let maxFps = format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30
        capturer.startCapture(with: device, format: format, fps: min(fps, Int(maxFps)), completionHandler: completion)
    }
}

/// Shared local media stream (Android RtcLocalMedia): one microphone track and
/// one camera capture whose tracks are added to every attached peer
/// connection. Toggles and camera switching act on the single capture, so they
/// apply to all peers; peers attach and detach while the capture keeps running.
public final class RtcLocalMedia: @unchecked Sendable {
    public let id: String
    private let config: RtcMediaConfig
    private let factory: RTCPeerConnectionFactory
    private let queue: DispatchQueue
    private let lock = NSLock()
    private var attached: [String: RtcSession] = [:]
    private var capturer: RTCCameraVideoCapturer?
    private var videoSource: RTCVideoSource?
    private var audio: RTCAudioTrack?
    private var video: RTCVideoTrack?
    private var started = false
    private var starting = false
    private var waiters: [(String?) -> Void] = []
    private var closed = false
    private(set) var microphoneEnabled = true
    private(set) var cameraEnabled = true
    private(set) var capturing = false
    private(set) var front: Bool
    /// Camera capturers opened over the stream lifetime (one, however many peers attach).
    private(set) var capturerCount = 0

    init(config: RtcMediaConfig, factory: RTCPeerConnectionFactory) {
        id = config.id
        self.config = config
        self.factory = factory
        front = Int64(config.facing) != RtcSession.facingBack
        queue = DispatchQueue(label: "PamRtcMedia-\(config.id)", qos: .userInitiated)
    }

    var hasVideo: Bool { config.video }

    var audioTrack: RTCAudioTrack? {
        lock.lock()
        defer { lock.unlock() }
        return audio
    }

    var videoTrack: RTCVideoTrack? {
        lock.lock()
        defer { lock.unlock() }
        return video
    }

    var attachedSessionIds: Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(attached.keys)
    }

    /// Opens the microphone (and camera) once; concurrent callers wait for the same start.
    func ensureStarted(_ done: @escaping (String?) -> Void) {
        queue.async {
            if self.closed {
                done("Local media closed")
                return
            }
            if self.started {
                done(nil)
                return
            }
            self.waiters.append(done)
            guard !self.starting else { return }
            self.starting = true
            if self.audioTrack == nil {
                let source = self.factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
                let track = self.factory.audioTrack(with: source, trackId: "audio-\(self.id)")
                track.isEnabled = self.microphoneEnabled
                self.lock.lock()
                self.audio = track
                self.lock.unlock()
            }
            guard self.config.video, self.videoTrack == nil else {
                self.finishStart(nil)
                return
            }
            guard let device = RtcCamera.device(front: self.front) else {
                self.finishStart("No camera available")
                return
            }
            self.front = device.position != .back
            let source = self.factory.videoSource()
            let capturer = RTCCameraVideoCapturer(delegate: source)
            self.videoSource = source
            self.capturer = capturer
            self.capturerCount += 1
            let track = self.factory.videoTrack(with: source, trackId: "video-\(self.id)")
            track.isEnabled = self.cameraEnabled
            self.lock.lock()
            self.video = track
            self.lock.unlock()
            WebRtcRuntime.publish(self.id, track: WebRtcRuntime.trackLocal, value: track)
            guard self.cameraEnabled else {
                self.finishStart(nil)
                return
            }
            RtcCamera.start(capturer, device: device, width: self.config.width, height: self.config.height, fps: self.config.fps) { error in
                self.queue.async {
                    self.capturing = error == nil
                    self.finishStart(error?.localizedDescription)
                }
            }
        }
    }

    /// Runs on the media queue.
    private func finishStart(_ error: String?) {
        starting = false
        started = error == nil
        let pending = waiters
        waiters = []
        pending.forEach { $0(error) }
        if error == nil {
            sessions().forEach { $0.syncLocalMedia() }
        }
    }

    func start(_ completion: @escaping ModuleCompletion) {
        ensureStarted { error in
            if let error {
                fail(completion, error)
            } else {
                succeed(completion, self.snapshot())
            }
        }
    }

    func attach(_ session: RtcSession) throws {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { throw RtcError("Local media closed") }
        attached[session.id] = session
    }

    func detach(_ session: RtcSession) {
        lock.lock()
        if attached[session.id] === session { attached[session.id] = nil }
        lock.unlock()
    }

    func setMicrophone(_ enabled: Bool) {
        lock.lock()
        microphoneEnabled = enabled
        let track = audio
        lock.unlock()
        track?.isEnabled = enabled
    }

    func setCamera(_ enabled: Bool, _ completion: @escaping ModuleCompletion) {
        queue.async {
            self.cameraEnabled = enabled
            guard let capturer = self.capturer, enabled != self.capturing else {
                self.videoTrack?.isEnabled = enabled
                succeed(completion, self.snapshot())
                return
            }
            if enabled {
                guard let device = RtcCamera.device(front: self.front) else {
                    fail(completion, "No camera available")
                    return
                }
                RtcCamera.start(capturer, device: device, width: self.config.width, height: self.config.height, fps: self.config.fps) { error in
                    self.queue.async {
                        self.capturing = error == nil
                        self.videoTrack?.isEnabled = true
                        if let error {
                            fail(completion, error.localizedDescription)
                        } else {
                            succeed(completion, self.snapshot())
                        }
                    }
                }
            } else {
                capturer.stopCapture {
                    self.queue.async {
                        self.capturing = false
                        self.videoTrack?.isEnabled = false
                        succeed(completion, self.snapshot())
                    }
                }
            }
        }
    }

    func switchCamera(_ completion: @escaping ModuleCompletion) {
        queue.async {
            guard let capturer = self.capturer else {
                fail(completion, "No camera is capturing")
                return
            }
            guard self.capturing else {
                fail(completion, "Camera is off")
                return
            }
            guard let device = RtcCamera.device(front: !self.front) else {
                fail(completion, "Camera switch failed")
                return
            }
            capturer.stopCapture {
                RtcCamera.start(capturer, device: device, width: self.config.width, height: self.config.height, fps: self.config.fps) { error in
                    self.queue.async {
                        if let error {
                            self.capturing = false
                            fail(completion, error.localizedDescription)
                            return
                        }
                        self.front = device.position != .back
                        self.capturing = true
                        succeed(completion, self.snapshot())
                    }
                }
            }
        }
    }

    func snapshot() -> [String: WireValue] {
        [
            "facing": .integer(front ? RtcSession.facingFront : RtcSession.facingBack),
            "microphone": .flag(microphoneEnabled),
            "camera": .flag(cameraEnabled),
            "video": .flag(videoTrack != nil),
            "peers": .integer(Int64(attachedSessionIds.count)),
        ]
    }

    /// Detaches every peer (their senders stop), then releases the single capture.
    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        let peers = Array(attached.values)
        attached = [:]
        lock.unlock()
        peers.forEach { $0.detachLocalMedia(self) }
        WebRtcRuntime.publish(id, track: WebRtcRuntime.trackLocal, value: nil, wait: !Thread.isMainThread)
        queue.async {
            self.capturer?.stopCapture()
            self.capturer = nil
            self.capturing = false
            self.lock.lock()
            self.audio = nil
            self.video = nil
            self.lock.unlock()
            self.videoSource = nil
            let pending = self.waiters
            self.waiters = []
            pending.forEach { $0("Local media closed") }
        }
    }

    private func sessions() -> [RtcSession] {
        lock.lock()
        defer { lock.unlock() }
        return Array(attached.values)
    }
}
