package com.threesk.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ThreeSkPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ThreeSk())
    }
}