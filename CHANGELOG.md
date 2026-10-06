# Changelog

## 0.3.0 - 2026-10-06

- Add `LocalMedia`, a shared local stream for group (mesh) calls: one
  microphone track and one camera capturer whose tracks are added to every
  peer configured with `PeerConnection::localMedia()`. Microphone, camera and
  front/back switching act on the single capture, so they apply to all peers;
  peers join (getting the live tracks) and leave without stopping it, and
  closing the stream detaches the remaining peers before releasing the camera.
- `RtcVideoView::preview($media)` (and `<RtcVideoView :media="…">`) renders the
  one local preview of a shared stream.
- Android (`RtcLocalMedia`) and iOS (`RtcLocalMedia.swift`) implementations
  with new `media*` module methods and the optional `localMediaId` on
  `create`; 1:1 sessions are unchanged.
- Android instrumented mesh test (one capturer feeding two connected peers,
  shared toggles and switch, late joiner, leaving peer, stream close), its
  XCTest mirror (uncompiled, needs Mac validation), PHP contract tests and a
  group loopback example.

## 0.2.1 - 2026-10-05

- iOS usage descriptions now match `pam-native-media`/`pam-native-camera`, so
  apps installing these plugins together no longer fail the generated
  Info.plist merge.

## 0.2.0 - 2026-10-05

- iOS: native peer connections on GoogleWebRTC M124 (`stasel/WebRTC` Swift
  package) with offer/answer, trickle ICE, ICE restart, relay-only policy,
  microphone/camera toggles, camera switching, stats and concurrent sessions;
  the same push-style event channel as Android.
- iOS: `RtcVideoView` renders local/remote tracks with `RTCMTLVideoView`
  (cover/contain, mirroring) and rebinds when tracks change.
- iOS: `CallAudio` on `AVAudioSession` with route events, speaker/Bluetooth/
  wired routing, proximity monitoring, ringback, ringtone with vibration and
  microphone mute; the plugin declares the `audio` background mode.
- XCTest mirror of the Android instrumented suite (`ios/Tests`). The iOS code
  was parse-checked only (no Xcode on the release machine) and needs device
  validation.

## 0.1.0 - 2026-10-05

- Add `PeerConnection` with STUN/TURN servers, lazy native sessions, offer/answer,
  trickle ICE, ICE restart, relay-only policy, microphone and camera toggles,
  camera switching, stats and multiple concurrent sessions.
- Deliver `RtcEventKind` events (ICE candidate, connection state, remote track,
  renegotiation needed, local ready, failure) through a push-style module event
  channel instead of polling.
- Add the `RtcVideoView` component and `<RtcVideoView>` template tag, rendered
  natively from a shared EGL context into a `TextureView` with cover/contain fit
  and mirroring; renderers rebind automatically when tracks change.
- Add `CallAudio` (replacement for InCallManager): communication focus and mode,
  speaker/earpiece/wired/Bluetooth routing with route events, ringback, ringtone
  with vibration, microphone mute and proximity wake lock.
- Android instrumented loopback-call suite and PHP contract tests.
