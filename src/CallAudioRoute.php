<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Active call audio output. */
enum CallAudioRoute: int
{
    case Earpiece = 1;
    case Speaker = 2;
    case WiredHeadset = 3;
    case Bluetooth = 4;
}
