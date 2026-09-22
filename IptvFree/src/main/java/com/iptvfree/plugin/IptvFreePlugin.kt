package com.iptvfree.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IptvFreePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IptvFreeProvider())
    }
}