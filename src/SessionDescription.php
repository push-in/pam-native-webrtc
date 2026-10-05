<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use InvalidArgumentException;

/** SDP offer or answer exchanged through the application's signaling channel. */
final readonly class SessionDescription
{
    public function __construct(
        public SdpType $type,
        public string $sdp,
    ) {
        if ($sdp === '' || strlen($sdp) > 512 * 1024) {
            throw new InvalidArgumentException('SDP must be between 1 byte and 512 KiB.');
        }
    }

    public static function offer(string $sdp): self
    {
        return new self(SdpType::Offer, $sdp);
    }

    public static function answer(string $sdp): self
    {
        return new self(SdpType::Answer, $sdp);
    }

    /** Accepts the W3C JSON shape (`{"type":"offer","sdp":"..."}`) used by most signaling servers. */
    public static function fromArray(array $value): self
    {
        $type = $value['type'] ?? null;
        $type = match (true) {
            $type instanceof SdpType => $type,
            is_int($type) => SdpType::from($type),
            is_string($type) && strtolower($type) === 'offer' => SdpType::Offer,
            is_string($type) && strtolower($type) === 'answer' => SdpType::Answer,
            default => throw new InvalidArgumentException('Unsupported SDP type.'),
        };

        return new self($type, is_string($value['sdp'] ?? null) ? $value['sdp'] : '');
    }

    /** @return array{type: string, sdp: string} */
    public function toArray(): array
    {
        return ['type' => strtolower($this->type->name), 'sdp' => $this->sdp];
    }
}
