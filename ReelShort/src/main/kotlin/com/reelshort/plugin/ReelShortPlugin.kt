package com.reelshort.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelShortPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ReelShortProvider())
    }
}