<?php

declare(strict_types=1);

namespace Pam\Native\WebRtc;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;

final class WebRtcPluginProvider implements PluginProvider
{
    public function register(): void
    {
        TemplateRegistry::component('RtcVideoView', static fn (array $props): RtcVideoView => RtcVideoView::fromProps($props));
    }

    public function boot(): void
    {
    }
}
