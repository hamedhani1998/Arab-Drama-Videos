package com.sexalarab.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexAlArabPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SexAlArabProvider())
    }
}
