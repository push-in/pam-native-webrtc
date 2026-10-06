<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\PermissionDecision;
use Pam\Native\Overflow;
use Pam\Native\PermissionKind;
use Pam\Native\Style;
use Pam\Native\System\Permissions;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\WebRtc\CallAudio;
use Pam\Native\WebRtc\CallAudioMode;
use Pam\Native\WebRtc\CallAudioRoute;
use Pam\Native\WebRtc\IceCandidate;
use Pam\Native\WebRtc\IceServer;
use Pam\Native\WebRtc\PeerConnection;
use Pam\Native\WebRtc\RtcConnectionState;
use Pam\Native\WebRtc\RtcEvent;
use Pam\Native\WebRtc\RtcEventKind;
use Pam\Native\WebRtc\RtcStats;
use Pam\Native\WebRtc\RtcVideoView;
use Pam\Native\WebRtc\SessionDescription;
use Pam\Native\WebRtc\TrackKind;
use Pam\Native\WebRtc\VideoFit;

/**
 * Loopback call: two peer connections in the same app exchange their offer,
 * answer and ICE candidates directly, which is exactly what a signalling
 * server does between two phones. "caller" sends camera + microphone,
 * "callee" receives them and renders the remote track.
 */
final class LoopbackCall extends Component
{
    private ?PeerConnection $caller = null;
    private ?PeerConnection $callee = null;
    private string $state = 'idle';
    private bool $remoteVideo = false;
    private bool $microphone = true;
    private string $route = '';
    private string $stats = '';

    /** @var list<IceCandidate> Candidates for the callee received before its remote description. */
    private array $pending = [];
    private bool $calleeReady = false;

    public function render(): Element
    {
        $connected = $this->caller !== null;

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    Text::make('WebRTC loopback')->style(new Style(fontSize: 24, fontWeight: 700)),
                    Text::make('State: '.$this->state.($this->route !== '' ? ' · '.$this->route : '')),
                    View::make(
                        $this->remoteVideo ? RtcVideoView::remote('callee')->fit(VideoFit::Cover)->toElement()->style(new Style(flexGrow: 1)) : null,
                    )->style(new Style(height: 260, backgroundColor: 0xFF111111, borderRadius: 12, overflow: Overflow::Hidden)),
                    View::make(
                        $connected ? RtcVideoView::local('caller')->fit(VideoFit::Cover)->toElement()->style(new Style(flexGrow: 1)) : null,
                    )->style(new Style(width: 120, height: 160, backgroundColor: 0xFF222222, borderRadius: 8, overflow: Overflow::Hidden)),
                    $connected
                        ? Row::make(
                            Button::make($this->microphone ? 'Mute' : 'Unmute')->onPress($this->toggleMicrophone(...)),
                            Button::make('Switch camera')->onPress(fn () => $this->caller?->switchCamera()),
                            Button::make('Stats')->onPress($this->readStats(...)),
                            Button::make('Hang up')->onPress($this->hangUp(...)),
                        )->style(new Style(gap: 8))
                        : Button::make('Start loopback call')->onPress($this->start(...)),
                    $this->stats !== '' ? Text::make($this->stats) : null,
                )->style(new Style(flexGrow: 1, padding: 16, gap: 12)),
            ),
        );
    }

    public function start(): void
    {
        Permissions::requestKind(PermissionKind::Microphone, function (PermissionDecision $mic): void {
            Permissions::requestKind(PermissionKind::Camera, function (PermissionDecision $camera) use ($mic): void {
                if (!$mic->granted() || !$camera->granted()) {
                    $this->state = 'camera and microphone are required';

                    return;
                }
                $this->connect();
            });
        });
    }

    public function toggleMicrophone(): void
    {
        $this->microphone = !$this->microphone;
        $this->caller?->enableMicrophone($this->microphone);
    }

    public function readStats(): void
    {
        $this->callee?->stats(function (?RtcStats $stats): void {
            $this->stats = $stats === null
                ? 'stats unavailable'
                : sprintf('%.0f fps in · %d bytes in · relayed %s', $stats->inboundFramesPerSecond, $stats->bytesReceived, $stats->relayed ? 'yes' : 'no');
        });
    }

    public function hangUp(): void
    {
        PeerConnection::closeAll();
        CallAudio::stopActive();
        $this->caller = $this->callee = null;
        $this->pending = [];
        $this->calleeReady = false;
        $this->remoteVideo = false;
        $this->state = 'idle';
        $this->stats = '';
    }

    private function connect(): void
    {
        $servers = [IceServer::stun('stun:stun.l.google.com:19302')];
        $this->state = 'connecting';

        $this->callee = PeerConnection::create($servers, 'callee')
            ->onIceCandidate(fn (IceCandidate $candidate) => $this->caller?->addIceCandidate($candidate))
            ->onRemoteTrack(function (TrackKind $track): void {
                if ($track === TrackKind::Video) {
                    $this->remoteVideo = true;
                }
            });

        $this->caller = PeerConnection::create($servers, 'caller')
            ->video()
            ->resolution(640, 480, 30)
            ->onIceCandidate(function (IceCandidate $candidate): void {
                // A signalling server would relay this; buffer until the callee has the offer.
                if ($this->calleeReady) {
                    $this->callee?->addIceCandidate($candidate);
                } else {
                    $this->pending[] = $candidate;
                }
            })
            ->onConnectionState(function (RtcConnectionState $state): void {
                $this->state = strtolower($state->name);
                if ($state === RtcConnectionState::Connected) {
                    CallAudio::active()?->ringback(false);
                }
            })
            ->on(RtcEventKind::Failure, function (RtcEvent $event): void {
                $this->state = 'failed: '.$event->message;
            });

        CallAudio::start(CallAudioMode::Video)
            ->ringback()
            ->onRouteChange(function (CallAudioRoute $route): void {
                $this->route = strtolower($route->name);
            });

        $this->caller->startLocal(function (bool $ok, string $error): void {
            if (!$ok) {
                $this->state = 'camera failed: '.$error;

                return;
            }
            $this->caller?->offer(function (?SessionDescription $offer, string $error): void {
                if ($offer === null) {
                    $this->state = 'offer failed: '.$error;

                    return;
                }
                $this->callee?->setRemote($offer, function (bool $ok, string $error): void {
                    if (!$ok) {
                        $this->state = 'remote offer failed: '.$error;

                        return;
                    }
                    $this->calleeReady = true;
                    foreach ($this->pending as $candidate) {
                        $this->callee?->addIceCandidate($candidate);
                    }
                    $this->pending = [];
                    $this->callee?->answer(function (?SessionDescription $answer, string $error): void {
                        if ($answer === null) {
                            $this->state = 'answer failed: '.$error;

                            return;
                        }
                        $this->caller?->setRemote($answer);
                    });
                });
            });
        });
    }
}
