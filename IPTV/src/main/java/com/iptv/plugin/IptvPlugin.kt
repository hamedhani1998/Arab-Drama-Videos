package com.iptv.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IptvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IptvOrgProvider())
        registerMainAPI(FreeTvProvider())
    }
}