<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Typed event emitted by a native peer connection. */
final readonly class RtcEvent
{
    public function __construct(
        public RtcEventKind $kind,
        public string $sessionId,
        public ?IceCandidate $candidate = null,
        public ?RtcConnectionState $state = null,
        public ?TrackKind $track = null,
        public string $message = '',
    ) {
    }

    /** @param array<string, string|int|float|bool> $values */
    public static function fromWire(string $sessionId, array $values): self
    {
        $kind = RtcEventKind::tryFrom((int) ($values['kind'] ?? 0)) ?? RtcEventKind::Failure;

        return new self(
            kind: $kind,
            sessionId: $sessionId,
            candidate: $kind === RtcEventKind::IceCandidate
                ? new IceCandidate(
                    (string) ($values['candidate'] ?? ''),
                    (string) ($values['sdpMid'] ?? ''),
                    (int) ($values['sdpMLineIndex'] ?? 0),
                )
                : null,
            state: $kind === RtcEventKind::ConnectionState
                ? RtcConnectionState::tryFrom((int) ($values['state'] ?? 0))
                : null,
            track: $kind === RtcEventKind::RemoteTrack
                ? TrackKind::tryFrom((int) ($values['track'] ?? 0))
                : null,
            message: (string) ($values['message'] ?? ''),
        );
    }
}
