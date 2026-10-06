import Foundation
import PamNative
import UIKit
import WebRTC

/// PAM module `webrtc`: peer connections addressed by session id.
public final class WebRtcModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            if method.hasPrefix("media") {
                try media(method, values, completion)
                return
            }
            let id = try values.text("sessionId")
            switch method {
            case "create":
                _ = try WebRtcRuntime.create(RtcSessionConfig(
                    id: id,
                    iceServersJson: values.text("iceServersJson", "[]"),
                    video: values.flag("video"),
                    facing: Int(values.integer("facing", 1)),
                    width: Int(min(max(values.integer("width", 1_280), 160), 3_840)),
                    height: Int(min(max(values.integer("height", 720), 120), 2_160)),
                    fps: Int(min(max(values.integer("fps", 30), 5), 60)),
                    relayOnly: values.flag("relayOnly"),
                    localMediaId: values.text("localMediaId", "")
                ))
                succeed(completion)
            case "next":
                guard let session = WebRtcRuntime.session(id) else { throw RtcError("RTC session \(id) not found") }
                session.events.next(completion)
            case "startLocal":
                try WebRtcRuntime.sessionOrThrow(id).startLocal(completion)
            case "createOffer":
                try WebRtcRuntime.sessionOrThrow(id).createOffer(iceRestart: values.flag("iceRestart"), completion)
            case "createAnswer":
                try WebRtcRuntime.sessionOrThrow(id).createAnswer(completion)
            case "setRemoteDescription":
                try WebRtcRuntime.sessionOrThrow(id).setRemote(
                    type: values.integer("type", 0),
                    sdp: try values.text("sdp"),
                    completion
                )
            case "addIceCandidate":
                try WebRtcRuntime.sessionOrThrow(id).addCandidate(
                    mid: values.text("sdpMid", ""),
                    line: Int32(clamping: values.integer("sdpMLineIndex", 0)),
                    candidate: try values.text("candidate")
                ) { added in
                    succeed(completion, ["added": .flag(added)])
                }
            case "setMicrophone":
                try WebRtcRuntime.sessionOrThrow(id).setMicrophone(values.flag("enabled", true))
                succeed(completion)
            case "setCamera":
                try WebRtcRuntime.sessionOrThrow(id).setCamera(values.flag("enabled", true), completion)
            case "switchCamera":
                try WebRtcRuntime.sessionOrThrow(id).switchCamera(completion)
            case "stats":
                try WebRtcRuntime.sessionOrThrow(id).stats(completion)
            case "close":
                WebRtcRuntime.close(id)
                succeed(completion)
            default:
                throw RtcError("Unknown WebRTC method \(method)")
            }
        } catch {
            fail(completion, error.localizedDescription)
        }
    }

    /// Shared local media streams (`LocalMedia` in PHP) addressed by media id.
    private func media(_ method: String, _ values: [String: WireValue], _ completion: @escaping ModuleCompletion) throws {
        let id = try values.text("mediaId")
        switch method {
        case "mediaCreate":
            _ = try WebRtcRuntime.createMedia(RtcMediaConfig(
                id: id,
                video: values.flag("video"),
                facing: Int(values.integer("facing", 1)),
                width: Int(min(max(values.integer("width", 1_280), 160), 3_840)),
                height: Int(min(max(values.integer("height", 720), 120), 2_160)),
                fps: Int(min(max(values.integer("fps", 30), 5), 60))
            ))
            succeed(completion)
        case "mediaStart":
            try WebRtcRuntime.mediaOrThrow(id).start(completion)
        case "mediaSetMicrophone":
            let media = try WebRtcRuntime.mediaOrThrow(id)
            media.setMicrophone(values.flag("enabled", true))
            succeed(completion, media.snapshot())
        case "mediaSetCamera":
            try WebRtcRuntime.mediaOrThrow(id).setCamera(values.flag("enabled", true), completion)
        case "mediaSwitchCamera":
            try WebRtcRuntime.mediaOrThrow(id).switchCamera(completion)
        case "mediaClose":
            WebRtcRuntime.closeMedia(id)
            succeed(completion)
        default:
            throw RtcError("Unknown WebRTC method \(method)")
        }
    }

    public func close() {
        WebRtcRuntime.closeAll()
    }
}

/// PAM view `webrtc.video`: renders a session's local or remote track with Metal.
public final class RtcVideoViewFactory: NativeViewFactory, @unchecked Sendable {
    static let fitCover: Int64 = 1
    static let fitContain: Int64 = 2

    public init() {}

    public func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        RtcVideoContainer(frame: .zero)
    }

    public func update(view: UIView, properties: [String: WireValue]) {
        guard let container = view as? RtcVideoContainer else { return }
        container.configure(
            fit: properties.integer("fit", Self.fitCover),
            mirror: properties.flag("mirror")
        )
        container.attach(
            sessionId: properties.text("sessionId", ""),
            track: Int(properties.integer("track", Int64(WebRtcRuntime.trackRemote)))
        )
    }

    public func release(view: UIView) {
        (view as? RtcVideoContainer)?.dispose()
    }
}

/// Black host around an RTCMTLVideoView so video composes with overlays.
final class RtcVideoContainer: UIView {
    let renderer = RTCMTLVideoView(frame: .zero)
    private var unbind: (() -> Void)?
    private var bound: RTCVideoTrack?
    private var sessionId = ""
    private var track = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        renderer.videoContentMode = .scaleAspectFill
        renderer.frame = bounds
        renderer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(renderer)
    }

    required init?(coder: NSCoder) { nil }

    func configure(fit: Int64, mirror: Bool) {
        renderer.videoContentMode = fit == RtcVideoViewFactory.fitContain ? .scaleAspectFit : .scaleAspectFill
        renderer.transform = mirror ? CGAffineTransform(scaleX: -1, y: 1) : .identity
    }

    func attach(sessionId: String, track: Int) {
        guard sessionId != self.sessionId || track != self.track else { return }
        unbind?()
        bind(nil)
        self.sessionId = sessionId
        self.track = track
        unbind = sessionId.isEmpty ? nil : WebRtcRuntime.observe(sessionId, track: track) { [weak self] value in
            self?.bind(value)
        }
    }

    private func bind(_ track: RTCVideoTrack?) {
        guard bound !== track else { return }
        bound?.remove(renderer)
        bound = track
        track?.add(renderer)
        renderer.isHidden = track == nil
    }

    func dispose() {
        unbind?()
        unbind = nil
        bind(nil)
    }
}
