package com.dramatip.plugin

import com.lagradost.cloudstream3.utils.SubtitleHelper

/**
 * اسم اللغة في قائمة الترجمات: العربية أولاً ثم اللاتينية — «العربية Arabic».
 *
 * زوجٌ مزدوج لا اسمٌ واحد، لأن `SubtitleFile.lang` يقرأه التطبيق في ثلاثة
 * مواضع متعارضة:
 * - العرض: قائمة الترجمات تجمع بـ`originalName` وتكتب المفتاح حرفيًا
 *   (GeneratorPlayer)، فالرمز «ar» يظهر هكذا.
 * - الوسم: `langTag` يقرأ الرمز أولًا وإلاّ الاسم نصف-مطابقة؛ والاسم العربي
 *   وحده يعطي وسماً فارغًا فيسقط الاختيار التلقائي والحفظ.
 * - المرشِّح: فلتر «لغة الترجمة» يقارن `originalName` بأسماء إنجليزية صغيرة،
 *   فلا يمرّ «ar» ولا «العربية» وحدهما — ولا يمرّ إلا الزوج اللاتيني.
 *
 * كلُّ فشلٍ في الاشتقاق يُعيد `raw` كما هو: لا اختراعٌ ولا رفض.
 */
internal fun subLangLabel(raw: String): String {
    if (raw.isEmpty()) return raw
    val tag = SubtitleHelper.fromCodeToLangTagIETF(raw)
        ?: SubtitleHelper.fromLanguageToTagIETF(raw, true)
        ?: KNOWN_SUB_LANG[raw.trim()]
        ?: return raw
    val ar = SubtitleHelper.fromTagToLanguageName(tag, "ar") ?: return raw
    val en = SubtitleHelper.fromTagToEnglishLanguageName(tag) ?: return raw
    return if (ar.equals(en, true)) en else "$ar $en"
}

private val KNOWN_SUB_LANG = mapOf(
    "العربية" to "ar",
    "الإنجليزية" to "en",
    "الفرنسية" to "fr",
    "الألمانية" to "de",
    "الإسبانية" to "es",
    "البرتغالية" to "pt",
    "الإندونيسية" to "id",
    "الصينية" to "zh",
    "التركية" to "tr",
    "الروسية" to "ru",
    "الهندية" to "hi",
    "الإيطالية" to "it",
    "اليابانية" to "ja",
    "الكورية" to "ko",
    "الهولندية" to "nl",
    "السويدية" to "sv",
    "النرويجية" to "no",
    "الدنماركية" to "da",
    "الفنلندية" to "fi",
    "البولندية" to "pl",
    "التايلاندية" to "th",
    "الفيتنامية" to "vi",
    "الماليزية" to "ms",
    "الفلبينية" to "fil",
    "日本語" to "ja",
    "한국어" to "ko",
    "中文" to "zh",
    "繁體中文" to "zh",
    "简体中文" to "zh",
    "हिन्दी" to "hi",
    "हिंदी" to "hi",
)