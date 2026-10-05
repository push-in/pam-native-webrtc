import AVFoundation
import PamNative
import WebRTC
import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeWebrtc (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeWebrtc

/// XCTest mirror of WebRtcInstrumentedTest. Uncompiled — needs Mac validation
/// (add to the generated host's test target; run on a device for camera).
final class WebRtcTests: XCTestCase {
    private let module = WebRtcModule()
    private let audioModule = CallAudioModule()

    override func tearDown() {
        WebRtcRuntime.closeAll()
        CallAudioSession.shared.stop()
        super.tearDown()
    }

    private func call(_ target: NativeModule, _ method: String, _ values: [String: WireValue], timeout: TimeInterval = 15) -> (ok: Bool, values: [String: WireValue], message: String) {
        let done = expectation(description: method)
        var result: (Bool, [String: WireValue], String) = (false, [:], "timeout")
        target.invoke(method: method, payload: (try? WireMap.encode(values)) ?? Data()) { status, payload in
            result = (status == .success, (try? WireMap.decode(payload)) ?? [:], String(decoding: payload, as: UTF8.self))
            done.fulfill()
        }
        wait(for: [done], timeout: timeout)
        return result
    }

    private func rtc(_ method: String, _ session: String, _ values: [String: WireValue] = [:]) -> (ok: Bool, values: [String: WireValue], message: String) {
        var payload = values
        payload["sessionId"] = .text(session)
        return call(module, method, payload)
    }

    private func create(_ session: String, video: Bool) {
        let result = rtc("create", session, [
            "iceServersJson": .text("[]"), "video": .flag(video), "facing": .integer(1),
            "width": .integer(640), "height": .integer(480), "fps": .integer(15),
        ])
        XCTAssertTrue(result.ok, result.message)
    }

    /// Forwards events like a signaling server (background reader).
    private func pump(_ from: String, _ to: String, states: NSMutableArray, tracks: NSMutableArray) {
        func read() {
            var payload: [String: WireValue] = ["sessionId": .text(from)]
            module.invoke(method: "next", payload: (try? WireMap.encode(payload)) ?? Data()) { status, data in
                guard status == .success, let event = try? WireMap.decode(data) else { return }
                switch event["kind"] {
                case .integer(1)?:
                    payload = ["sessionId": .text(to), "candidate": event["candidate"]!, "sdpMid": event["sdpMid"]!, "sdpMLineIndex": event["sdpMLineIndex"]!]
                    self.module.invoke(method: "addIceCandidate", payload: (try? WireMap.encode(payload)) ?? Data()) { _, _ in }
                case .integer(2)?:
                    if case let .integer(state)? = event["state"] { states.add(state) }
                case .integer(3)?:
                    if case let .integer(track)? = event["track"] { tracks.add(track) }
                default:
                    break
                }
                read()
            }
        }
        read()
    }

    func testLoopbackCallConnectsRendersRemoteVideoAndReportsStats() throws {
        create("caller", video: true)
        create("callee", video: false)
        let callerStates = NSMutableArray()
        let calleeStates = NSMutableArray()
        let calleeTracks = NSMutableArray()
        pump("caller", "callee", states: callerStates, tracks: NSMutableArray())
        pump("callee", "caller", states: calleeStates, tracks: calleeTracks)
        XCTAssertTrue(rtc("startLocal", "caller").ok)
        XCTAssertTrue(rtc("startLocal", "callee").ok)
        XCTAssertNotNil(WebRtcRuntime.session("caller")?.localVideo)

        let offer = rtc("createOffer", "caller")
        XCTAssertTrue(offer.ok, offer.message)
        XCTAssertEqual(offer.values["type"], .integer(1))
        XCTAssertTrue(rtc("setRemoteDescription", "callee", ["type": .integer(1), "sdp": offer.values["sdp"]!]).ok)
        let answer = rtc("createAnswer", "callee")
        XCTAssertTrue(answer.ok, answer.message)
        XCTAssertEqual(answer.values["type"], .integer(2))
        XCTAssertTrue(rtc("setRemoteDescription", "caller", ["type": .integer(2), "sdp": answer.values["sdp"]!]).ok)

        waitUntil(20) { calleeStates.contains(Int64(3)) && callerStates.contains(Int64(3)) }
        XCTAssertTrue(calleeTracks.contains(Int64(2)) && calleeTracks.contains(Int64(1)))

        let factory = RtcVideoViewFactory()
        let container = factory.create(context: nil) { _ in } as! RtcVideoContainer
        factory.update(view: container, properties: ["sessionId": .text("callee"), "track": .integer(2), "fit": .integer(2), "mirror": .flag(false)])
        waitUntil(5) { !container.renderer.isHidden }

        let stats = rtc("stats", "caller")
        XCTAssertTrue(stats.ok)
        if case let .integer(sent)? = stats.values["bytesSent"] { XCTAssertGreaterThan(sent, 0) } else { XCTFail("bytesSent") }
        XCTAssertEqual(stats.values["relayed"], .flag(false))

        XCTAssertTrue(rtc("setCamera", "caller", ["enabled": .flag(false)]).ok)
        XCTAssertFalse(WebRtcRuntime.session("caller")!.isCapturing)
        XCTAssertTrue(rtc("setCamera", "caller", ["enabled": .flag(true)]).ok)
        XCTAssertTrue(WebRtcRuntime.session("caller")!.isCapturing)
        XCTAssertTrue(rtc("setMicrophone", "caller", ["enabled": .flag(false)]).ok)

        XCTAssertTrue(rtc("close", "callee").ok)
        waitUntil(2) { container.renderer.isHidden }
        XCTAssertNil(WebRtcRuntime.session("callee"))
        XCTAssertFalse(rtc("next", "callee").ok)
        factory.release(view: container)
    }

    func testMultipleSessionsAreIndependent() {
        create("one", video: false)
        create("two", video: false)
        XCTAssertEqual(WebRtcRuntime.sessionIds(), ["one", "two"])
        XCTAssertTrue(rtc("startLocal", "one").ok)
        XCTAssertTrue(rtc("close", "one").ok)
        XCTAssertEqual(WebRtcRuntime.sessionIds(), ["two"])
        XCTAssertTrue(rtc("startLocal", "two").ok)
        let offer = rtc("createOffer", "two")
        XCTAssertTrue(offer.ok, offer.message)
        if case let .text(sdp)? = offer.values["sdp"] { XCTAssertTrue(sdp.contains("m=audio")) } else { XCTFail("sdp") }
        XCTAssertFalse(rtc("createOffer", "missing").ok)
        XCTAssertFalse(rtc("create", "bad id!").ok)
    }

    func testRenegotiationAndIceEventsArriveThroughTheChannel() {
        create("events", video: false)
        XCTAssertTrue(rtc("startLocal", "events").ok)
        XCTAssertTrue(rtc("createOffer", "events").ok)
        var kinds: [Int64] = []
        while !kinds.contains(1) && kinds.count < 8 {
            if case let .integer(kind)? = rtc("next", "events").values["kind"] { kinds.append(kind) }
        }
        XCTAssertTrue(kinds.contains(4) && kinds.contains(5) && kinds.contains(1), "\(kinds)")
    }

    func testEventChannelBuffersReplacesAndCloses() {
        let channel = EventChannel(capacity: 2)
        for index in 0..<3 { channel.offer(["n": .integer(Int64(index))]) }
        XCTAssertEqual(channel.pendingCount, 2)
        var first: Data?
        channel.next { _, payload in first = payload }
        XCTAssertEqual((try? WireMap.decode(first ?? Data()))?["n"], .integer(1))
        channel.next { _, _ in }
        var replaced: ModuleResultStatus?
        channel.next { status, _ in replaced = status }
        channel.next { _, _ in }
        XCTAssertEqual(replaced, .failure)
        channel.close()
        var closed: ModuleResultStatus?
        channel.next { status, _ in closed = status }
        XCTAssertEqual(closed, .failure)
    }

    func testStatsSummaryUsesNominatedPairAndRelayFlag() {
        let summary = RtcSession.summarize([
            RtcStatSample(id: "o", type: "outbound-rtp", values: ["bytesSent": NSNumber(value: 1_000)]),
            RtcStatSample(id: "i", type: "inbound-rtp", values: ["bytesReceived": NSNumber(value: 500), "packetsLost": NSNumber(value: 2), "jitter": NSNumber(value: 0.01), "kind": "video" as NSString, "framesPerSecond": NSNumber(value: 24)]),
            RtcStatSample(id: "p", type: "candidate-pair", values: ["state": "succeeded" as NSString, "nominated": NSNumber(value: true), "currentRoundTripTime": NSNumber(value: 0.05), "localCandidateId": "l" as NSString]),
            RtcStatSample(id: "l", type: "local-candidate", values: ["candidateType": "relay" as NSString]),
        ])
        XCTAssertEqual(summary["bytesSent"], .integer(1_000))
        XCTAssertEqual(summary["packetsLost"], .integer(2))
        XCTAssertEqual(summary["roundTripTimeMs"], .decimal(50))
        XCTAssertEqual(summary["relayed"], .flag(true))
    }

    func testIceServersParseUrlsAndCredentials() throws {
        let servers = try RtcSession.parseIceServers(#"[{"urls":["turn:t.example:3478"],"username":"u","credential":"p"}]"#)
        XCTAssertEqual(servers.first?.urlStrings, ["turn:t.example:3478"])
        XCTAssertEqual(servers.first?.username, "u")
        XCTAssertThrowsError(try RtcSession.parseIceServers(#"[{"urls":[]}]"#))
    }

    func testCallAudioSwitchesModeRouteTonesAndRestores() {
        XCTAssertTrue(call(audioModule, "start", ["mode": .integer(1)]).ok)
        let session = CallAudioSession.shared
        XCTAssertTrue(session.active)
        XCTAssertEqual(AVAudioSession.sharedInstance().mode, .voiceChat)
        XCTAssertTrue((1...4).contains(session.currentRoute()))
        XCTAssertTrue(call(audioModule, "setSpeaker", ["enabled": .flag(true)]).ok)
        waitUntil(3) { session.currentRoute() == CallAudioSession.routeSpeaker || session.currentRoute() == CallAudioSession.routeBluetooth }
        let route = call(audioModule, "next", [:])
        XCTAssertTrue(route.ok)
        XCTAssertTrue(call(audioModule, "setRingback", ["enabled": .flag(true)]).ok)
        XCTAssertTrue(session.isRingbackPlaying)
        XCTAssertTrue(call(audioModule, "setRingback", ["enabled": .flag(false)]).ok)
        XCTAssertFalse(session.isRingbackPlaying)
        XCTAssertTrue(call(audioModule, "setRingtone", ["enabled": .flag(true)]).ok)
        XCTAssertTrue(call(audioModule, "setRingtone", ["enabled": .flag(false)]).ok)
        waitUntil(2) { !session.isRingtonePlaying }
        XCTAssertTrue(call(audioModule, "setMicrophoneMute", ["muted": .flag(true)]).ok)
        XCTAssertTrue(call(audioModule, "stop", [:]).ok)
        XCTAssertFalse(session.active)
        XCTAssertFalse(call(audioModule, "unknown", [:]).ok)
    }

    func testRouteMappingPrefersBluetoothThenWired() {
        XCTAssertEqual(CallAudioSession.route(of: [.builtInReceiver]), CallAudioSession.routeEarpiece)
        XCTAssertEqual(CallAudioSession.route(of: [.builtInSpeaker]), CallAudioSession.routeSpeaker)
        XCTAssertEqual(CallAudioSession.route(of: [.headphones]), CallAudioSession.routeWired)
        XCTAssertEqual(CallAudioSession.route(of: [.bluetoothHFP]), CallAudioSession.routeBluetooth)
        XCTAssertTrue(CallAudioSession.categoryOptions(speaker: true, bluetooth: true).contains(.defaultToSpeaker))
        XCTAssertFalse(CallAudioSession.categoryOptions(speaker: false, bluetooth: false).contains(.allowBluetooth))
    }

    private func waitUntil(_ seconds: TimeInterval, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        XCTAssertTrue(condition(), "condition not met in \(seconds)s")
    }
}
