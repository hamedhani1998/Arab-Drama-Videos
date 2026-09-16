package com.arabplugins.extractors

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabPluginsExtractors : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(KVSFlashvarsExtractor())
        registerExtractorAPI(PlayerIzExtractor())
    }
}