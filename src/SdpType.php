<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Session description role. */
enum SdpType: int
{
    case Offer = 1;
    case Answer = 2;
}
