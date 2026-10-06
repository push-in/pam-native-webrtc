import AVFoundation
import Foundation
import PamNative
import WebRTC

struct RtcSessionConfig {
    let id: String
    let iceServersJson: String
    let video: Bool
    let facing: Int
    let width: Int
    let height: Int
    let fps: Int
    let relayOnly: Bool
    /// Shared `RtcLocalMedia` feeding this peer; empty opens a capture for this session only.
    var localMediaId: String = ""
}

/// Process-wide factory, sessions and renderer bindings (Android WebRtcRuntime).
public enum WebRtcRuntime {
    static let trackLocal = 1
    static let trackRemote = 2
    private static let lock = NSLock()
    private static var sessions: [String: RtcSession] = [:]
    private static var medias: [String: RtcLocalMedia] = [:]
    private static var observers: [String: [UUID: (RTCVideoTrack?) -> Void]] = [:]
    private static let sessionPattern = "^[A-Za-z0-9_-]{1,128}$"

    static let factory: RTCPeerConnectionFactory = {
        _ = RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    static func create(_ config: RtcSessionConfig) throws -> RtcSession {
        guard config.id.range(of: sessionPattern, options: .regularExpression) != nil else {
            throw RtcError("Invalid session id")
        }
        guard config.localMediaId.isEmpty || config.localMediaId.range(of: sessionPattern, options: .regularExpression) != nil else {
            throw RtcError("Invalid local media id")
        }
        lock.lock()
        if medias[config.id] != nil {
            lock.unlock()
            throw RtcError("Id \(config.id) belongs to a local media stream")
        }
        let previous = sessions.removeValue(forKey: config.id)
        lock.unlock()
        previous?.close()
        let session = try RtcSession(config: config, factory: factory)
        lock.lock()
        sessions[config.id] = session
        lock.unlock()
        return session
    }

    public static func session(_ id: String) -> RtcSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[id]
    }

    static func sessionOrThrow(_ id: String) throws -> RtcSession {
        guard let session = session(id) else { throw RtcError("RTC session \(id) not found") }
        return session
    }

    public static func sessionIds() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(sessions.keys)
    }

    public static func close(_ id: String) {
        lock.lock()
        let session = sessions.removeValue(forKey: id)
        lock.unlock()
        session?.close()
    }

    public static func closeAll() {
        sessionIds().forEach(close)
        mediaIds().forEach(closeMedia)
    }

    static func createMedia(_ config: RtcMediaConfig) throws -> RtcLocalMedia {
        guard config.id.range(of: sessionPattern, options: .regularExpression) != nil else {
            throw RtcError("Invalid local media id")
        }
        lock.lock()
        if sessions[config.id] != nil {
            lock.unlock()
            throw RtcError("Id \(config.id) belongs to a peer connection")
        }
        let previous = medias.removeValue(forKey: config.id)
        lock.unlock()
        previous?.close()
        let media = RtcLocalMedia(config: config, factory: factory)
        lock.lock()
        medias[config.id] = media
        lock.unlock()
        return media
    }

    public static func media(_ id: String) -> RtcLocalMedia? {
        lock.lock()
        defer { lock.unlock() }
        return medias[id]
    }

    static func mediaOrThrow(_ id: String) throws -> RtcLocalMedia {
        guard let media = media(id) else { throw RtcError("Local media \(id) not found") }
        return media
    }

    public static func mediaIds() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return Set(medias.keys)
    }

    /// Closes a shared stream: attached peers stop sending it, then the capture is released.
    public static func closeMedia(_ id: String) {
        lock.lock()
        let media = medias.removeValue(forKey: id)
        lock.unlock()
        media?.close()
    }

    /// Mutes or unmutes every local microphone (CallAudio `setMicrophoneMute`).
    static func setAllMicrophones(enabled: Bool) {
        lock.lock()
        let all = Array(sessions.values)
        let streams = Array(medias.values)
        lock.unlock()
        all.forEach { $0.setMicrophone(enabled) }
        streams.forEach { $0.setMicrophone(enabled) }
    }

    /// Observes a session track (or a local media preview, by media id and `trackLocal`) on the main thread, immediately and on every change.
    static func observe(_ sessionId: String, track: Int, observer: @escaping (RTCVideoTrack?) -> Void) -> () -> Void {
        let key = key(sessionId, track)
        let token = UUID()
        lock.lock()
        observers[key, default: [:]][token] = observer
        let stream = track == trackLocal ? medias[sessionId] : nil
        let session = sessions[sessionId]
        lock.unlock()
        let current = session?.videoTrack(track) ?? stream?.videoTrack
        onMain { observer(current) }
        return {
            lock.lock()
            observers[key]?[token] = nil
            lock.unlock()
        }
    }

    static func publish(_ sessionId: String, track: Int, value: RTCVideoTrack?, wait: Bool = false) {
        lock.lock()
        let targets = Array((observers[key(sessionId, track)] ?? [:]).values)
        lock.unlock()
        guard !targets.isEmpty else { return }
        let deliver = { targets.forEach { $0(value) } }
        if Thread.isMainThread {
            deliver()
        } else if wait {
            DispatchQueue.main.sync(execute: deliver)
        } else {
            DispatchQueue.main.async(execute: deliver)
        }
    }

    private static func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    private static func key(_ sessionId: String, _ track: Int) -> String { "\(sessionId)#\(track)" }
}

/// One RTCPeerConnection with its local capture pipeline and event channel.
public final class RtcSession: NSObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    static let eventIceCandidate: Int64 = 1
    static let eventConnectionState: Int64 = 2
    static let eventRemoteTrack: Int64 = 3
    static let eventRenegotiationNeeded: Int64 = 4
    static let eventLocalReady: Int64 = 5
    static let eventFailure: Int64 = 6
    static let sdpOffer: Int64 = 1
    static let sdpAnswer: Int64 = 2
    static let facingFront: Int64 = 1
    static let facingBack: Int64 = 2

    public let id: String
    let events = EventChannel()
    private let config: RtcSessionConfig
    private let factory: RTCPeerConnectionFactory
    private let queue: DispatchQueue
    private var peer: RTCPeerConnection!
    private var capturer: RTCCameraVideoCapturer?
    private var videoSource: RTCVideoSource?
    private var localAudio: RTCAudioTrack?
    private var capturing = false
    private var front: Bool
    private var closed = false
    private var sharedAudioSender: RTCRtpSender?
    private var sharedVideoSender: RTCRtpSender?
    /// Shared stream this peer sends, or nil when it owns its capture (1:1 calls).
    private(set) var localMedia: RtcLocalMedia?
    private(set) var localVideo: RTCVideoTrack?
    private(set) var remoteVideo: RTCVideoTrack?
    private(set) var connectionState: Int64 = 1

    init(config: RtcSessionConfig, factory: RTCPeerConnectionFactory) throws {
        id = config.id
        self.config = config
        self.factory = factory
        front = Int64(config.facing) != Self.facingBack
        queue = DispatchQueue(label: "PamRtc-\(config.id)", qos: .userInitiated)
        super.init()
        let configuration = RTCConfiguration()
        configuration.iceServers = try Self.parseIceServers(config.iceServersJson)
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherContinually
        configuration.bundlePolicy = .maxBundle
        configuration.rtcpMuxPolicy = .require
        if config.relayOnly { configuration.iceTransportPolicy = .relay }
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: ["DtlsSrtpKeyAgreement": kRTCMediaConstraintsValueTrue]
        )
        guard let connection = factory.peerConnection(with: configuration, constraints: constraints, delegate: self) else {
            throw RtcError("Could not create peer connection")
        }
        peer = connection
    }

    func videoTrack(_ track: Int) -> RTCVideoTrack? {
        track == WebRtcRuntime.trackLocal ? localVideo : remoteVideo
    }

    var hasLocalAudio: Bool { localAudio != nil || sharedAudioSender != nil }
    var isCapturing: Bool { localMedia?.capturing ?? capturing }
    var usesSharedMedia: Bool { !config.localMediaId.isEmpty }
    /// Tracks this peer currently sends from its shared stream (audio, video).
    var sharedSenderCount: Int { [sharedAudioSender, sharedVideoSender].compactMap { $0 }.count }

    func startLocal(_ completion: @escaping ModuleCompletion) {
        if usesSharedMedia {
            startShared(completion)
            return
        }
        execute(completion) { done in
            if self.localAudio == nil {
                let source = self.factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
                let track = self.factory.audioTrack(with: source, trackId: "audio-\(self.id)")
                self.peer.add(track, streamIds: ["stream-\(self.id)"])
                self.localAudio = track
            }
            guard self.config.video, self.localVideo == nil else {
                self.emit(Self.eventLocalReady)
                done(nil)
                return
            }
            guard let device = self.device(front: self.front) else { throw RtcError("No camera available") }
            self.front = device.position != .back
            let source = self.factory.videoSource()
            self.videoSource = source
            let capturer = RTCCameraVideoCapturer(delegate: source)
            self.capturer = capturer
            self.startCapture(capturer, device: device) { error in
                if let error {
                    self.emit(Self.eventFailure, ["message": .text(error.localizedDescription)])
                    done(error.localizedDescription)
                    return
                }
                self.capturing = true
                let track = self.factory.videoTrack(with: source, trackId: "video-\(self.id)")
                self.peer.add(track, streamIds: ["stream-\(self.id)"])
                self.localVideo = track
                WebRtcRuntime.publish(self.id, track: WebRtcRuntime.trackLocal, value: track)
                self.emit(Self.eventLocalReady)
                done(nil)
            }
        }
    }

    private func startShared(_ completion: @escaping ModuleCompletion) {
        execute(completion) { done in
            let media = try WebRtcRuntime.mediaOrThrow(self.config.localMediaId)
            self.localMedia = media
            try media.attach(self)
            media.ensureStarted { error in
                self.queue.async {
                    if let error {
                        if error.lowercased().contains("camera") {
                            self.emit(Self.eventFailure, ["message": .text(error)])
                        }
                        done(error)
                        return
                    }
                    self.attachSharedTracks(media)
                    self.emit(Self.eventLocalReady)
                    done(nil)
                }
            }
        }
    }

    /// Called by the shared stream when its tracks appear; idempotent.
    func syncLocalMedia() {
        queue.async {
            if let media = self.localMedia { self.attachSharedTracks(media) }
        }
    }

    /// Runs on the session queue.
    private func attachSharedTracks(_ media: RtcLocalMedia) {
        guard !closed, localMedia === media else { return }
        let streams = ["stream-\(media.id)"]
        if let audio = media.audioTrack, sharedAudioSender == nil {
            sharedAudioSender = peer.add(audio, streamIds: streams)
        }
        if let video = media.videoTrack, sharedVideoSender == nil {
            sharedVideoSender = peer.add(video, streamIds: streams)
            localVideo = video
            WebRtcRuntime.publish(id, track: WebRtcRuntime.trackLocal, value: video)
        }
    }

    /// The shared stream is closing: stop sending its tracks.
    func detachLocalMedia(_ media: RtcLocalMedia) {
        WebRtcRuntime.publish(id, track: WebRtcRuntime.trackLocal, value: nil, wait: !Thread.isMainThread)
        queue.async {
            guard self.localMedia === media else { return }
            if !self.closed {
                [self.sharedAudioSender, self.sharedVideoSender].compactMap { $0 }.forEach { _ = self.peer.removeTrack($0) }
            }
            self.sharedAudioSender = nil
            self.sharedVideoSender = nil
            self.localVideo = nil
            self.localMedia = nil
        }
    }

    func createOffer(iceRestart: Bool, _ completion: @escaping ModuleCompletion) {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: iceRestart ? [kRTCMediaConstraintsIceRestart: kRTCMediaConstraintsValueTrue] : nil,
            optionalConstraints: nil
        )
        execute(completion) { done in
            self.peer.offer(for: constraints) { description, error in
                self.applyLocal(description, error: error, completion: completion, done: done)
            }
        }
    }

    func createAnswer(_ completion: @escaping ModuleCompletion) {
        execute(completion) { done in
            self.peer.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { description, error in
                self.applyLocal(description, error: error, completion: completion, done: done)
            }
        }
    }

    func setRemote(type: Int64, sdp: String, _ completion: @escaping ModuleCompletion) {
        execute(completion) { done in
            let sdpType: RTCSdpType
            switch type {
            case Self.sdpOffer: sdpType = .offer
            case Self.sdpAnswer: sdpType = .answer
            default: throw RtcError("Unsupported SDP type")
            }
            self.peer.setRemoteDescription(RTCSessionDescription(type: sdpType, sdp: sdp)) { error in
                done(error.map { $0.localizedDescription })
            }
        }
    }

    func addCandidate(mid: String, line: Int32, candidate: String, completion: @escaping (Bool) -> Void) {
        guard !closed else {
            completion(false)
            return
        }
        peer.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: line, sdpMid: mid.isEmpty ? nil : mid)) { error in
            completion(error == nil)
        }
    }

    func setMicrophone(_ enabled: Bool) {
        localMedia?.setMicrophone(enabled)
        localAudio?.isEnabled = enabled
    }

    func setCamera(_ enabled: Bool, _ completion: @escaping ModuleCompletion) {
        if let localMedia {
            localMedia.setCamera(enabled, completion)
            return
        }
        execute(completion) { done in
            guard let capturer = self.capturer, enabled != self.capturing else {
                self.localVideo?.isEnabled = enabled
                done(nil)
                return
            }
            if enabled {
                guard let device = self.device(front: self.front) else { throw RtcError("No camera available") }
                self.startCapture(capturer, device: device) { error in
                    self.capturing = error == nil
                    self.localVideo?.isEnabled = true
                    done(error?.localizedDescription)
                }
            } else {
                capturer.stopCapture {
                    self.capturing = false
                    self.localVideo?.isEnabled = false
                    done(nil)
                }
            }
        }
    }

    func switchCamera(_ completion: @escaping ModuleCompletion) {
        if let localMedia {
            localMedia.switchCamera(completion)
            return
        }
        guard let capturer else {
            fail(completion, "No camera is capturing")
            return
        }
        queue.async {
            guard let device = self.device(front: !self.front) else {
                fail(completion, "Camera switch failed")
                return
            }
            capturer.stopCapture {
                self.startCapture(capturer, device: device) { error in
                    if let error {
                        fail(completion, error.localizedDescription)
                        return
                    }
                    self.front = device.position != .back
                    self.capturing = true
                    succeed(completion, ["facing": .integer(self.front ? Self.facingFront : Self.facingBack)])
                }
            }
        }
    }

    func stats(_ completion: @escaping ModuleCompletion) {
        guard !closed else {
            fail(completion, "Session closed")
            return
        }
        peer.statistics { report in
            succeed(completion, Self.summarize(report.statistics.values.map {
                RtcStatSample(id: $0.id, type: $0.type, values: $0.values)
            }))
        }
    }

    /// Same aggregation as Android: totals over RTP streams, nominated pair RTT.
    static func summarize(_ stats: [RtcStatSample]) -> [String: WireValue] {
        var bytesSent: Int64 = 0
        var bytesReceived: Int64 = 0
        var packetsLost: Int64 = 0
        var jitter = 0.0
        var fps = 0.0
        var rtt = 0.0
        var outgoing: Int64 = 0
        var localCandidateId = ""
        func number(_ values: [String: NSObject], _ key: String) -> NSNumber {
            (values[key] as? NSNumber) ?? 0
        }
        for stat in stats {
            switch stat.type {
            case "outbound-rtp":
                bytesSent += number(stat.values, "bytesSent").int64Value
            case "inbound-rtp":
                bytesReceived += number(stat.values, "bytesReceived").int64Value
                packetsLost += number(stat.values, "packetsLost").int64Value
                jitter = max(jitter, number(stat.values, "jitter").doubleValue)
                if (stat.values["kind"] as? String) == "video" {
                    fps = number(stat.values, "framesPerSecond").doubleValue
                }
            case "candidate-pair":
                if (stat.values["state"] as? String) == "succeeded", number(stat.values, "nominated").boolValue {
                    rtt = number(stat.values, "currentRoundTripTime").doubleValue
                    outgoing = number(stat.values, "availableOutgoingBitrate").int64Value
                    localCandidateId = (stat.values["localCandidateId"] as? String) ?? ""
                }
            default:
                break
            }
        }
        let relayed = stats.contains { $0.id == localCandidateId && ($0.values["candidateType"] as? String) == "relay" }
        return [
            "bytesSent": .integer(bytesSent),
            "bytesReceived": .integer(bytesReceived),
            "packetsLost": .integer(packetsLost),
            "roundTripTimeMs": .decimal(rtt * 1_000),
            "jitterMs": .decimal(jitter * 1_000),
            "inboundFramesPerSecond": .decimal(fps),
            "availableOutgoingBitrate": .integer(outgoing),
            "relayed": .flag(relayed),
        ]
    }

    func close() {
        guard !closed else { return }
        closed = true
        events.close()
        // A shared stream outlives its peers: only this peer's senders go away with it.
        localMedia?.detach(self)
        // Detach renderers before tracks are released.
        WebRtcRuntime.publish(id, track: WebRtcRuntime.trackLocal, value: nil, wait: !Thread.isMainThread)
        WebRtcRuntime.publish(id, track: WebRtcRuntime.trackRemote, value: nil, wait: !Thread.isMainThread)
        localVideo = nil
        remoteVideo = nil
        let capturer = self.capturer
        let peer = self.peer
        queue.async {
            capturer?.stopCapture()
            peer?.close()
        }
        self.capturer = nil
        capturing = false
    }

    // MARK: Internals

    private func applyLocal(
        _ description: RTCSessionDescription?,
        error: Error?,
        completion: @escaping ModuleCompletion,
        done: @escaping (String?) -> Void
    ) {
        guard let description else {
            done(error?.localizedDescription ?? "Could not create session description")
            return
        }
        peer.setLocalDescription(description) { error in
            if let error {
                done(error.localizedDescription)
                return
            }
            succeed(completion, [
                "sdp": .text(description.sdp),
                "type": .integer(description.type == .answer ? Self.sdpAnswer : Self.sdpOffer),
            ])
            done("")
        }
    }

    /// Runs [block] on the session queue; `done(nil)` succeeds with an empty
    /// map, `done("")` means the block already completed, any other string fails.
    private func execute(_ completion: @escaping ModuleCompletion, _ block: @escaping (@escaping (String?) -> Void) throws -> Void) {
        guard !closed else {
            fail(completion, "Session closed")
            return
        }
        queue.async {
            var finished = false
            let done: (String?) -> Void = { message in
                guard !finished else { return }
                finished = true
                switch message {
                case nil: succeed(completion)
                case ""?: break
                case let failure?: fail(completion, failure)
                }
            }
            do {
                try block(done)
            } catch {
                if (error as? RtcError)?.message.lowercased().contains("camera") == true {
                    self.emit(Self.eventFailure, ["message": .text(error.localizedDescription)])
                }
                done(error.localizedDescription)
            }
        }
    }

    private func device(front: Bool) -> AVCaptureDevice? {
        RtcCamera.device(front: front)
    }

    private func startCapture(
        _ capturer: RTCCameraVideoCapturer,
        device: AVCaptureDevice,
        completion: @escaping (Error?) -> Void
    ) {
        RtcCamera.start(capturer, device: device, width: config.width, height: config.height, fps: config.fps, completion: completion)
    }

    private func emit(_ kind: Int64, _ values: [String: WireValue] = [:]) {
        guard !closed else { return }
        var event = values
        event["kind"] = .integer(kind)
        events.offer(event)
    }

    static func parseIceServers(_ json: String) throws -> [RTCIceServer] {
        let source = json.trimmingCharacters(in: .whitespaces).isEmpty ? "[]" : json
        guard let array = try JSONSerialization.jsonObject(with: Data(source.utf8)) as? [[String: Any]] else {
            throw RtcError("Invalid ICE servers")
        }
        return try array.map { server in
            guard let urls = server["urls"] as? [String], !urls.isEmpty else { throw RtcError("ICE server urls are required") }
            let username = (server["username"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let credential = (server["credential"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return RTCIceServer(urlStrings: urls, username: username, credential: credential)
        }
    }

    // MARK: RTCPeerConnectionDelegate

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {
        emit(Self.eventRenegotiationNeeded)
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        emit(Self.eventIceCandidate, [
            "candidate": .text(candidate.sdp),
            "sdpMid": .text(candidate.sdpMid ?? ""),
            "sdpMLineIndex": .integer(Int64(candidate.sdpMLineIndex)),
        ])
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        connectionState = Self.state(newState)
        emit(Self.eventConnectionState, ["state": .integer(connectionState)])
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        guard let track = transceiver.receiver.track else { return }
        let video = track.kind == kRTCMediaStreamTrackKindVideo
        if video, let videoTrack = track as? RTCVideoTrack {
            remoteVideo = videoTrack
            WebRtcRuntime.publish(id, track: WebRtcRuntime.trackRemote, value: videoTrack)
        }
        emit(Self.eventRemoteTrack, ["track": .integer(video ? 2 : 1)])
    }

    static func state(_ state: RTCPeerConnectionState) -> Int64 {
        switch state {
        case .new: return 1
        case .connecting: return 2
        case .connected: return 3
        case .disconnected: return 4
        case .failed: return 5
        case .closed: return 6
        @unknown default: return 1
        }
    }
}

/// Plain copy of one RTCStatistics entry (testable without a connection).
struct RtcStatSample {
    let id: String
    let type: String
    let values: [String: NSObject]
}
