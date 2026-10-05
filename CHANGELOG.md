# Changelog

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
