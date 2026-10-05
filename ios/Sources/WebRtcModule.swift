import AVFoundation
import Foundation
import PamNative
import UIKit

/// iOS peer connections are not shipped in 0.1; calls fail with a typed message instead of crashing.
public final class WebRtcModule: NativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        completion(.failure, Data("pam-native-webrtc 0.1 supports Android only; iOS peer connections are planned.".utf8))
    }
}

/// Call audio session on iOS through AVAudioSession (mode, speaker override, Bluetooth HFP).
public final class CallAudioModule: NativeModule, @unchecked Sendable {
    private var videoMode = false
    private var speaker = false
    private var bluetooth = true

    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            let session = AVAudioSession.sharedInstance()
            switch method {
            case "start":
                if case let .integer(mode)? = values["mode"] { videoMode = mode == 2 }
                speaker = videoMode
                try configure(session)
                try session.setActive(true)
            case "stop":
                try session.setActive(false, options: .notifyOthersOnDeactivation)
            case "setSpeaker":
                if case let .flag(enabled)? = values["enabled"] { speaker = enabled }
                try session.overrideOutputAudioPort(speaker ? .speaker : .none)
            case "setBluetooth":
                if case let .flag(enabled)? = values["enabled"] { bluetooth = enabled }
                try configure(session)
            case "setRingback", "setRingtone", "setProximity", "setMicrophoneMute":
                if method == "setProximity", case let .flag(enabled)? = values["enabled"] {
                    DispatchQueue.main.async { UIDevice.current.isProximityMonitoringEnabled = enabled }
                }
            case "next":
                return
            default:
                throw CallAudioError.unknownMethod
            }
            completion(.success, try WireMap.encode([:]))
        } catch {
            completion(.failure, Data(String(describing: error).utf8))
        }
    }

    private func configure(_ session: AVAudioSession) throws {
        var options: AVAudioSession.CategoryOptions = [.allowBluetoothA2DP]
        if bluetooth { options.insert(.allowBluetooth) }
        if speaker { options.insert(.defaultToSpeaker) }
        try session.setCategory(.playAndRecord, mode: videoMode ? .videoChat : .voiceChat, options: options)
    }
}

private enum CallAudioError: Error {
    case unknownMethod
}

public final class RtcVideoViewFactory: NativeViewFactory, @unchecked Sendable {
    public init() {}
    public func create(context: AnyObject?, emit: @escaping (Data) -> Void) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        return view
    }
    public func update(view: UIView, properties: [String: WireValue]) {}
    public func release(view: UIView) {}
}
