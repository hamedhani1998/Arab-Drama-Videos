package com.arabnok.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabNokPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabNokProvider())
    }
}