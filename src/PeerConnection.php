<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Native WebRTC peer connection.
 *
 * The connection is configured fluently and opened lazily by the first media
 * or signaling operation. Events arrive through the module event channel: a
 * single pending native completion that the platform resolves when the peer
 * produces an event, so no timers or polling run in PHP.
 */
final class PeerConnection
{
    public const string MODULE = 'webrtc';

    /** @var array<string, self> */
    private static array $sessions = [];

    private bool $video = false;
    private CameraFacing $facing = CameraFacing::Front;
    private int $width = 1280;
    private int $height = 720;
    private int $fps = 30;
    private bool $relayOnly = false;
    private bool $opened = false;
    private bool $closed = false;
    private ?RtcConnectionState $state = null;

    /** @var list<Closure(RtcEvent): void> */
    private array $listeners = [];

    /** @param list<IceServer> $iceServers */
    private function __construct(
        public readonly string $id,
        private readonly array $iceServers,
    ) {
    }

    /** @param list<IceServer> $iceServers */
    public static function create(array $iceServers = [], ?string $id = null): self
    {
        foreach ($iceServers as $server) {
            if (!$server instanceof IceServer) {
                throw new InvalidArgumentException('ICE servers must be IceServer instances.');
            }
        }
        if (count($iceServers) > 16) {
            throw new InvalidArgumentException('At most 16 ICE servers are supported.');
        }
        $id ??= 'rtc-'.bin2hex(random_bytes(8));
        if (preg_match('/^[A-Za-z0-9_-]{1,128}$/D', $id) !== 1) {
            throw new InvalidArgumentException('Session ids may contain only letters, digits, "-" and "_".');
        }
        if (isset(self::$sessions[$id])) {
            throw new LogicException("Peer connection {$id} already exists.");
        }

        return self::$sessions[$id] = new self($id, array_values($iceServers));
    }

    public static function find(string $id): ?self
    {
        return self::$sessions[$id] ?? null;
    }

    /** @return list<self> */
    public static function all(): array
    {
        return array_values(self::$sessions);
    }

    public static function closeAll(): void
    {
        foreach (self::$sessions as $session) {
            $session->close();
        }
    }

    public function video(bool $enabled = true): self
    {
        $this->assertConfigurable();
        $this->video = $enabled;

        return $this;
    }

    public function facing(CameraFacing $facing): self
    {
        $this->assertConfigurable();
        $this->facing = $facing;

        return $this;
    }

    public function resolution(int $width, int $height, int $fps = 30): self
    {
        $this->assertConfigurable();
        if ($width < 160 || $width > 3840 || $height < 120 || $height > 2160 || $fps < 5 || $fps > 60) {
            throw new InvalidArgumentException('Capture must be between 160x120@5 and 3840x2160@60.');
        }
        [$this->width, $this->height, $this->fps] = [$width, $height, $fps];

        return $this;
    }

    /** Forces TURN relay candidates (iceTransportPolicy = relay). */
    public function relayOnly(bool $relay = true): self
    {
        $this->assertConfigurable();
        $this->relayOnly = $relay;

        return $this;
    }

    public function hasVideo(): bool
    {
        return $this->video;
    }

    public function state(): ?RtcConnectionState
    {
        return $this->state;
    }

    public function isClosed(): bool
    {
        return $this->closed;
    }

    /** @param Closure(RtcEvent): void $handler */
    public function onEvent(Closure $handler): self
    {
        $this->listeners[] = $handler;

        return $this;
    }

    /** @param Closure(RtcEvent): void $handler */
    public function on(RtcEventKind $kind, Closure $handler): self
    {
        return $this->onEvent(static function (RtcEvent $event) use ($kind, $handler): void {
            if ($event->kind === $kind) {
                $handler($event);
            }
        });
    }

    /** @param Closure(IceCandidate): void $handler */
    public function onIceCandidate(Closure $handler): self
    {
        return $this->on(RtcEventKind::IceCandidate, static function (RtcEvent $event) use ($handler): void {
            if ($event->candidate !== null) {
                $handler($event->candidate);
            }
        });
    }

    /** @param Closure(RtcConnectionState): void $handler */
    public function onConnectionState(Closure $handler): self
    {
        return $this->on(RtcEventKind::ConnectionState, static function (RtcEvent $event) use ($handler): void {
            if ($event->state !== null) {
                $handler($event->state);
            }
        });
    }

    /** @param Closure(TrackKind): void $handler */
    public function onRemoteTrack(Closure $handler): self
    {
        return $this->on(RtcEventKind::RemoteTrack, static function (RtcEvent $event) use ($handler): void {
            if ($event->track !== null) {
                $handler($event->track);
            }
        });
    }

    /** Starts microphone (and camera for video sessions) capture and adds the tracks to the peer. */
    public function startLocal(?Closure $done = null): self
    {
        return $this->command('startLocal', [], $done === null ? null : static function (NativeModuleResult $result) use ($done): void {
            $done($result->succeeded(), $result->succeeded() ? '' : $result->message());
        });
    }

    /** @param Closure(?SessionDescription, string): void $done */
    public function offer(Closure $done, bool $iceRestart = false): self
    {
        return $this->command('createOffer', ['iceRestart' => $iceRestart], self::description($done));
    }

    /** @param Closure(?SessionDescription, string): void $done */
    public function answer(Closure $done): self
    {
        return $this->command('createAnswer', [], self::description($done));
    }

    /** @param (Closure(bool, string): void)|null $done */
    public function setRemote(SessionDescription $description, ?Closure $done = null): self
    {
        return $this->command(
            'setRemoteDescription',
            ['type' => $description->type->value, 'sdp' => $description->sdp],
            $done === null ? null : static function (NativeModuleResult $result) use ($done): void {
                $done($result->succeeded(), $result->succeeded() ? '' : $result->message());
            },
        );
    }

    /** @param (Closure(bool): void)|null $done */
    public function addIceCandidate(IceCandidate $candidate, ?Closure $done = null): self
    {
        return $this->command(
            'addIceCandidate',
            $candidate->toArray(),
            $done === null ? null : static function (NativeModuleResult $result) use ($done): void {
                $done($result->succeeded() && (bool) ($result->values()['added'] ?? false));
            },
            reportFailure: false,
        );
    }

    public function enableMicrophone(bool $enabled = true): self
    {
        return $this->command('setMicrophone', ['enabled' => $enabled]);
    }

    public function enableCamera(bool $enabled = true): self
    {
        return $this->command('setCamera', ['enabled' => $enabled]);
    }

    /** @param (Closure(?CameraFacing): void)|null $done */
    public function switchCamera(?Closure $done = null): self
    {
        return $this->command('switchCamera', [], function (NativeModuleResult $result) use ($done): void {
            $facing = $result->succeeded() ? CameraFacing::tryFrom((int) ($result->values()['facing'] ?? 0)) : null;
            if ($facing !== null) {
                $this->facing = $facing;
            }
            if ($done !== null) {
                $done($facing);
            }
        });
    }

    public function currentFacing(): CameraFacing
    {
        return $this->facing;
    }

    /** @param Closure(?RtcStats): void $done */
    public function stats(Closure $done): self
    {
        return $this->command('stats', [], static function (NativeModuleResult $result) use ($done): void {
            $done($result->succeeded() ? RtcStats::fromWire($result->values()) : null);
        }, reportFailure: false);
    }

    public function close(): void
    {
        if ($this->closed) {
            return;
        }
        $this->closed = true;
        unset(self::$sessions[$this->id]);
        if ($this->opened) {
            NativeModules::call(self::MODULE, 'close', ['sessionId' => $this->id], static fn (NativeModuleResult $result): null => null);
        }
    }

    /** @internal Delivers one native event to the registered listeners. */
    public function dispatch(RtcEvent $event): void
    {
        if ($event->kind === RtcEventKind::ConnectionState && $event->state !== null) {
            $this->state = $event->state;
        }
        foreach ($this->listeners as $listener) {
            $listener($event);
        }
    }

    /** @return array<string, string|int|float|bool> */
    public function nativeConfiguration(): array
    {
        return [
            'sessionId' => $this->id,
            'iceServersJson' => json_encode(
                array_map(static fn (IceServer $server): array => $server->toArray(), $this->iceServers),
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
            ),
            'video' => $this->video,
            'facing' => $this->facing->value,
            'width' => $this->width,
            'height' => $this->height,
            'fps' => $this->fps,
            'relayOnly' => $this->relayOnly,
        ];
    }

    /**
     * @param array<string, string|int|float|bool> $values
     * @param (Closure(NativeModuleResult): void)|null $callback
     */
    private function command(string $method, array $values = [], ?Closure $callback = null, bool $reportFailure = true): self
    {
        if ($this->closed) {
            throw new LogicException("Peer connection {$this->id} is closed.");
        }
        $this->open();
        NativeModules::call(
            self::MODULE,
            $method,
            ['sessionId' => $this->id, ...$values],
            function (NativeModuleResult $result) use ($callback, $reportFailure, $method): void {
                if (!$result->succeeded() && $reportFailure && $callback === null) {
                    $this->dispatch(new RtcEvent(RtcEventKind::Failure, $this->id, message: "{$method}: {$result->message()}"));
                }
                if ($callback !== null) {
                    $callback($result);
                }
            },
        );

        return $this;
    }

    private function open(): void
    {
        if ($this->opened) {
            return;
        }
        $this->opened = true;
        NativeModules::call(self::MODULE, 'create', $this->nativeConfiguration(), function (NativeModuleResult $result): void {
            if (!$result->succeeded()) {
                $this->dispatch(new RtcEvent(RtcEventKind::Failure, $this->id, message: $result->message()));
            }
        });
        $this->listen();
    }

    private function listen(): void
    {
        if ($this->closed) {
            return;
        }
        NativeModules::call(self::MODULE, 'next', ['sessionId' => $this->id], function (NativeModuleResult $result): void {
            if ($this->closed || !$result->succeeded()) {
                return;
            }
            $this->dispatch(RtcEvent::fromWire($this->id, $result->values()));
            $this->listen();
        });
    }

    private function assertConfigurable(): void
    {
        if ($this->opened || $this->closed) {
            throw new LogicException('Configure the peer connection before its first operation.');
        }
    }

    /**
     * @param Closure(?SessionDescription, string): void $done
     * @return Closure(NativeModuleResult): void
     */
    private static function description(Closure $done): Closure
    {
        return static function (NativeModuleResult $result) use ($done): void {
            if (!$result->succeeded()) {
                $done(null, $result->message());

                return;
            }
            $values = $result->values();
            $done(new SessionDescription(
                SdpType::tryFrom((int) ($values['type'] ?? 0)) ?? SdpType::Offer,
                (string) ($values['sdp'] ?? ''),
            ), '');
        };
    }
}
