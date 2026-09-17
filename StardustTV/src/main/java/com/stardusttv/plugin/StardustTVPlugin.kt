package com.stardusttv.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StardustTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StardustTVProvider())
    }
}
