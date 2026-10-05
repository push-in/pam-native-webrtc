<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use Closure;
use LogicException;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

/**
 * Call audio session: communication audio focus and mode, speaker, earpiece,
 * wired and Bluetooth routing, ringtone, ringback and the proximity wake lock.
 *
 * Every fluent call is applied natively in order. Only one call audio session
 * is active at a time; starting a new one replaces the previous session.
 */
final class CallAudio
{
    public const string MODULE = 'call-audio';

    private static ?self $active = null;

    private bool $stopped = false;
    private ?CallAudioRoute $route = null;

    /** @var list<Closure(CallAudioRoute): void> */
    private array $routeListeners = [];

    private function __construct(public readonly CallAudioMode $mode)
    {
    }

    public static function start(CallAudioMode $mode = CallAudioMode::Voice): self
    {
        self::$active?->release(sendStop: false);
        $audio = self::$active = new self($mode);
        $audio->send('start', ['mode' => $mode->value]);
        $audio->listen();

        return $audio;
    }

    public static function active(): ?self
    {
        return self::$active;
    }

    public static function stopActive(): void
    {
        self::$active?->stop();
    }

    /** Routes to the loudspeaker (true) or back to the earpiece/headset (false). */
    public function speaker(bool $enabled = true): self
    {
        return $this->send('setSpeaker', ['enabled' => $enabled]);
    }

    /** Prefers a connected Bluetooth headset (SCO / LE audio) over the built-in outputs. */
    public function bluetooth(bool $prefer = true): self
    {
        return $this->send('setBluetooth', ['enabled' => $prefer]);
    }

    /** Plays the standard ringback tone on the voice stream while an outgoing call rings. */
    public function ringback(bool $play = true): self
    {
        return $this->send('setRingback', ['enabled' => $play]);
    }

    /** Plays the device ringtone (and vibration pattern when allowed) for an in-app incoming call. */
    public function ringtone(bool $play = true): self
    {
        return $this->send('setRingtone', ['enabled' => $play]);
    }

    /** Turns the screen off while the phone is held at the ear and the earpiece is active. */
    public function proximity(bool $enabled = true): self
    {
        return $this->send('setProximity', ['enabled' => $enabled]);
    }

    /** Mutes the device microphone for every audio client. Prefer PeerConnection::enableMicrophone() per call. */
    public function mute(bool $muted = true): self
    {
        return $this->send('setMicrophoneMute', ['muted' => $muted]);
    }

    /** @param Closure(CallAudioRoute): void $handler */
    public function onRouteChange(Closure $handler): self
    {
        $this->routeListeners[] = $handler;

        return $this;
    }

    public function route(): ?CallAudioRoute
    {
        return $this->route;
    }

    public function isActive(): bool
    {
        return !$this->stopped;
    }

    /** Restores the previous audio mode, abandons focus, stops tones and releases the wake lock. */
    public function stop(): void
    {
        $this->release(sendStop: true);
    }

    /** @internal */
    public function dispatchRoute(CallAudioRoute $route): void
    {
        $this->route = $route;
        foreach ($this->routeListeners as $listener) {
            $listener($route);
        }
    }

    /** @param array<string, string|int|float|bool> $values */
    private function send(string $method, array $values): self
    {
        if ($this->stopped) {
            throw new LogicException('The call audio session has been stopped.');
        }
        NativeModules::call(self::MODULE, $method, $values, static fn (NativeModuleResult $result): null => null);

        return $this;
    }

    private function listen(): void
    {
        NativeModules::call(self::MODULE, 'next', [], function (NativeModuleResult $result): void {
            if ($this->stopped || !$result->succeeded()) {
                return;
            }
            $route = CallAudioRoute::tryFrom((int) ($result->values()['route'] ?? 0));
            if ($route !== null) {
                $this->dispatchRoute($route);
            }
            $this->listen();
        });
    }

    private function release(bool $sendStop): void
    {
        if ($this->stopped) {
            return;
        }
        if ($sendStop) {
            NativeModules::call(self::MODULE, 'stop', [], static fn (NativeModuleResult $result): null => null);
        }
        $this->stopped = true;
        if (self::$active === $this) {
            self::$active = null;
        }
    }
}
