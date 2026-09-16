package com.freesexarab.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreeSexArabPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FreeSexArabProvider())
    }
}