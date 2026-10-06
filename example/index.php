<?php

declare(strict_types=1);

use App\GroupLoopbackCall;
use App\LoopbackCall;
use Pam\Native\App;

require __DIR__.'/vendor/autoload.php';

App::theme(\Pam\Native\Theme::pamLab());
// 'group' runs the shared-camera mesh demo (one LocalMedia, two peers) instead of the 1:1 call.
const DEMO = 'single';

App::run(DEMO === 'group' ? new GroupLoopbackCall() : new LoopbackCall());
