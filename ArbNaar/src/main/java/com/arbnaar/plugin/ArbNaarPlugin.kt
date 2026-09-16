package com.arbnaar.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArbNaarPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArbNaarProvider())
    }
}