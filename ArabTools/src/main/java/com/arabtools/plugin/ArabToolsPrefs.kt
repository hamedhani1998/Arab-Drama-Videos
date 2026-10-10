package com.arabtools.plugin

import android.content.SharedPreferences

/**
 * مفاتيح إعدادات «أدوات عربية» وقيمها الافتراضية.
 *
 * كل المفاتيح ببادئة `art_` فلا تتصادم مع أي وحدة أخرى تشترك معنا في عملية
 * التطبيق الواحدة. الملف نفسه يحمل اسم الإضافة، ويُمرَّر من [ArabToolsPlugin]
 * إلى شاشة الإعدادات وإلى محرّك التعديلات.
 */
object ArabToolsPrefs {

    /** اسم ملف التفضيلات — يطابق ما يمرّره [ArabToolsPlugin]. */
    const val PREFS_NAME = "ArabTools"

    /** حزمة التطبيق المضيف: بها نبحث عن معرّفات العرض الحقيقية. */
    const val APP_PACKAGE = "com.lagradost.cloudstream3"

    // (١) عام — المفتاح الرئيسي والوضع الآمن.
    const val MASTER = "art_master"           // Boolean — تفعيل التعديلات
    const val SAFE_MODE = "art_safe_mode"     // Boolean — إيقاف مؤقت لكل التعديلات

    // (٢) المشغّل.
    const val PLAYER_LANDSCAPE = "art_player_landscape" // Boolean — تدوير أفقي عند التشغيل

    // (٣) المظهر.
    const val NIGHT_MODE = "art_night_mode"     // "auto" | "dark" | "light"
    const val HIDE_STATUS = "art_hide_status"   // Boolean — إخفاء شريط الحالة
    const val STATUS_COLOR = "art_status_color" // "default" | "black" | "dark" | "blue" | ...
    const val NAV_COLOR = "art_nav_color"       // مثل أعلاه — شريط أزرار النظام السفلي

    // (٤) الرئيسية والتنقل.
    const val NAV_HIDE_BAR = "art_nav_hide_bar"                 // Boolean — إخفاء الشريط كاملاً
    const val NAV_HIDE_HOME = "art_nav_hide_home"               // Boolean — إخفاء تبويب الرئيسية
    const val NAV_HIDE_SEARCH = "art_nav_hide_search"           // Boolean — إخفاء تبويب البحث
    const val NAV_HIDE_LIBRARY = "art_nav_hide_library"         // Boolean — إخفاء تبويب المكتبة
    const val NAV_HIDE_DOWNLOADS = "art_nav_hide_downloads"     // Boolean — إخفاء تبويب التحميلات
    const val NAV_HIDE_SETTINGS = "art_nav_hide_settings"       // Boolean — إخفاء تبويب الإعدادات

    // مفاتيح داخلية (لا تظهر في الشاشة) — حماية من تكرار توقّف التطبيق.
    const val BOOT_COUNT = "art_boot_count"     // Int — بصمات التشغيل المتقاربة
    const val BOOT_TS = "art_boot_ts"           // Long — وقت آخر بصمة تشغيل
    const val WAS_DISABLED = "art_was_disabled" // Boolean — أُوقفت التعديلات تلقائياً

    /**
     * تبويبات شريط التنقل السفلي في التطبيق المضيف.
     *
     * المعرّف في [Tab.resName] هو اسم معرّف حقيقي في `R.id` الخاص بالتطبيق
     * (مقروء من حزمة التطبيق نفسها)، ولا نستورده وقت الترجمة حتى لا تتعلّق
     * الإضافة بإصدار معيّن: نبحث عنه وقت التشغيل بـ`getIdentifier`.
     */
    data class Tab(val resName: String, val key: String, val label: String)

    val TABS: List<Tab> = listOf(
        Tab("navigation_home", NAV_HIDE_HOME, "الرئيسية"),
        Tab("navigation_search", NAV_HIDE_SEARCH, "البحث"),
        Tab("navigation_library", NAV_HIDE_LIBRARY, "المكتبة"),
        Tab("navigation_downloads", NAV_HIDE_DOWNLOADS, "التحميلات"),
        Tab("navigation_settings", NAV_HIDE_SETTINGS, "الإعدادات")
    )

    /** كل المفاتيح الظاهرة في الشاشة — تستخدمها «إعادة الضبط». */
    val SETTING_KEYS: List<String> = listOf(
        MASTER, SAFE_MODE,
        PLAYER_LANDSCAPE,
        NIGHT_MODE, HIDE_STATUS, STATUS_COLOR, NAV_COLOR,
        NAV_HIDE_BAR, NAV_HIDE_HOME, NAV_HIDE_SEARCH, NAV_HIDE_LIBRARY,
        NAV_HIDE_DOWNLOADS, NAV_HIDE_SETTINGS
    )

    fun reset(prefs: SharedPreferences) {
        val editor = prefs.edit()
        SETTING_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }
}
