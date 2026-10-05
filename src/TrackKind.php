<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Media track kind. */
enum TrackKind: int
{
    case Audio = 1;
    case Video = 2;
}
