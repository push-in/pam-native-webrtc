<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Aggregated RTCPeerConnection connection state. */
enum RtcConnectionState: int
{
    case New = 1;
    case Connecting = 2;
    case Connected = 3;
    case Disconnected = 4;
    case Failed = 5;
    case Closed = 6;
}
