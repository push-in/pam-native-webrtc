import AudioToolbox
import AVFoundation
import Foundation
import PamNative
import UIKit

/// PAM module `call-audio` (InCallManager replacement) on AVAudioSession.
public final class CallAudioModule: NativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            let audio = CallAudioSession.shared
            switch method {
            case "start": try audio.start(mode: values.integer("mode", CallAudioSession.modeVoice))
            case "stop": audio.stop()
            case "setSpeaker": try audio.setSpeaker(values.flag("enabled", true))
            case "setBluetooth": try audio.setBluetooth(values.flag("enabled", true))
            case "setRingback": audio.setRingback(values.flag("enabled", true))
            case "setRingtone": audio.setRingtone(values.flag("enabled", true))
            case "setProximity": audio.setProximity(values.flag("enabled", true))
            case "setMicrophoneMute": audio.setMicrophoneMute(values.flag("muted", true))
            case "route":
                succeed(completion, ["route": .integer(audio.currentRoute())])
                return
            case "next":
                audio.events.next(completion)
                return
            default:
                throw RtcError("Unknown call audio method \(method)")
            }
            succeed(completion)
        } catch {
            fail(completion, error.localizedDescription)
        }
    }
}

/// Communication audio state machine (one per process). Routing priority:
/// Bluetooth HFP, wired headset (unless speaker was requested), speaker when
/// requested, receiver (earpiece).
final class CallAudioSession: @unchecked Sendable {
    static let shared = CallAudioSession()
    static let modeVoice: Int64 = 1
    static let modeVideo: Int64 = 2
    static let routeEarpiece: Int64 = 1
    static let routeSpeaker: Int64 = 2
    static let routeWired: Int64 = 3
    static let routeBluetooth: Int64 = 4

    let events = EventChannel(capacity: 16)
    private let lock = NSRecursiveLock()
    private(set) var active = false
    private(set) var mode = modeVoice
    private var speakerRequested = false
    private var preferBluetooth = true
    private var proximityEnabled = true
    private var lastRoute: Int64 = 0
    private var routeObserver: NSObjectProtocol?
    private var ringback: RingbackTone?
    private var ringtoneTimer: Timer?

    func start(mode requested: Int64) throws {
        lock.lock()
        defer { lock.unlock() }
        mode = requested == Self.modeVideo ? Self.modeVideo : Self.modeVoice
        speakerRequested = mode == Self.modeVideo
        if !active {
            routeObserver = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in self?.report(self?.currentRoute() ?? 0) }
        }
        active = true
        try applyRoute()
        try AVAudioSession.sharedInstance().setActive(true)
        report(currentRoute())
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        guard active else { return }
        active = false
        setRingback(false)
        setRingtone(false)
        updateProximity(0)
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        routeObserver = nil
        setMicrophoneMute(false)
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(.none)
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        lastRoute = 0
    }

    func setSpeaker(_ enabled: Bool) throws {
        lock.lock()
        defer { lock.unlock() }
        speakerRequested = enabled
        if active { try applyRoute() }
    }

    func setBluetooth(_ enabled: Bool) throws {
        lock.lock()
        defer { lock.unlock() }
        preferBluetooth = enabled
        if active { try applyRoute() }
    }

    func setProximity(_ enabled: Bool) {
        lock.lock()
        proximityEnabled = enabled
        lock.unlock()
        updateProximity(currentRoute())
    }

    /// Mutes local WebRTC tracks (and the app input on iOS 17+).
    func setMicrophoneMute(_ muted: Bool) {
        WebRtcRuntime.setAllMicrophones(enabled: !muted)
        if #available(iOS 17.0, *) {
            try? AVAudioApplication.shared.setInputMuted(muted)
        }
    }

    func setRingback(_ enabled: Bool) {
        if !enabled {
            ringback?.stop()
            ringback = nil
            return
        }
        guard ringback == nil else { return }
        ringback = RingbackTone()
        ringback?.start()
    }

    /// iOS exposes no system ringtone to apps: plays the alert sound with
    /// vibration every 2 s until stopped (CallKit owns the real ringtone).
    func setRingtone(_ enabled: Bool) {
        DispatchQueue.main.async {
            self.ringtoneTimer?.invalidate()
            self.ringtoneTimer = nil
            guard enabled else { return }
            let ring = {
                AudioServicesPlayAlertSound(SystemSoundID(1005))
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
            ring()
            self.ringtoneTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in ring() }
        }
    }

    var isRingbackPlaying: Bool { ringback != nil }
    var isRingtonePlaying: Bool { ringtoneTimer != nil }

    func currentRoute() -> Int64 {
        guard active else { return 0 }
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType)
        return Self.route(of: outputs)
    }

    static func route(of outputs: [AVAudioSession.Port]) -> Int64 {
        for port in outputs {
            switch port {
            case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE, .carAudio: return routeBluetooth
            case .headphones, .usbAudio, .headsetMic, .lineOut: return routeWired
            case .builtInSpeaker: return routeSpeaker
            case .builtInReceiver: return routeEarpiece
            default: continue
            }
        }
        return routeEarpiece
    }

    static func categoryOptions(speaker: Bool, bluetooth: Bool) -> AVAudioSession.CategoryOptions {
        var options: AVAudioSession.CategoryOptions = [.allowBluetoothA2DP]
        if bluetooth { options.insert(.allowBluetooth) }
        if speaker { options.insert(.defaultToSpeaker) }
        return options
    }

    private func applyRoute() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: mode == Self.modeVideo ? .videoChat : .voiceChat,
            options: Self.categoryOptions(speaker: speakerRequested, bluetooth: preferBluetooth)
        )
        let inputs = session.availableInputs ?? []
        let bluetoothInput = inputs.first { $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE }
        let wiredInput = inputs.first { $0.portType == .headsetMic || $0.portType == .usbAudio }
        if preferBluetooth, let bluetoothInput {
            try session.setPreferredInput(bluetoothInput)
            try session.overrideOutputAudioPort(.none)
        } else if !speakerRequested, let wiredInput {
            try session.setPreferredInput(wiredInput)
            try session.overrideOutputAudioPort(.none)
        } else {
            try session.setPreferredInput(inputs.first { $0.portType == .builtInMic })
            try session.overrideOutputAudioPort(speakerRequested ? .speaker : .none)
        }
        let route = currentRoute()
        updateProximity(route)
        report(route)
    }

    private func report(_ route: Int64) {
        guard route != 0, route != lastRoute else { return }
        lastRoute = route
        events.offer(["route": .integer(route)])
        updateProximity(route)
    }

    private func updateProximity(_ route: Int64) {
        let wanted = active && proximityEnabled && mode == Self.modeVoice && route == Self.routeEarpiece
        DispatchQueue.main.async { UIDevice.current.isProximityMonitoringEnabled = wanted }
    }
}

/// North-American ringback (440 + 480 Hz, 2 s on / 4 s off) on AVAudioEngine.
final class RingbackTone {
    private let engine = AVAudioEngine()
    private var phase = 0.0
    private var frame = 0.0

    func start() {
        let format = engine.outputNode.inputFormat(forBus: 0)
        let sampleRate = format.sampleRate > 0 ? format.sampleRate : 48_000
        let source = AVAudioSourceNode { [weak self] _, _, count, buffers -> OSStatus in
            guard let self else { return noErr }
            let list = UnsafeMutableAudioBufferListPointer(buffers)
            for index in 0..<Int(count) {
                let seconds = self.frame / sampleRate
                let on = seconds.truncatingRemainder(dividingBy: 6) < 2
                let value = on
                    ? Float(0.12 * (sin(2 * .pi * 440 * self.phase) + sin(2 * .pi * 480 * self.phase)))
                    : 0
                self.phase += 1 / sampleRate
                self.frame += 1
                for buffer in list {
                    buffer.mData?.assumingMemoryBound(to: Float.self)[index] = value
                }
            }
            return noErr
        }
        let mono = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: mono)
        try? engine.start()
    }

    func stop() {
        engine.stop()
    }
}
