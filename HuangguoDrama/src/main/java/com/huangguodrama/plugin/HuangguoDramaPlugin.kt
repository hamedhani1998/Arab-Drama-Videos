package com.huangguodrama.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HuangguoDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («HuangguoDrama») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        val prefs = context.getSharedPreferences(HuangguoDramaSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        // `applicationContext` لا `context`: ورقة الإعدادات تُعرض بعد أن تُدمَّر
        // الـActivity، فاحتفاظ بسياق التطبيق وحده يمنع تسريب Activity مغلقة.
        registerMainAPI(HuangguoDramaProvider(prefs, context.applicationContext))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) HuangguoDramaSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
