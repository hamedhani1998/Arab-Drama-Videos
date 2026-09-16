package com.aflamk1.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Aflamk1Plugin : Plugin() {
    override fun load() {
        registerMainAPI(Aflamk1Provider())
    }
}
