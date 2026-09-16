package com.sex6x.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Sex6xPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Sex6xProvider())
    }
}
