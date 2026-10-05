<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use InvalidArgumentException;

/** STUN or TURN server used for ICE candidate gathering. */
final readonly class IceServer
{
    /** @param list<string> $urls */
    private function __construct(
        public array $urls,
        public string $username = '',
        public string $credential = '',
    ) {
    }

    public static function stun(string ...$urls): self
    {
        return new self(self::urls($urls, ['stun', 'stuns']));
    }

    /** @param string|list<string> $url */
    public static function turn(string|array $url, string $username, string $credential): self
    {
        if ($username === '' || $credential === '') {
            throw new InvalidArgumentException('TURN servers require a username and credential.');
        }
        if (strlen($username) > 512 || strlen($credential) > 512) {
            throw new InvalidArgumentException('TURN credentials are too long.');
        }

        return new self(self::urls(is_array($url) ? $url : [$url], ['turn', 'turns']), $username, $credential);
    }

    /** @return array{urls: list<string>, username: string, credential: string} */
    public function toArray(): array
    {
        return ['urls' => $this->urls, 'username' => $this->username, 'credential' => $this->credential];
    }

    /**
     * @param array<array-key, string> $urls
     * @param list<string> $schemes
     * @return list<string>
     */
    private static function urls(array $urls, array $schemes): array
    {
        if ($urls === [] || count($urls) > 8) {
            throw new InvalidArgumentException('An ICE server needs between one and eight URLs.');
        }
        $valid = [];
        foreach ($urls as $url) {
            $scheme = strtolower(strstr($url, ':', true) ?: '');
            if (!in_array($scheme, $schemes, true) || strlen($url) > 512 || preg_match('/\s/', $url) === 1) {
                throw new InvalidArgumentException("Invalid ICE server URL: {$url}");
            }
            $valid[] = $url;
        }

        return $valid;
    }
}
