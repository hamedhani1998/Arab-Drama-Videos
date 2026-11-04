package com.kingdomdrama.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KingdomDramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KingdomDramaProvider())
    }
}
