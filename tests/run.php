<?php

declare(strict_types=1);

$packageAutoload = dirname(__DIR__).'/vendor/autoload.php';
if (is_file($packageAutoload)) {
    require $packageAutoload;
}
$roots = [
    'Pam\\Native\\WebRtc\\' => dirname(__DIR__).'/src/',
    'Pam\\Native\\Testing\\' => dirname(__DIR__, 2).'/pam-native-testing/src/',
    'Pam\\Native\\' => dirname(__DIR__, 2).'/../pam-native/packages/native/src/',
];
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) {
                require $file;
            }

            return;
        }
    }
});

use Pam\Native\Element;
use Pam\Native\Internal\Wire;
use Pam\Native\Testing\DispatchMode;
use Pam\Native\Testing\FakeNativeModuleTransport;
use Pam\Native\Testing\NativeTestHarness;
use Pam\Native\TemplateRegistry;
use Pam\Native\WebRtc\CallAudio;
use Pam\Native\WebRtc\CallAudioMode;
use Pam\Native\WebRtc\CallAudioRoute;
use Pam\Native\WebRtc\CameraFacing;
use Pam\Native\WebRtc\IceCandidate;
use Pam\Native\WebRtc\IceServer;
use Pam\Native\WebRtc\PeerConnection;
use Pam\Native\WebRtc\RtcConnectionState;
use Pam\Native\WebRtc\RtcEvent;
use Pam\Native\WebRtc\RtcEventKind;
use Pam\Native\WebRtc\RtcTrack;
use Pam\Native\WebRtc\RtcVideoView;
use Pam\Native\WebRtc\SdpType;
use Pam\Native\WebRtc\SessionDescription;
use Pam\Native\WebRtc\TrackKind;
use Pam\Native\WebRtc\VideoFit;
use Pam\Native\WebRtc\WebRtcPluginProvider;

$tests = [];
$test = static function (string $name, Closure $body) use (&$tests): void {
    $tests[$name] = $body;
};
$check = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (string $class, Closure $body) use ($check): void {
    try {
        $body();
    } catch (Throwable $error) {
        $check($error instanceof $class, 'expected '.$class.', got '.$error::class);

        return;
    }
    throw new RuntimeException("expected {$class}");
};
$fake = static function (): FakeNativeModuleTransport {
    PeerConnection::closeAll();

    return NativeTestHarness::install();
};
$payload = static fn (FakeNativeModuleTransport $transport, string $method): array => Wire::decodeMap(
    array_values(array_filter($transport->calls(), static fn ($call): bool => $call->method === $method))[0]->payload,
);

$test('coded variants are sequential integers from one', static function () use ($check): void {
    foreach ([
        RtcEventKind::cases(), RtcConnectionState::cases(), SdpType::cases(), TrackKind::cases(), RtcTrack::cases(),
        VideoFit::cases(), CameraFacing::cases(), CallAudioMode::cases(), CallAudioRoute::cases(),
    ] as $cases) {
        $values = array_map(static fn ($case): int => $case->value, $cases);
        $check($values === range(1, count($values)), 'enum is not sequential');
    }
    $check(RtcEventKind::IceCandidate->value === 1 && RtcEventKind::RenegotiationNeeded->value === 4, 'event contract changed');
});

$test('ice servers validate schemes and credentials', static function () use ($check, $throws): void {
    $turn = IceServer::turn(['turn:turn.example.com:3478?transport=udp', 'turns:turn.example.com:5349'], 'user', 'secret');
    $check($turn->toArray()['urls'][1] === 'turns:turn.example.com:5349', 'turn urls lost');
    $check(IceServer::stun('stun:stun.l.google.com:19302')->username === '', 'stun has credentials');
    $throws(InvalidArgumentException::class, static fn () => IceServer::stun('https://example.com'));
    $throws(InvalidArgumentException::class, static fn () => IceServer::turn('turn:example.com', '', ''));
    $throws(InvalidArgumentException::class, static fn () => IceServer::stun('turn:example.com'));
});

$test('peer connections open lazily with the configured native contract', static function () use ($fake, $payload, $check): void {
    $transport = $fake();
    $transport->succeed('webrtc', 'close')->succeed('webrtc', 'create')->succeed('webrtc', 'next', [], DispatchMode::Deferred)->succeed('webrtc', 'startLocal');
    $pc = PeerConnection::create([IceServer::stun('stun:stun.example.com'), IceServer::turn('turn:t.example.com', 'u', 'p')], 'call-1')
        ->video()
        ->facing(CameraFacing::Back)
        ->resolution(960, 540, 24)
        ->relayOnly();
    $check($transport->calls() === [], 'opened before first operation');
    $ok = null;
    $pc->startLocal(static function (bool $success) use (&$ok): void {
        $ok = $success;
    });
    $check($ok === true, 'startLocal callback missing');
    $check(array_map(static fn ($c): string => $c->method, $transport->calls()) === ['create', 'next', 'startLocal'], 'unexpected call order');
    $create = $payload($transport, 'create');
    $servers = json_decode($create['iceServersJson'], true, flags: JSON_THROW_ON_ERROR);
    $check($create['sessionId'] === 'call-1' && $create['video'] === true && $create['facing'] === 2, 'create payload mismatch');
    $check($create['width'] === 960 && $create['height'] === 540 && $create['fps'] === 24 && $create['relayOnly'] === true, 'capture mismatch');
    $check($servers[1] === ['urls' => ['turn:t.example.com'], 'username' => 'u', 'credential' => 'p'], 'turn server mismatch');
    $pc->close();
    NativeTestHarness::uninstall();
});

$test('events arrive through the re-armed native channel', static function () use ($fake, $check): void {
    $transport = $fake();
    $transport->succeed('webrtc', 'close')->succeed('webrtc', 'create')
        ->succeed('webrtc', 'next', ['kind' => 1, 'candidate' => 'candidate:1 1 udp 1 10.0.0.2 5000 typ host', 'sdpMid' => '0', 'sdpMLineIndex' => 0], DispatchMode::Deferred)
        ->succeed('webrtc', 'next', ['kind' => 2, 'state' => 3], DispatchMode::Deferred)
        ->succeed('webrtc', 'next', ['kind' => 3, 'track' => 2], DispatchMode::Deferred)
        ->succeed('webrtc', 'next', [], DispatchMode::Deferred)
        ->succeed('webrtc', 'createOffer', ['type' => 1, 'sdp' => "v=0\r\n"]);
    $candidates = [];
    $states = [];
    $tracks = [];
    $kinds = [];
    $pc = PeerConnection::create()
        ->onIceCandidate(static function (IceCandidate $candidate) use (&$candidates): void {
            $candidates[] = $candidate;
        })
        ->onConnectionState(static function (RtcConnectionState $state) use (&$states): void {
            $states[] = $state;
        })
        ->onRemoteTrack(static function (TrackKind $track) use (&$tracks): void {
            $tracks[] = $track;
        })
        ->onEvent(static function (RtcEvent $event) use (&$kinds): void {
            $kinds[] = $event->kind;
        });
    $offer = null;
    $pc->offer(static function (?SessionDescription $description) use (&$offer): void {
        $offer = $description;
    });
    $check($offer?->type === SdpType::Offer && $offer->toArray() === ['type' => 'offer', 'sdp' => "v=0\r\n"], 'offer mismatch');
    $transport->flushOne();
    $transport->flushOne();
    $transport->flushOne();
    $check(count($candidates) === 1 && $candidates[0]->sdpMid === '0', 'candidate not delivered');
    $check($states === [RtcConnectionState::Connected] && $pc->state() === RtcConnectionState::Connected, 'state not delivered');
    $check($tracks === [TrackKind::Video], 'track not delivered');
    $check($kinds === [RtcEventKind::IceCandidate, RtcEventKind::ConnectionState, RtcEventKind::RemoteTrack], 'event order');
    $transport->assertCalled('webrtc', 'next', 4);
    $pc->close();
    NativeTestHarness::uninstall();
});

$test('signaling helpers map W3C shapes', static function () use ($fake, $payload, $check): void {
    $transport = $fake();
    $transport->succeed('webrtc', 'close')->succeed('webrtc', 'create')->succeed('webrtc', 'next', [], DispatchMode::Deferred)
        ->succeed('webrtc', 'setRemoteDescription')
        ->succeed('webrtc', 'addIceCandidate', ['added' => true])
        ->succeed('webrtc', 'switchCamera', ['facing' => 2])
        ->succeed('webrtc', 'stats', ['bytesSent' => 10, 'roundTripTimeMs' => 42.5, 'relayed' => true]);
    $pc = PeerConnection::create(id: 'sig');
    $pc->setRemote(SessionDescription::fromArray(['type' => 'answer', 'sdp' => 'v=0']));
    $added = null;
    $pc->addIceCandidate(IceCandidate::fromArray(['candidate' => 'c', 'sdpMid' => 'audio', 'sdpMLineIndex' => '1']), static function (bool $ok) use (&$added): void {
        $added = $ok;
    });
    $facing = null;
    $pc->switchCamera(static function (?CameraFacing $value) use (&$facing): void {
        $facing = $value;
    });
    $stats = null;
    $pc->stats(static function ($value) use (&$stats): void {
        $stats = $value;
    });
    $check($payload($transport, 'setRemoteDescription') === ['sdp' => 'v=0', 'sessionId' => 'sig', 'type' => 2], 'remote payload');
    $check($payload($transport, 'addIceCandidate')['sdpMLineIndex'] === 1 && $added === true, 'candidate payload');
    $check($facing === CameraFacing::Back && $pc->currentFacing() === CameraFacing::Back, 'facing not tracked');
    $check($stats?->bytesSent === 10 && $stats->roundTripTimeMs === 42.5 && $stats->relayed, 'stats mismatch');
    $pc->close();
    NativeTestHarness::uninstall();
});

$test('failures without callbacks become Failure events', static function () use ($fake, $check): void {
    $transport = $fake();
    $transport->succeed('webrtc', 'close')->succeed('webrtc', 'create')->succeed('webrtc', 'next', [], DispatchMode::Deferred)->fail('webrtc', 'setCamera', 'camera busy');
    $messages = [];
    $pc = PeerConnection::create()->on(RtcEventKind::Failure, static function (RtcEvent $event) use (&$messages): void {
        $messages[] = $event->message;
    });
    $pc->enableCamera(false);
    $check($messages === ['setCamera: camera busy'], 'failure not surfaced');
    $pc->close();
    NativeTestHarness::uninstall();
});

$test('lifecycle guards configuration, duplicates and closed sessions', static function () use ($fake, $check, $throws): void {
    $transport = $fake();
    $transport->succeed('webrtc', 'close')->succeed('webrtc', 'create')->succeed('webrtc', 'next', [], DispatchMode::Deferred)
        ->succeed('webrtc', 'setMicrophone')->succeed('webrtc', 'close');
    $pc = PeerConnection::create(id: 'guarded');
    $throws(LogicException::class, static fn () => PeerConnection::create(id: 'guarded'));
    $throws(InvalidArgumentException::class, static fn () => PeerConnection::create(id: 'bad id'));
    $pc->enableMicrophone(false);
    $throws(LogicException::class, static fn () => $pc->video());
    $check(PeerConnection::find('guarded') === $pc && count(PeerConnection::all()) === 1, 'registry mismatch');
    $pc->close();
    $pc->close();
    $transport->assertCalled('webrtc', 'close', 1);
    $check(PeerConnection::find('guarded') === null && $pc->isClosed(), 'not unregistered');
    $throws(LogicException::class, static fn () => $pc->enableMicrophone());
    NativeTestHarness::uninstall();
});

$test('video view renders the typed native contract', static function () use ($check): void {
    $local = RtcVideoView::local('s1');
    $check($local->properties() === ['sessionId' => 's1', 'track' => 1, 'fit' => 1, 'mirror' => true], 'local defaults');
    $remote = RtcVideoView::remote('s1')->fit(VideoFit::Contain);
    $check($remote->properties()['mirror'] === false && $remote->properties()['fit'] === 2, 'remote props');
    $check($remote->toElement() instanceof Element, 'not renderable');
    $fromTemplate = RtcVideoView::fromProps(['sessionId' => 's2', 'track' => 'local', 'fit' => 'contain', 'mirror' => 'false']);
    $check($fromTemplate->properties() === ['sessionId' => 's2', 'track' => 1, 'fit' => 2, 'mirror' => false], 'template props');
    (new WebRtcPluginProvider())->register();
    $factory = TemplateRegistry::factory('RtcVideoView');
    $check($factory !== null && $factory(['session' => 's3'], [], null) instanceof RtcVideoView, 'template tag missing');
});

$test('call audio applies fluent commands in order and reports routes', static function () use ($check, $throws): void {
    $transport = NativeTestHarness::install();
    $transport->succeed('call-audio', 'start')
        ->succeed('call-audio', 'next', ['route' => 2], DispatchMode::Deferred)
        ->succeed('call-audio', 'next', [], DispatchMode::Deferred)
        ->succeed('call-audio', 'setSpeaker')->succeed('call-audio', 'setRingback')->succeed('call-audio', 'setBluetooth')
        ->succeed('call-audio', 'setRingback')->succeed('call-audio', 'stop');
    $routes = [];
    $audio = CallAudio::start(CallAudioMode::Video)->speaker()->ringback()->bluetooth()
        ->onRouteChange(static function (CallAudioRoute $route) use (&$routes): void {
            $routes[] = $route;
        });
    $transport->flushOne();
    $check($routes === [CallAudioRoute::Speaker] && $audio->route() === CallAudioRoute::Speaker, 'route not reported');
    $check(CallAudio::active() === $audio, 'not active');
    $audio->ringback(false);
    $methods = array_map(static fn ($c): string => $c->method, $transport->calls());
    $check($methods === ['start', 'next', 'setSpeaker', 'setRingback', 'setBluetooth', 'next', 'setRingback'], implode(',', $methods));
    $check(Wire::decodeMap($transport->calls()[0]->payload) === ['mode' => 2], 'mode payload');
    CallAudio::stopActive();
    $check(CallAudio::active() === null && !$audio->isActive(), 'still active');
    $throws(LogicException::class, static fn () => $audio->speaker());
    NativeTestHarness::uninstall();
});

$test('plugin manifest targets PAM Native 1.0.35 and declares runtime permissions', static function () use ($check): void {
    $manifest = json_decode((string) file_get_contents(dirname(__DIR__).'/pam-native.plugin.json'), true, flags: JSON_THROW_ON_ERROR);
    $check($manifest['pamNative'] === ['minimum' => '1.0.35', 'maximumExclusive' => '2.0.0'], 'range');
    foreach (['CAMERA', 'RECORD_AUDIO', 'MODIFY_AUDIO_SETTINGS', 'ACCESS_NETWORK_STATE'] as $permission) {
        $check(in_array("android.permission.{$permission}", $manifest['android']['permissions'], true), $permission);
    }
    $kotlin = (string) file_get_contents(dirname(__DIR__).'/android/src/main/kotlin/dev/pam/webrtc/RtcSession.kt');
    $check(str_contains($kotlin, 'EVENT_RENEGOTIATION_NEEDED = 4') && str_contains($kotlin, 'EVENT_ICE_CANDIDATE = 1'), 'native event codes drifted');
});

$failed = 0;
foreach ($tests as $name => $body) {
    try {
        $body();
        fwrite(STDOUT, "PASS {$name}\n");
    } catch (Throwable $error) {
        $failed++;
        fwrite(STDERR, "FAIL {$name}: {$error->getMessage()}\n");
    }
}
fwrite(STDOUT, count($tests)." tests, {$failed} failures\n");
exit($failed === 0 ? 0 : 1);
