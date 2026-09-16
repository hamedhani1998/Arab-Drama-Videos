package com.sexsatee.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexSateePlugin : Plugin() {
    override fun load() {
        registerMainAPI(SexSateeProvider())
    }
}
