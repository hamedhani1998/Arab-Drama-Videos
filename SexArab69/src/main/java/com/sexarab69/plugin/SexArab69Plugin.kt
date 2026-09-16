package com.sexarab69.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexArab69Plugin : Plugin() {
    override fun load() {
        registerMainAPI(SexArab69Provider())
    }
}