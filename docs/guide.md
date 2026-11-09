# PAM Native WebRTC guide and API reference

Moved out of the README; nothing was removed.

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

## Group calls (shared local media)

A mesh call keeps one `PeerConnection` per remote participant. Give them one
`LocalMedia` stream instead of letting each open the camera (a device has
only one camera, so a second capture would fail):

```php
use Pam\Native\WebRtc\{CameraFacing, LocalMedia, PeerConnection, RtcVideoView};

$media = LocalMedia::create()             // one microphone + one camera for the whole call
    ->video()
    ->facing(CameraFacing::Front)
    ->resolution(640, 480, 24)
    ->onFailure(fn (string $message) => $this->error = $message)
    ->start();                             // optional: opens the capture before any peer exists

$peer = PeerConnection::create($iceServers, "grp-{$remoteId}")
    ->localMedia($media)                   // add the shared tracks instead of a new capture
    ->onIceCandidate(...);
$peer->startLocal(fn (bool $ok) => $ok && $this->offerTo($remoteId));

$media->enableMicrophone(false);          // every peer
$media->enableCamera(false);              // stops the camera; tracks stay negotiated (no renegotiation)
$media->switchCamera(fn (?CameraFacing $facing) => $this->facing = $facing);
RtcVideoView::preview($media);            // the single local preview (mirrored)

$peer->close();                           // a participant left: the capture keeps running
$media->close();                          // end of call: detaches remaining peers, releases the camera
```

- The camera opens once, however many peers attach; a peer created later gets
  the live tracks at `startLocal()`.
- `enableMicrophone()`, `enableCamera()` and `switchCamera()` on a peer that
  uses a stream act on the stream (every peer), so 1:1 code keeps working.
- `switchCamera()` fails while the camera is off; toggles keep the same track,
  so remote renderers never rebind.
- `LocalMedia` ids share the namespace of peer ids (`media-…` by default).

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

For a shared stream use `<RtcVideoView :media="$media" />` (or the media id
in `session` with `track="local"`).

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
| `localMedia(LocalMedia $media)`, `sharedMedia(): ?LocalMedia` | Sends a shared stream (group calls) instead of opening a capture; toggles then act on the stream. |
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

### `LocalMedia`

| Method | Description |
| --- | --- |
| `create(?string $id = null): self` | Id `[A-Za-z0-9_-]{1,128}` (random `media-…`), unique among streams and peers. |
| `find(string $id): ?self`, `all(): list<self>`, `closeAll(): void` | Stream registry. |
| `video(bool $enabled = true)`, `facing(CameraFacing $facing)`, `resolution(int $width, int $height, int $fps = 30)` | Capture configuration, before the first operation (audio-only by default). |
| `start(?Closure(bool, string) $done = null)` | Opens the microphone (and camera) once; peers using the stream start it too. |
| `enableMicrophone(bool $enabled = true)` | Mutes every peer. |
| `enableCamera(bool $enabled = true, ?Closure(bool) $done = null)` | Stops/resumes the single camera for every peer. |
| `switchCamera(?Closure(?CameraFacing) $done = null)` | Front/back for every peer; `null` on failure (camera off, single camera). |
| `onFailure(Closure(string) $handler)` | Failures of commands sent without a callback. |
| `close(): void` | Detaches every peer and releases the capture; idempotent. |
| `hasVideo()`, `isStarted()`, `isCapturingVideo()`, `isMicrophoneEnabled()`, `isCameraEnabled()`, `currentFacing()`, `isClosed()`, `nativeConfiguration()` | Inspection. |

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

`make(PeerConnection|LocalMedia|string $session, RtcTrack $track = Remote)`,
`local($session)`, `remote($session)`, `preview(LocalMedia|string $media)`,
`fromProps(array $props)`,
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
- `LogicException`: duplicate session or stream id, configuring after the first
  operation, any command on a closed peer ("Peer connection … is closed"),
  any `CallAudio` command after `stop()`.
- Native failures: `Failure` events (`"Could not create peer connection"`,
  `"No camera available"`, `"Could not open camera"`, `"Session closed"`, …)
  or the `$done` callbacks (`offer`/`answer` receive `null` and the message).

## Tests

```bash
pam tests/run.php                                                      # PHP contracts
cd android && ../../pam-native/android/gradlew -p . connectedDebugAndroidTest   # loopback call on a device/emulator
```

The instrumented suite runs a real loopback call between two sessions (camera
capture, offer/answer, trickle ICE through the event channel, remote frames in
`RtcVideoView`, stats), a shared `LocalMedia` mesh (one capturer feeding
several connected peers, preview, shared toggles and switch, a late joiner, a
leaving peer and stream close), session isolation, event-channel semantics and
the call audio state machine. `ios/Tests/WebRtcTests.swift` mirrors it with XCTest.
