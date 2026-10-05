# PAM Native WebRTC

Native WebRTC peer connections, GPU video rendering and call audio routing for
[PAM Native](https://github.com/push-in/pam-native) applications.

- **PeerConnection** — offer/answer, trickle ICE, STUN/TURN, microphone and camera
  toggles, camera switching, stats and any number of concurrent sessions.
- **Event channel, not polling** — ICE candidates, connection state, remote
  tracks and renegotiation are pushed from the platform through one pending
  module completion per session. PHP never runs timers to ask for events.
- **RtcVideoView** — frames are drawn natively from the shared EGL context into
  a `TextureView`, so remote and local video compose with declarative overlays
  and never cross into PHP.
- **CallAudio** — replaces `react-native-incall-manager`: communication audio
  focus and mode, speaker/earpiece, wired and Bluetooth (SCO / LE) routing,
  ringback, ringtone with vibration, microphone mute and the proximity wake lock.

Android API 26+ (WebRTC M124). iOS: `CallAudio` maps to `AVAudioSession`;
peer connections and video are not shipped for iOS in 0.1.

## Install

```bash
pam composer require pushinbr/pam-native-webrtc
```

Requires `pushinbr/pam-native` `>=1.0.35 <2.0.0`. The plugin declares its Android
permissions (`CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK`,
`VIBRATE`, network state) through the per-plugin permission manifest. Ask for
camera and microphone at runtime with the core `Permissions` API before
`startLocal()`.

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

$pc->startLocal(function (bool $ok, string $error): void { /* camera + mic live */ });

// Caller
$pc->offer(fn (?SessionDescription $offer) => $signaling->send('offer', $offer->toArray()));
// ...when the answer arrives
$pc->setRemote(SessionDescription::fromArray($answerJson));

// Callee
$pc->setRemote(SessionDescription::fromArray($offerJson), function (bool $ok) use ($pc, $signaling): void {
    $pc->answer(fn (?SessionDescription $answer) => $signaling->send('answer', $answer->toArray()));
});

$pc->addIceCandidate(IceCandidate::fromArray($candidateJson));
$pc->enableMicrophone(false);   // mute
$pc->enableCamera(false);       // stops the camera, not only the track
$pc->switchCamera(fn (?CameraFacing $now) => null);
$pc->offer($done, iceRestart: true);
$pc->stats(fn (?RtcStats $s) => printf('%.0f ms rtt, relayed=%d', $s->roundTripTimeMs, $s->relayed));
$pc->close();

PeerConnection::find($id); PeerConnection::all(); PeerConnection::closeAll();
```

Configuration methods (`video`, `facing`, `resolution`, `relayOnly`) must run
before the first operation; the native session is created lazily.

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

## Event contract

| `RtcEventKind` | Value | Payload |
| --- | --- | --- |
| `IceCandidate` | 1 | `candidate` |
| `ConnectionState` | 2 | `state` (`RtcConnectionState`) |
| `RemoteTrack` | 3 | `track` (`TrackKind`) |
| `RenegotiationNeeded` | 4 | — |
| `LocalReady` | 5 | — |
| `Failure` | 6 | `message` |

## Tests

```bash
pam tests/run.php                                         # PHP contracts
cd android && ANDROID_SERIAL=emulator-5558 \
  ../../../pam-native/android/gradlew -p . connectedDebugAndroidTest   # loopback call on an emulator
```

The instrumented suite runs a real loopback call between two sessions (camera
capture, offer/answer, trickle ICE through the event channel, remote frames in
`RtcVideoView`, stats), session isolation, event-channel semantics and the
call audio state machine.

## License

Apache-2.0
