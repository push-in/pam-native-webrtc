<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use Pam\Native\Element;
use Pam\Native\Renderable;
use Pam\Native\UI\CustomView;

/**
 * GPU video renderer bound to a peer connection track.
 *
 * Frames are drawn natively through a shared EGL context into a TextureView,
 * so the view composes with overlays and never crosses into PHP per frame.
 * The renderer rebinds by itself when the track appears or changes.
 */
final class RtcVideoView implements Renderable
{
    private VideoFit $fit = VideoFit::Cover;
    private bool $mirror = false;

    private function __construct(
        private readonly string $sessionId,
        private readonly RtcTrack $track,
    ) {
    }

    public static function make(PeerConnection|LocalMedia|string $session, RtcTrack $track = RtcTrack::Remote): self
    {
        if ($session instanceof LocalMedia) {
            $track = RtcTrack::Local;
        }
        $view = new self($session instanceof PeerConnection || $session instanceof LocalMedia ? $session->id : $session, $track);
        $view->mirror = $track === RtcTrack::Local;

        return $view;
    }

    public static function local(PeerConnection|LocalMedia|string $session): self
    {
        return self::make($session, RtcTrack::Local);
    }

    /** The one local preview of a shared stream (mirrored; same track every peer sends). */
    public static function preview(LocalMedia|string $media): self
    {
        return self::make($media, RtcTrack::Local);
    }

    public static function remote(PeerConnection|string $session): self
    {
        return self::make($session, RtcTrack::Remote);
    }

    /**
     * Builds the view from template attributes (`<RtcVideoView session="..." track="local" fit="contain" />`,
     * or `<RtcVideoView :media="$localMedia" />` for a shared stream preview).
     */
    public static function fromProps(array $props): self
    {
        $session = $props['session'] ?? $props['sessionId'] ?? $props['media'] ?? '';
        $track = self::option($props['track'] ?? null, RtcTrack::class, isset($props['media']) ? RtcTrack::Local : RtcTrack::Remote);
        $view = self::make($session instanceof PeerConnection || $session instanceof LocalMedia ? $session : (string) $session, $track);
        $view = $view->fit(self::option($props['fit'] ?? null, VideoFit::class, VideoFit::Cover));
        if (array_key_exists('mirror', $props)) {
            $view = $view->mirror(filter_var($props['mirror'], FILTER_VALIDATE_BOOLEAN));
        }

        return $view;
    }

    public function fit(VideoFit $fit): self
    {
        $copy = clone $this;
        $copy->fit = $fit;

        return $copy;
    }

    public function mirror(bool $mirror = true): self
    {
        $copy = clone $this;
        $copy->mirror = $mirror;

        return $copy;
    }

    /** @return array<string, string|int|bool> */
    public function properties(): array
    {
        return [
            'sessionId' => $this->sessionId,
            'track' => $this->track->value,
            'fit' => $this->fit->value,
            'mirror' => $this->mirror,
        ];
    }

    public function toElement(): Element
    {
        return CustomView::make('webrtc.video', $this->properties());
    }

    /**
     * @template T of \BackedEnum
     * @param class-string<T> $enum
     * @param T $default
     * @return T
     */
    private static function option(mixed $value, string $enum, \BackedEnum $default): \BackedEnum
    {
        if ($value instanceof $enum) {
            return $value;
        }
        if (is_int($value) || (is_string($value) && ctype_digit($value))) {
            return $enum::tryFrom((int) $value) ?? $default;
        }
        if (is_string($value)) {
            foreach ($enum::cases() as $case) {
                if (strcasecmp($case->name, $value) === 0) {
                    return $case;
                }
            }
        }

        return $default;
    }
}
