<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Native peer connection events delivered through the module event channel. */
enum RtcEventKind: int
{
    case IceCandidate = 1;
    case ConnectionState = 2;
    case RemoteTrack = 3;
    case RenegotiationNeeded = 4;
    case LocalReady = 5;
    case Failure = 6;
}
