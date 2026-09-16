package com.sexallarab.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexAllArabPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SexAllArabProvider())
    }
}