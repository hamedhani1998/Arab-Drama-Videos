package com.moviesmod.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MoviesModPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviesModProvider())
    }
}