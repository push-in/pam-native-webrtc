<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Compact snapshot of the selected candidate pair and RTP streams. */
final readonly class RtcStats
{
    public function __construct(
        public int $bytesSent,
        public int $bytesReceived,
        public int $packetsLost,
        public float $roundTripTimeMs,
        public float $jitterMs,
        public float $inboundFramesPerSecond,
        public int $availableOutgoingBitrate,
        public bool $relayed,
    ) {
    }

    /** @param array<string, string|int|float|bool> $values */
    public static function fromWire(array $values): self
    {
        return new self(
            bytesSent: (int) ($values['bytesSent'] ?? 0),
            bytesReceived: (int) ($values['bytesReceived'] ?? 0),
            packetsLost: (int) ($values['packetsLost'] ?? 0),
            roundTripTimeMs: (float) ($values['roundTripTimeMs'] ?? 0.0),
            jitterMs: (float) ($values['jitterMs'] ?? 0.0),
            inboundFramesPerSecond: (float) ($values['inboundFramesPerSecond'] ?? 0.0),
            availableOutgoingBitrate: (int) ($values['availableOutgoingBitrate'] ?? 0),
            relayed: (bool) ($values['relayed'] ?? false),
        );
    }
}
