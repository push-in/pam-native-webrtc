<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Overflow;
use Pam\Native\PermissionDecision;
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
use Pam\Native\WebRtc\CameraFacing;
use Pam\Native\WebRtc\IceCandidate;
use Pam\Native\WebRtc\LocalMedia;
use Pam\Native\WebRtc\PeerConnection;
use Pam\Native\WebRtc\RtcVideoView;
use Pam\Native\WebRtc\SessionDescription;
use Pam\Native\WebRtc\TrackKind;
use Pam\Native\WebRtc\VideoFit;

/**
 * Group (mesh) loopback: one shared LocalMedia stream — a single camera and
 * microphone — feeds two outgoing peer connections, each answered by its own
 * "remote participant" inside the app. The preview renders once; mute,
 * camera and front/back switching apply to both peers at once.
 */
final class GroupLoopbackCall extends Component
{
    private const array PEERS = ['p1', 'p2'];

    private ?LocalMedia $media = null;
    private bool $camera = true;
    private bool $microphone = true;
    private string $facing = 'front';

    /** @var array<string, bool> participant => its remote video arrived */
    private array $remoteVideo = [];

    /** @var array<string, list<IceCandidate>> candidates buffered until the remote side has the offer */
    private array $pending = [];

    /** @var array<string, bool> */
    private array $ready = [];

    public function render(): Element
    {
        $tiles = [];
        foreach (self::PEERS as $peer) {
            $tiles[] = View::make(
                ($this->remoteVideo[$peer] ?? false) ? RtcVideoView::remote("{$peer}-remote")->fit(VideoFit::Cover)->toElement()->style(new Style(flexGrow: 1)) : null,
            )->style(new Style(flexGrow: 1, height: 180, backgroundColor: 0xFF111111, borderRadius: 12, overflow: Overflow::Hidden));
        }

        return Screen::make(
            SafeAreaView::make(
                Column::make(
                    Text::make('WebRTC group loopback')->style(new Style(fontSize: 24, fontWeight: 700)),
                    Text::make('Each tile is a participant receiving the same local camera.'),
                    Row::make(...$tiles)->style(new Style(gap: 8)),
                    View::make(
                        $this->media !== null ? RtcVideoView::preview($this->media)->toElement()->style(new Style(flexGrow: 1)) : null,
                    )->style(new Style(width: 120, height: 160, backgroundColor: 0xFF222222, borderRadius: 8, overflow: Overflow::Hidden)),
                    $this->media !== null
                        ? Row::make(
                            Button::make($this->microphone ? 'Mute all' : 'Unmute all')->onPress($this->toggleMicrophone(...)),
                            Button::make($this->camera ? 'Camera off' : 'Camera on')->onPress($this->toggleCamera(...)),
                            Button::make('Switch ('.$this->facing.')')->onPress($this->switchCamera(...)),
                            Button::make('Hang up')->onPress($this->hangUp(...)),
                        )->style(new Style(gap: 8))
                        : Button::make('Start group loopback')->onPress($this->start(...)),
                )->style(new Style(flexGrow: 1, padding: 16, gap: 12)),
            ),
        );
    }

    public function start(): void
    {
        Permissions::requestKind(PermissionKind::Microphone, function (PermissionDecision $mic): void {
            Permissions::requestKind(PermissionKind::Camera, function (PermissionDecision $camera) use ($mic): void {
                if ($mic->granted()) {
                    $this->connect($camera->granted());
                }
            });
        });
    }

    public function toggleMicrophone(): void
    {
        $this->microphone = !$this->microphone;
        $this->media?->enableMicrophone($this->microphone);
    }

    public function toggleCamera(): void
    {
        $this->camera = !$this->camera;
        $this->media?->enableCamera($this->camera);
    }

    public function switchCamera(): void
    {
        $this->media?->switchCamera(function (?CameraFacing $facing): void {
            if ($facing !== null) {
                $this->facing = strtolower($facing->name);
            }
        });
    }

    public function hangUp(): void
    {
        PeerConnection::closeAll();
        $this->media?->close();
        CallAudio::stopActive();
        $this->media = null;
        $this->remoteVideo = $this->pending = $this->ready = [];
    }

    private function connect(bool $video): void
    {
        CallAudio::start(CallAudioMode::Video)->speaker();
        // One capture for the whole call; the preview shows it before any peer connects.
        $this->media = LocalMedia::create('group-local')->video($video)->resolution(640, 480, 24)->start();
        foreach (self::PEERS as $peer) {
            $this->join($peer);
        }
    }

    /** A participant joins: an outgoing peer on the shared stream and its in-app answerer. */
    private function join(string $peer): void
    {
        $remote = PeerConnection::create([], "{$peer}-remote")
            ->onIceCandidate(fn (IceCandidate $candidate) => PeerConnection::find($peer)?->addIceCandidate($candidate))
            ->onRemoteTrack(function (TrackKind $track) use ($peer): void {
                if ($track === TrackKind::Video) {
                    $this->remoteVideo[$peer] = true;
                }
            });
        $local = PeerConnection::create([], $peer)
            ->localMedia($this->media ?? throw new \LogicException('No local media'))
            ->onIceCandidate(function (IceCandidate $candidate) use ($peer, $remote): void {
                if ($this->ready[$peer] ?? false) {
                    $remote->addIceCandidate($candidate);
                } else {
                    $this->pending[$peer][] = $candidate;
                }
            });
        $remote->startLocal();
        $local->startLocal(function (bool $ok) use ($peer, $local, $remote): void {
            if (!$ok) {
                return;
            }
            $local->offer(function (?SessionDescription $offer) use ($peer, $local, $remote): void {
                if ($offer === null) {
                    return;
                }
                $remote->setRemote($offer, function (bool $ok) use ($peer, $local, $remote): void {
                    $this->ready[$peer] = $ok;
                    foreach ($this->pending[$peer] ?? [] as $candidate) {
                        $remote->addIceCandidate($candidate);
                    }
                    $this->pending[$peer] = [];
                    $remote->answer(fn (?SessionDescription $answer) => $answer === null ? null : $local->setRemote($answer));
                });
            });
        });
    }
}
