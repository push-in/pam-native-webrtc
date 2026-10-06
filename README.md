<!-- pam:product-page:start -->
<div align="center">

# PAM Native WebRTC

**Voice and video calls from PHP, with every frame staying native.**

Native WebRTC peer connections, GPU video rendering and call audio routing for PAM Native applications.

[![Latest version](https://img.shields.io/packagist/v/pushinbr/pam-native-webrtc?style=flat-square&label=stable)](https://packagist.org/packages/pushinbr/pam-native-webrtc)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)

**[Documentation](https://push-in.github.io/pam-docs/packages/native-webrtc/) · [Quick start](#quick-start) · [API reference](#api-reference) · [PAM ecosystem](https://push-in.github.io/pam-docs/ecosystem/) · [Issues](https://github.com/push-in/pam-native-webrtc/issues)**

</div>

---

## Why PAM Native WebRTC

PHP drives the call (signalling, state, UI); the platform WebRTC stack moves
the media. Session descriptions and ICE candidates cross the bridge as small
typed values, events are pushed through one pending module completion per
session (no polling, no timers), and video frames are drawn natively into the
view tree without ever reaching PHP.

| | |
| --- | --- |
| **Best for** | 1:1 voice and video calls, support video chat, telemedicine, walkie-talkie |
| **Native path** | WebRTC M124 (`org.jitsi:webrtc` 124.0.0) on Android · GoogleWebRTC M124 (`stasel/WebRTC` Swift package) on iOS |
| **Application model** | Composer package + generated native integration (`webrtc` and `call-audio` modules, `webrtc.video` view) |
| **Design rule** | Media and audio routing only; signalling, TURN credentials and the call UI belong to your app |

## What you can build

- **Peer connections** — offer/answer, trickle ICE, STUN/TURN, ICE restart,
  relay-only policy, microphone and camera toggles, camera switching, stats and
  any number of concurrent sessions.
- **Native video** — `RtcVideoView` renders local and remote tracks (EGL
  `TextureView` on Android, Metal `RTCMTLVideoView` on iOS), composes with
  declarative overlays and rebinds by itself when tracks change.
- **Call audio** — `CallAudio` replaces `react-native-incall-manager`:
  communication audio focus and mode, speaker/earpiece, wired and Bluetooth
  routing with route events, ringback, ringtone with vibration, microphone mute
  and the proximity screen-off.

## Quick start

Already have a PAM Native project? Add only this capability:

```bash
pam composer require pushinbr/pam-native-webrtc
pam doctor --fix
```

```php
use Pam\Native\WebRtc\{IceCandidate, IceServer, PeerConnection, SessionDescription};

$pc = PeerConnection::create([IceServer::stun('stun:stun.l.google.com:19302')])
    ->video()
    ->onIceCandidate(fn (IceCandidate $c) => $signaling->send('ice', $c->toArray()));

$pc->startLocal(function (bool $ok, string $error) use ($pc, $signaling): void {
    $pc->offer(fn (?SessionDescription $offer) => $signaling->send('offer', $offer?->toArray()));
});
```

New to PAM? Follow the **[five-minute PAM Native setup](https://push-in.github.io/pam-docs/native/overview/)** once, then return here. Your application stays a normal Composer project with a committed lockfile.
<!-- pam:product-page:end -->

## Install

```bash
pam composer require pushinbr/pam-native-webrtc
pam doctor --fix
```

The package is a PAM Native plugin discovered from Composer; nothing is added
to `pam-native.json`. It requires `pushinbr/pam-native` `>=1.0.35 <2.0.0` and
PHP 8.5. The PHP provider registers the `<RtcVideoView>` template tag.

### Android

Merged permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`,
`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK`, `VIBRATE` and `BLUETOOTH`
(`maxSdkVersion="30"`, for SCO routing on Android 11 and older). Camera and
microphone are declared as optional features (`android:required="false"`).
Request `PermissionKind::Microphone` (and `PermissionKind::Camera` for video)
with the core `Permissions` API **before** `startLocal()`. Dependency:
`org.jitsi:webrtc:124.0.0` (adds native libraries for each ABI; expect a few MB
per ABI in the APK).

### iOS

- Swift package `https://github.com/stasel/WebRTC.git` from `124.0.0` (up to
  next major), product `WebRTC`; frameworks `AVFoundation`, `AudioToolbox`.
- `UIBackgroundModes`: `audio` (`ios/Info.plist`), so calls keep running in the
  background.
- `NSCameraUsageDescription` = "Capture photos and videos." and
  `NSMicrophoneUsageDescription` = "Record audio with videos." These strings are
  identical to `pam-native-media`/`pam-native-camera` on purpose: the CLI fails
  the Info.plist merge when two plugins declare different values for the same
  key.

For incoming calls on iOS use `pushinbr/pam-native-calls` (CallKit). Apps
cannot play the user's system ringtone; `CallAudio::ringtone()` plays an alert
sound with vibration instead. With CallKit, start `CallAudio` after CallKit
activates the audio session (`Notification.Name.pamCallAudioActivated`).

## Peer connection

```php
use Pam\Native\WebRtc\{CameraFacing, IceCandidate, IceServer, PeerConnection,
    RtcConnectionState, RtcEventKind, RtcEvent, RtcStats, SessionDescription};

$pc = PeerConnection::create([
        IceServer::stun('stun:stun.l.google.com:19302'),
        IceServer::turn('turn:turn.example.com:3478', $user, $credential),
    ])
    ->video()                       // audio-only when omitted
    ->facing(CameraFacing::Front)
    ->resolution(1280, 720, 30)
    ->onIceCandidate(fn (IceCandidate $c) => $signaling->send('ice', $c->toArray()))
    ->onConnectionState(function (RtcConnectionState $state): void {
        if ($state === RtcConnectionState::Connected) { /* start timer */ }
    })
    ->on(RtcEventKind::RenegotiationNeeded, fn (RtcEvent $e) => $this->renegotiate())
    ->on(RtcEventKind::Failure, fn (RtcEvent $e) => $this->fail($e->message));

$pc->startLocal(function (bool $ok, string $error): void { /* microphone (+ camera) live */ });

// Caller
$pc->offer(fn (?SessionDescription $offer, string $error) => $signaling->send('offer', $offer->toArray()));
// ...when the answer arrives
$pc->setRemote(SessionDescription::fromArray($answerJson));

// Callee
$pc->setRemote(SessionDescription::fromArray($offerJson), function (bool $ok, string $error) use ($pc, $signaling): void {
    $pc->answer(fn (?SessionDescription $answer, string $error) => $signaling->send('answer', $answer->toArray()));
});

$pc->addIceCandidate(IceCandidate::fromArray($candidateJson), fn (bool $added) => null);
$pc->enableMicrophone(false);   // mute this call
$pc->enableCamera(false);       // stops the camera, not only the track
$pc->switchCamera(fn (?CameraFacing $now) => null);
$pc->offer($done, iceRestart: true);
$pc->stats(fn (?RtcStats $s) => printf('%.0f ms rtt, relayed=%d', $s->roundTripTimeMs, $s->relayed));
$pc->close();

PeerConnection::find($id); PeerConnection::all(); PeerConnection::closeAll();
```

Configuration methods (`video`, `facing`, `resolution`, `relayOnly`) must run
before the first operation: the native session is created lazily by the first
command (`startLocal`, `offer`, `setRemote`, …), which also starts the event
channel. Remote ICE candidates that arrive before `setRemote()` has succeeded
should be buffered and added afterwards (Zé Chat keeps a pending list).

## Video

```php
use Pam\Native\WebRtc\{RtcVideoView, VideoFit};

RtcVideoView::remote($pc)->fit(VideoFit::Cover);
RtcVideoView::local($pc)->fit(VideoFit::Contain)->mirror();   // local mirrors by default
```

In templates the plugin registers `<RtcVideoView>`:

```html
<RtcVideoView :session="$sessionId" track="remote" fit="cover" class="remote-video" />
<RtcVideoView :session="$sessionId" track="local" mirror="true" class="local-preview" />
```

`session` accepts a session id or a `PeerConnection`; `track` is `local` or
`remote` (or `1`/`2`), `fit` is `cover` or `contain`, `mirror` is a boolean.
The view rebinds natively when the track appears, changes or the session
closes, so no re-render is required when `RemoteTrack` fires.

## Call audio

```php
use Pam\Native\WebRtc\{CallAudio, CallAudioMode, CallAudioRoute};

$audio = CallAudio::start(CallAudioMode::Video)   // Voice → earpiece + proximity, Video → speaker
    ->speaker()
    ->ringback()                                   // outgoing call waiting tone
    ->bluetooth()                                  // prefer a connected headset
    ->onRouteChange(fn (CallAudioRoute $route) => $this->route = $route);

$audio->ringback(false);      // when the callee answers
$audio->speaker(false);       // earpiece (or wired headset when plugged)
$audio->ringtone();           // in-app incoming ring + vibration
$audio->proximity(false);
CallAudio::active()?->stop(); // restores mode, abandons focus, releases wake lock
```

Routing priority: preferred Bluetooth headset → wired headset (unless speaker
was requested) → speaker when requested → earpiece. Android 12+ uses
`setCommunicationDevice`; older versions use speakerphone and Bluetooth SCO.
iOS uses `AVAudioSession` `playAndRecord` with `voiceChat`/`videoChat`.

## Event contract

| `RtcEventKind` | Value | Payload on `RtcEvent` |
| --- | --- | --- |
| `IceCandidate` | 1 | `candidate` (`IceCandidate`) |
| `ConnectionState` | 2 | `state` (`RtcConnectionState`) |
| `RemoteTrack` | 3 | `track` (`TrackKind`) |
| `RenegotiationNeeded` | 4 | — |
| `LocalReady` | 5 | — (local capture is live) |
| `Failure` | 6 | `message` |

Commands sent without a callback report native failures as a `Failure` event
whose message is prefixed by the method (`"setCamera: No camera is capturing"`).

## A real example: Zé Chat

Zé Chat's direct call screen fetches TURN credentials from its API (cached
for five minutes, public STUN as fallback), then creates one peer per call:

```php
$this->peer = PeerConnection::create(IceServers::toIceServers($servers), $this->sessionId)
    ->video($this->callType === CallType::Video->value)
    ->onIceCandidate($this->sendIceCandidate(...))            // → backend call signal
    ->onConnectionState($this->connectionStateChanged(...))   // Connected → ongoing call surface
    ->onRemoteTrack(function (TrackKind $track): void {
        if ($track === TrackKind::Video) {
            $this->remoteReady = true;                         // shows <RtcVideoView track="remote">
        }
    })
    ->on(RtcEventKind::LocalReady, fn () => $this->localReady = true)
    ->on(RtcEventKind::Failure, fn (RtcEvent $e) => $this->rtcCreated ? $this->log($e->message) : $this->fail($e->message));

$this->peer->startLocal(function (bool $started, string $error): void {
    if (!$started) {
        $this->fail($error);
        return;
    }
    // InCallManager parity: voice → earpiece + proximity, video → speaker; ringback while ringing.
    $this->audio = CallAudio::start($isVideo ? CallAudioMode::Video : CallAudioMode::Voice)->speaker($this->speaker);
    if (!$this->incoming) {
        $this->audio->ringback();
        $this->peer->offer(function (?SessionDescription $offer, string $error): void {
            $offer === null ? $this->fail($error) : $this->api->sendSignal('offer', $offer->toArray());
        });
    }
});
```

TURN servers are converted with `IceServer::stun(...$urls)` /
`IceServer::turn($urls, $username, $credential)` in chunks of eight URLs, and
malformed entries are skipped (`InvalidArgumentException`). The incoming side
plays `CallAudio::start(...)->proximity(false)->ringtone()` while the call
screen waits for an answer. A runnable minimal app is in [`example/`](example).

## API reference

All classes live in `Pam\Native\WebRtc`.

### `PeerConnection`

| Method | Description |
| --- | --- |
| `create(array $iceServers = [], ?string $id = null): self` | Up to 16 `IceServer`s. Id `[A-Za-z0-9_-]{1,128}` (random `rtc-…` by default); must be unique. |
| `find(string $id): ?self`, `all(): list<self>`, `closeAll(): void` | Session registry. |
| `video(bool $enabled = true)` | Video session (audio-only by default). Before the first operation. |
| `facing(CameraFacing $facing)` | Initial camera (default `Front`). |
| `resolution(int $width, int $height, int $fps = 30)` | 160×120@5 to 3840×2160@60 (default 1280×720@30). |
| `relayOnly(bool $relay = true)` | `iceTransportPolicy = relay` (TURN only). |
| `onEvent(Closure(RtcEvent))`, `on(RtcEventKind, Closure(RtcEvent))` | Event listeners. |
| `onIceCandidate(Closure(IceCandidate))`, `onConnectionState(Closure(RtcConnectionState))`, `onRemoteTrack(Closure(TrackKind))` | Typed shortcuts. |
| `startLocal(?Closure(bool, string) $done = null)` | Starts the microphone (and camera) and adds the tracks. |
| `offer(Closure(?SessionDescription, string) $done, bool $iceRestart = false)` | Creates and applies a local offer. |
| `answer(Closure(?SessionDescription, string) $done)` | Creates and applies a local answer. |
| `setRemote(SessionDescription $description, ?Closure(bool, string) $done = null)` | Applies the remote description. |
| `addIceCandidate(IceCandidate $candidate, ?Closure(bool) $done = null)` | `$done(true)` when the candidate was added. Failures never emit `Failure`. |
| `enableMicrophone(bool $enabled = true)`, `enableCamera(bool $enabled = true)` | Track toggles; disabling the camera stops capture. |
| `switchCamera(?Closure(?CameraFacing) $done = null)` | Flips the camera. |
| `stats(Closure(?RtcStats) $done)` | Snapshot; `null` on failure. |
| `close(): void` | Closes the native session; idempotent. |
| `hasVideo()`, `state(): ?RtcConnectionState`, `isClosed()`, `currentFacing(): CameraFacing`, `nativeConfiguration(): array` | Inspection. |
| `$id`, `MODULE = 'webrtc'` | |

`dispatch()` is `@internal`.

### `CallAudio`

| Method | Description |
| --- | --- |
| `start(CallAudioMode $mode = Voice): self` | Starts a session; replaces the active one. |
| `active(): ?self`, `stopActive(): void` | Current session. |
| `speaker(bool $enabled = true)`, `bluetooth(bool $prefer = true)` | Routing preferences. |
| `ringback(bool $play = true)`, `ringtone(bool $play = true)` | Tones (ringtone vibrates when allowed). |
| `proximity(bool $enabled = true)` | Screen off at the ear while the earpiece is active. |
| `mute(bool $muted = true)` | Device-wide microphone mute; prefer `PeerConnection::enableMicrophone()`. |
| `onRouteChange(Closure(CallAudioRoute))`, `route(): ?CallAudioRoute`, `isActive(): bool` | Route events. |
| `stop(): void` | Restores the audio mode, abandons focus, stops tones, releases the wake lock. |
| `$mode`, `MODULE = 'call-audio'` | |

### `RtcVideoView` (`Renderable`, immutable)

`make(PeerConnection|string $session, RtcTrack $track = Remote)`,
`local($session)`, `remote($session)`, `fromProps(array $props)`,
`fit(VideoFit $fit)`, `mirror(bool $mirror = true)`, `properties(): array`,
`toElement(): Element` (a `CustomView` of kind `webrtc.video`). Give the
renderer a size: in templates use classes or layout attributes, in PHP style
the element, for example
`RtcVideoView::remote($pc)->toElement()->style(new Style(flexGrow: 1))` inside
a sized `View`.

### Values

| Class | Members |
| --- | --- |
| `IceServer` | `stun(string ...$urls)`, `turn(string\|array $url, string $username, string $credential)`; 1–8 URLs (`stun:`/`stuns:` or `turn:`/`turns:`, ≤ 512 bytes, no spaces); `toArray()`; readonly `urls`, `username`, `credential`. |
| `IceCandidate` | `__construct(string $candidate, string $sdpMid = '', int $sdpMLineIndex = 0)` (candidate ≤ 4096 bytes, mid ≤ 256, index 0–1024), `fromArray()` (W3C shape), `toArray()`. |
| `SessionDescription` | `__construct(SdpType $type, string $sdp)` (1 byte–512 KiB), `offer()`, `answer()`, `fromArray()` (`{"type":"offer","sdp":"…"}`), `toArray()`. |
| `RtcEvent` | `kind`, `sessionId`, `candidate`, `state`, `track`, `message`; `fromWire()`. |
| `RtcStats` | `bytesSent`, `bytesReceived`, `packetsLost`, `roundTripTimeMs`, `jitterMs`, `inboundFramesPerSecond`, `availableOutgoingBitrate`, `relayed`; `fromWire()`. |

### Enums (int-backed)

`RtcConnectionState` (`New = 1`, `Connecting`, `Connected`, `Disconnected`,
`Failed`, `Closed = 6`), `RtcEventKind` (above), `TrackKind` (`Audio = 1`,
`Video = 2`), `RtcTrack` (`Local = 1`, `Remote = 2`), `VideoFit` (`Cover = 1`,
`Contain = 2`), `CameraFacing` (`Front = 1`, `Back = 2`), `SdpType`
(`Offer = 1`, `Answer = 2`), `CallAudioMode` (`Voice = 1`, `Video = 2`),
`CallAudioRoute` (`Earpiece = 1`, `Speaker = 2`, `WiredHeadset = 3`,
`Bluetooth = 4`).

### Errors

- `InvalidArgumentException`: invalid ICE server URLs or credentials, more than
  16 servers, invalid session id, capture outside the supported range, invalid
  candidate or SDP, unsupported SDP type in `fromArray()`.
- `LogicException`: duplicate session id, configuring after the first
  operation, any command on a closed peer ("Peer connection … is closed"),
  any `CallAudio` command after `stop()`.
- Native failures: `Failure` events (`"Could not create peer connection"`,
  `"No camera available"`, `"Could not open camera"`, `"Session closed"`, …)
  or the `$done` callbacks (`offer`/`answer` receive `null` and the message).

## Limits and troubleshooting

- **`startLocal()` fails with a camera error:** the camera permission was not
  granted, or another app holds the camera.
- **No audio on Bluetooth (Android 12+):** routing uses
  `setCommunicationDevice`; the headset must be connected and support calls
  (SCO/LE). Call `bluetooth(false)` to force built-in outputs.
- **Connection stays `Connecting` on mobile networks:** add a TURN server;
  test with `relayOnly()` and check `RtcStats::$relayed`.
- **Video view is black:** render `RtcVideoView` with an explicit size (or
  absolute fill); the session id must match the `PeerConnection` id.
- **Two plugins conflict on iOS camera strings:** keep this package's strings or
  align the other plugin; the generated Info.plist rejects conflicting values.
- **iOS validation:** the iOS implementation was parse-checked and mirrors the
  Android suite (`ios/Tests/WebRtcTests.swift`) but has not been validated on a
  device yet.

## Compatibility

| `pushinbr/pam-native-webrtc` | `pushinbr/pam-native` | Android | iOS |
| --- | --- | --- | --- |
| 0.2.1 | `>=1.0.35 <2.0.0` (tested with 1.14.x) | API 26+, WebRTC M124 | 15+, GoogleWebRTC M124 |
| 0.2.0 | `>=1.0.35 <2.0.0` | API 26+ | 15+ (Info.plist strings conflict with `pam-native-media`) |
| 0.1.x | `>=1.0.35 <2.0.0` | API 26+ | Not supported |

## Tests

```bash
pam tests/run.php                                                      # PHP contracts
cd android && ../../pam-native/android/gradlew -p . connectedDebugAndroidTest   # loopback call on a device/emulator
```

The instrumented suite runs a real loopback call between two sessions (camera
capture, offer/answer, trickle ICE through the event channel, remote frames in
`RtcVideoView`, stats), session isolation, event-channel semantics and the
call audio state machine. `ios/Tests/WebRtcTests.swift` mirrors it with XCTest.

## License

Apache-2.0
