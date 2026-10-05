<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Capture camera direction. */
enum CameraFacing: int
{
    case Front = 1;
    case Back = 2;
}
