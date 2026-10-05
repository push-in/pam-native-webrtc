<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Which video track a renderer displays. */
enum RtcTrack: int
{
    case Local = 1;
    case Remote = 2;
}
