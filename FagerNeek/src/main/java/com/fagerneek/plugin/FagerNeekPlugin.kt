package com.fagerneek.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FagerNeekPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FagerNeekProvider())
    }
}
