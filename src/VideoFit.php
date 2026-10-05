<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** How video frames fill the renderer bounds. */
enum VideoFit: int
{
    case Cover = 1;
    case Contain = 2;
}
