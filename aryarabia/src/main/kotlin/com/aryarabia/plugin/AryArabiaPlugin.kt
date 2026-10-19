package com.aryarabia.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

@CloudstreamPlugin
class AryArabiaPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("ARY", Context.MODE_PRIVATE)
        registerMainAPI(AryProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                ArySettingsBottomSheet.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}