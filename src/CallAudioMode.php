<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

/** Call audio profile: voice calls default to the earpiece, video calls to the speaker. */
enum CallAudioMode: int
{
    case Voice = 1;
    case Video = 2;
}
