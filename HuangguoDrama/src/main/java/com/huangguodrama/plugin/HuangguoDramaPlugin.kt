package com.huangguodrama.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HuangguoDramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HuangguoDramaProvider())
    }
}
