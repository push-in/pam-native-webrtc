<?php

declare(strict_types=1);

use App\LoopbackCall;
use Pam\Native\App;

require __DIR__.'/vendor/autoload.php';

App::theme(\Pam\Native\Theme::pamLab());
App::run(new LoopbackCall());
