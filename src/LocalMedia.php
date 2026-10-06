<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Shared local media stream for group (mesh) calls.
 *
 * One microphone track and one camera capture are opened natively and the
 * same tracks are added to every peer connection configured with
 * `PeerConnection::localMedia()`. Microphone, camera and front/back switching
 * act on that single capture, so they apply to every peer at once. Peers can
 * join and leave while the capture keeps running; closing the stream detaches
 * it from the remaining peers and releases the camera.
 *
 * Render the local preview once with `RtcVideoView::preview($media)`.
 */
final class LocalMedia
{
    /** @var array<string, self> */
    private static array $streams = [];

    private bool $video = false;
    private CameraFacing $facing = CameraFacing::Front;
    private int $width = 1280;
    private int $height = 720;
    private int $fps = 30;
    private bool $opened = false;
    private bool $closed = false;
    private bool $started = false;
    private bool $capturingVideo = false;
    private bool $microphone = true;
    private bool $camera = true;

    /** @var list<Closure(string): void> */
    private array $failureListeners = [];

    private function __construct(public readonly string $id)
    {
    }

    public static function create(?string $id = null): self
    {
        $id ??= 'media-'.bin2hex(random_bytes(8));
        if (preg_match('/^[A-Za-z0-9_-]{1,128}$/D', $id) !== 1) {
            throw new InvalidArgumentException('Local media ids may contain only letters, digits, "-" and "_".');
        }
        if (isset(self::$streams[$id]) || PeerConnection::find($id) !== null) {
            throw new LogicException("Local media {$id} already exists.");
        }

        return self::$streams[$id] = new self($id);
    }

    public static function find(string $id): ?self
    {
        return self::$streams[$id] ?? null;
    }

    /** @return list<self> */
    public static function all(): array
    {
        return array_values(self::$streams);
    }

    public static function closeAll(): void
    {
        foreach (self::$streams as $stream) {
            $stream->close();
        }
    }

    /** Captures the camera too (otherwise the stream carries the microphone only). */
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

    /**
     * Opens the microphone (and camera) once. Peers using the stream start it
     * by themselves; call this to show the preview before any peer exists.
     *
     * @param (Closure(bool, string): void)|null $done
     */
    public function start(?Closure $done = null): self
    {
        return $this->command('mediaStart', [], function (NativeModuleResult $result) use ($done): void {
            if ($result->succeeded()) {
                $this->started = true;
                $this->apply($result->values());
            } elseif ($done === null) {
                $this->fail("mediaStart: {$result->message()}");
            }
            if ($done !== null) {
                $done($result->succeeded(), $result->succeeded() ? '' : $result->message());
            }
        });
    }

    /** Mutes or unmutes the microphone on every peer. */
    public function enableMicrophone(bool $enabled = true): self
    {
        $this->microphone = $enabled;

        return $this->command('mediaSetMicrophone', ['enabled' => $enabled]);
    }

    /**
     * Stops or resumes the camera on every peer (the track stays negotiated).
     *
     * @param (Closure(bool): void)|null $done
     */
    public function enableCamera(bool $enabled = true, ?Closure $done = null): self
    {
        $previous = $this->camera;
        $this->camera = $enabled;

        return $this->command('mediaSetCamera', ['enabled' => $enabled], function (NativeModuleResult $result) use ($done, $previous): void {
            if (!$result->succeeded()) {
                $this->camera = $previous;
                if ($done === null) {
                    $this->fail("mediaSetCamera: {$result->message()}");
                }
            }
            if ($done !== null) {
                $done($result->succeeded());
            }
        });
    }

    /**
     * Switches between the front and back camera for every peer.
     *
     * @param (Closure(?CameraFacing): void)|null $done receives null when the switch failed
     */
    public function switchCamera(?Closure $done = null): self
    {
        return $this->command('mediaSwitchCamera', [], function (NativeModuleResult $result) use ($done): void {
            $facing = $result->succeeded() ? CameraFacing::tryFrom((int) ($result->values()['facing'] ?? 0)) : null;
            if ($facing !== null) {
                $this->facing = $facing;
            }
            if ($done !== null) {
                $done($facing);
            }
        });
    }

    /** @param Closure(string): void $handler receives failures of commands sent without a callback */
    public function onFailure(Closure $handler): self
    {
        $this->failureListeners[] = $handler;

        return $this;
    }

    public function hasVideo(): bool
    {
        return $this->video;
    }

    /** The camera is open and producing the shared video track. */
    public function isCapturingVideo(): bool
    {
        return $this->capturingVideo;
    }

    public function isStarted(): bool
    {
        return $this->started;
    }

    public function isMicrophoneEnabled(): bool
    {
        return $this->microphone;
    }

    public function isCameraEnabled(): bool
    {
        return $this->camera;
    }

    public function currentFacing(): CameraFacing
    {
        return $this->facing;
    }

    public function isClosed(): bool
    {
        return $this->closed;
    }

    /** Detaches the stream from every peer and releases the capture. */
    public function close(): void
    {
        if ($this->closed) {
            return;
        }
        $this->closed = true;
        unset(self::$streams[$this->id]);
        if ($this->opened) {
            NativeModules::call(PeerConnection::MODULE, 'mediaClose', ['mediaId' => $this->id], static fn (NativeModuleResult $result): null => null);
        }
    }

    /** @return array<string, string|int|bool> */
    public function nativeConfiguration(): array
    {
        return [
            'mediaId' => $this->id,
            'video' => $this->video,
            'facing' => $this->facing->value,
            'width' => $this->width,
            'height' => $this->height,
            'fps' => $this->fps,
        ];
    }

    /** @internal Registers the stream natively before a peer references it. */
    public function open(): void
    {
        if ($this->closed) {
            throw new LogicException("Local media {$this->id} is closed.");
        }
        if ($this->opened) {
            return;
        }
        $this->opened = true;
        NativeModules::call(PeerConnection::MODULE, 'mediaCreate', $this->nativeConfiguration(), function (NativeModuleResult $result): void {
            if (!$result->succeeded()) {
                $this->fail("mediaCreate: {$result->message()}");
            }
        });
    }

    /**
     * @param array<string, string|int|float|bool> $values
     * @param (Closure(NativeModuleResult): void)|null $callback
     */
    private function command(string $method, array $values = [], ?Closure $callback = null): self
    {
        $this->open();
        NativeModules::call(
            PeerConnection::MODULE,
            $method,
            ['mediaId' => $this->id, ...$values],
            function (NativeModuleResult $result) use ($callback, $method): void {
                if ($result->succeeded()) {
                    $this->apply($result->values());
                }
                if ($callback !== null) {
                    $callback($result);
                } elseif (!$result->succeeded()) {
                    $this->fail("{$method}: {$result->message()}");
                }
            },
        );

        return $this;
    }

    /** @param array<string, mixed> $values native stream snapshot */
    private function apply(array $values): void
    {
        if (array_key_exists('video', $values)) {
            $this->capturingVideo = (bool) $values['video'];
        }
        $facing = CameraFacing::tryFrom((int) ($values['facing'] ?? 0));
        if ($facing !== null) {
            $this->facing = $facing;
        }
    }

    private function fail(string $message): void
    {
        foreach ($this->failureListeners as $listener) {
            $listener($message);
        }
    }

    private function assertConfigurable(): void
    {
        if ($this->opened || $this->closed) {
            throw new LogicException('Configure the local media before its first operation.');
        }
    }
}
