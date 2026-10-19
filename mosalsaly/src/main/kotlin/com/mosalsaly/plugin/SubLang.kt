package com.mosalsaly.plugin

import com.lagradost.cloudstream3.utils.SubtitleHelper

/**
 * اسم اللغة في قائمة الترجمات: العربية أولاً ثم اللاتينية — «العربية Arabic».
 *
 * زوجٌ مزدوج لا اسمٌ واحد، لأن `SubtitleFile.lang` يقرأه التطبيق في ثلاثة
 * مواضع متعارضة:
 * - العرض: قائمة الترجمات تجمع بـ`originalName` وتكتب المفتاح حرفياً
 *   (GeneratorPlayer)، فالرمز «ar» يظهر هكذا — وهي شكاية «لا تظهر بشكل صحيح».
 * - الوسم: `langTag` يقرأ الرمز أولاً وإلاّ الاسم نصف-مطابقة؛ والاسم العربي
 *   وحده يعطي وسماً فارغاً («الإنجليزية» ليست في خرائط SubtitleHelper)،
 *   فيسقط الاختيار التلقائي بين الحلقات وحفظ الاختيار.
 * - المرشِّح: فلتر «لغة الترجمة» يقارن `originalName` بأسماء إنجليزية صغيرة،
 *   فلا يمرّ «ar» ولا «العربية» وحدهما — ولا يمرّ إلا الزوج اللاتيني.
 *
 * كلُّ فشلٍ في الاشتقاق يُعيد `raw` كما هو: لا اختراعٌ ولا رفض.
 * (قِيس كلُّ مسارٍ أدناه على cloudstream.jar 2026-10-08.)
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

/**
 * خامٌ لا تعرفه خرائط SubtitleHelper: أسماءٌ عربية ترسلها بعض المصادر
 * (الخرائط بالإنجليزية والأصلية فحسب) وأسماء شرقآسيوية أصلية تُطابَق
 * حرفياً فقط. القيم رموزٌ معلومة — جُرِّبت كلّها على الـjar نفسه.
 */
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
