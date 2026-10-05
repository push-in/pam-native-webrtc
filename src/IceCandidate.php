<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use InvalidArgumentException;

/** Trickle ICE candidate. */
final readonly class IceCandidate
{
    public function __construct(
        public string $candidate,
        public string $sdpMid = '',
        public int $sdpMLineIndex = 0,
    ) {
        if (strlen($candidate) > 4096 || strlen($sdpMid) > 256 || $sdpMLineIndex < 0 || $sdpMLineIndex > 1024) {
            throw new InvalidArgumentException('Invalid ICE candidate.');
        }
    }

    /** Accepts the W3C JSON shape (`candidate`, `sdpMid`, `sdpMLineIndex`). */
    public static function fromArray(array $value): self
    {
        return new self(
            is_string($value['candidate'] ?? null) ? $value['candidate'] : '',
            is_string($value['sdpMid'] ?? null) ? $value['sdpMid'] : '',
            is_int($value['sdpMLineIndex'] ?? null) ? $value['sdpMLineIndex'] : (int) ($value['sdpMLineIndex'] ?? 0),
        );
    }

    /** @return array{candidate: string, sdpMid: string, sdpMLineIndex: int} */
    public function toArray(): array
    {
        return ['candidate' => $this->candidate, 'sdpMid' => $this->sdpMid, 'sdpMLineIndex' => $this->sdpMLineIndex];
    }
}
