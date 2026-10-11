package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════════════════════════════════════ (٥) البحث والنتائج

internal fun searchCategory() = Category(
    "البحث والنتائج",
    listOf(
        Group(
            "البحث",
            listOf(
                Def("multi_provider_search", "البحث في كل المصادر", "يستعلم كل المصادر في وقت واحد"),
                Def("search_history_enabled", "حفظ سجلّ البحث", "يحفظ ما بحثت عنه للرجوع إليه"),
                Def("recent_searches_limit", "عدد عمليات البحث المحفوظة", "كم كلمة يبقى في السجلّ", Kind.TEXT, default = "20"),
                Def("auto_clear_search_history", "حذف السجلّ تلقائياً", "يمحو سجلّ البحث بعد المدّة المحدّدة"),
                Def("auto_clear_search_days", "مدّة حفظ السجلّ (أيام)", "بعدها يُحذف تلقائياً", Kind.TEXT, default = "30"),
                Def("pull_to_search", "السحب للبحث", "انزل من أعلى الشاشة لتفتح البحث"),
                Def("voice_search", "البحث الصوتي", "يبحث بالكلام"),
                Def("search_highlight", "تظليل كلمة البحث", "يُبرز الكلمة التي بحثت عنها في النتائج"),
                Def("block_keywords", "كلمات محظورة", "نتائج تحتوي هذه الكلمات لا تظهر", Kind.TEXT, default = ""),
                Def("clear_search_history", "مسح سجلّ البحث الآن", "يحذف كل ما حفظه السجلّ فوراً")
            )
        ),
        Group(
            "بطاقات النتائج",
            listOf(
                Def("result_count", "إظهار عدد النتائج", "رقم بجانب عنوان المصدر"),
                Def("show_provider_icons", "إظهار أيقونة المصدر", "أيقونة المصدر مع كل نتيجة"),
                Def("result_tag", "إظهار وسوم النتائج", "وسوم مثل «فيلم» أو «مسلسل»"),
                Def("age_rating_badge", "إظهار تصنيف العمر", "شارة تصنيف العمر على البطاقة"),
                Def(
                    "age_rating_badge_style", "شكل شارة تصنيف العمر", "نمط الشارة", Kind.LIST,
                    entries = listOf("نص", "دائرة", "شريط"),
                    values = listOf("text", "circle", "ribbon"),
                    default = "text"
                ),
                Def("series_meta_badges", "شارات معلومات المسلسل", "سنة الإصدار والتقييم واللغة"),
                Def("series_meta_show_year", "إظهار سنة الإصدار", "السنة على البطاقة"),
                Def("series_meta_show_rating", "إظهار التقييم", "التقييم على البطاقة"),
                Def("series_meta_show_language", "إظهار اللغة", "لغة العمل على البطاقة"),
                Def("synopsis_expand", "توسيع القصة", "زر لعرض القصة كاملة"),
                Def("synopsis_max_lines", "عدد أسطر القصة", "كم سطراً تظهر قبل التوسيع", Kind.TEXT, default = "4"),
                Def("rank_badge", "إظهار رقم الترتيب", "رقم المكان في قوائم التصنيف"),
                Def("result_share", "زر مشاركة النتيجة", "يشارك رابط العمل مع الآخرين"),
                Def("result_copy_title", "زر نسخ العنوان", "ينسخ اسم العمل إلى الحافظة")
            )
        ),
        Group(
            "الحلقات",
            listOf(
                Def("episode_sort_enabled", "ترتيب الحلقات", "يرتّب قائمة الحلقات"),
                Def(
                    "episode_sort_mode", "طريقة ترتيب الحلقات", "الأساس المستعمل", Kind.LIST,
                    entries = listOf("رقم الحلقة", "تاريخ العرض", "الأحدث أولاً"),
                    values = listOf("number", "air_date", "newest_first"),
                    default = "number"
                ),
                Def("episode_sort_reverse", "عكس ترتيب الحلقات", "يقلب الترتيب"),
                Def("episode_date", "إظهار تاريخ الحلقة", "تاريخ عرض كل حلقة"),
                Def("exact_episode_dates", "تواريخ دقيقة للحلقات", "يعرض التاريخ الكامل بدل «منذ يومين»"),
                Def("episode_air_status", "إظهار حالة العرض", "هل الحلقة عُرضت أم لم تُعرض بعد"),
                Def("episode_air_status_show_watched", "تمييز الحلقات المُشاهَدة", "علامة على الحلقات التي أكملتها"),
                Def("episode_name", "إظهار اسم الحلقة", "اسم الحلقة إن وُجد"),
                Def("episode_runtime", "إظهار مدّة الحلقة", "طول الحلقة بالدقائق"),
                Def("episode_copy_title", "زر نسخ اسم الحلقة", "ينسخ العنوان إلى الحافظة"),
                Def("episode_batch_enabled", "تحديد دفعة من الحلقات", "يسمح بتحديد عدّة حلقات ودفعها للتحميل"),
                Def("episode_jump_enabled", "الانتقال السريع للحلقة", "يقفز إلى رقم حلقة تحدّده"),
                Def("episodes_side_panel", "لوحة الحلقات الجانبية", "قائمة حلقات في لوحة جانبية أثناء المشاهدة"),
                Def("episode_filter", "تصفية الحلقات", "شريط لتصفية الحلقات"),
                Def(
                    "episode_badge_text", "نص شارة الحلقة", "ما يُكتب على شارة الحلقة", Kind.TEXT, default = "الحلقة"
                ),
                Def("episode_preview", "معاينة الحلقة", "صورة مصغّرة لكل حلقة"),
                Def("episode_play_expanded", "توسيع زر التشغيل", "زر تشغيل أكبر في بطاقة الحلقة"),
                Def("episode_thumb", "صورة الحلقة", "يُظهر صورة الحلقة في القائمة")
            )
        ),
        Group(
            "التصنيف والوسوم",
            listOf(
                Def("hide_adult", "إخفاء المحتوى للبالغين", "يُخفي الأعمال المصنّفة للبالغين"),
                Def("maturity_filter_enabled", "تصفية حسب التصنيف العمر", "يُخفي ما فوق تصنيف تحدّده"),
                Def(
                    "maturity_level", "حدّ التصنيف العمري", "أقصى تصنيف يُسمح بعرضه", Kind.LIST,
                    entries = listOf("الكل", "‏+7", "‏+13", "‏+16", "‏+18"),
                    values = listOf("all", "7", "13", "16", "18"),
                    default = "all"
                ),
                Def("tvtypes_chips_scroll", "تمرير أنواع المحتوى", "شريط أفقي لأنواع المحتوى"),
                Def("sort_providers", "ترتيب المصادر", "ترتيب المصادر في القوائم"),
                Def("sort_by_alpha", "ترتيب أبجدي", "يرتّب القوائم أبجدياً"),
                Def("sort_fab", "زر الترتيب العائم", "زر عائم للترتيب السريع")
            )
        )
    )
)

// ═══════════════════════════════════════════════════ (٦) التحميلات

internal fun downloadsCategory() = Category(
    "التحميلات",
    listOf(
        Group(
            "إعدادات التحميل",
            listOf(
                Def(
                    "preferred_quality", "الجودة المفضّلة", "الجودة التي تُحمَّل افتراضياً", Kind.LIST,
                    entries = listOf("أفضل جودة", "1080p", "720p", "480p", "360p", "أقل جودة"),
                    values = listOf("best", "1080", "720", "480", "360", "worst"),
                    default = "best"
                ),
                Def("batch_download", "تحميل دفعة واحدة", "حمّل كل الحلقات أو الموسم بضغطة"),
                Def("download_wifi_only", "التحميل عبر الواي فاي فقط", "يمنع التحميل على بيانات الجوال"),
                Def("download_charging_only", "التحميل أثناء الشحن فقط", "يبدأ التحميل عند توصيل الشاحن"),
                Def("download_concurrent", "عدد التحميلات المتزامنة", "كم ملفاً يُحمَّل في الوقت نفسه", Kind.TEXT, default = "3"),
                Def("download_progress_notification", "إظهار تقدّم التحميل", "شريط تقدّم في شريط التنبيهات"),
                Def(
                    "download_format", "صيغة الحفظ", "الصيغة التي تُحفظ بها الملفات", Kind.LIST,
                    entries = listOf("الأصلية", "MP4", "MKV", "TS"),
                    values = listOf("original", "mp4", "mkv", "ts"),
                    default = "original"
                ),
                Def("episode_download_button", "زر التحميل في بطاقة الحلقة", "يُظهر زر تحميل مباشر على كل حلقة"),
                Def("download_queue", "قائمة انتظار التحميل", "يعرض طابور التحميلات"),
                Def("auto_try_next_source", "تجربة المصدر التالي تلقائياً", "إن فشل مصدر ينتقل إلى التالي بلا سؤال")
            )
        ),
        Group(
            "مُحمِّل خارجي",
            listOf(
                Def("external_downloader", "استعمال مُحمِّل خارجي", "حمّل بواسطة تطبيق آخر"),
                Def("external_downloader_package", "تطبيق المُحمِّل", "اسم حزمة التطبيق المستعمل", Kind.TEXT, default = ""),
                Def(
                    "external_downloader_app", "المُحمِّل المفضّل", "التطبيق الجاهز المستعمل", Kind.LIST,
                    entries = listOf("بدون", "ADM", "IDM", "1DM", "NewPipe", "المتصفّح"),
                    values = listOf("none", "adm", "idm", "idm_lite", "newpipe", "browser"),
                    default = "none"
                ),
                Def("launch_external_downloader", "فتح المُحمِّل فوراً", "يفتح التطبيق الخارجي بمجرّد بدء التحميل")
            )
        ),
        Group(
            "إدارة الملفات",
            listOf(
                Def("cache_size", "حدّ المخزّق المؤقت (ميجابايت)", "الحجم الأقصى المخصّص للمخزّق", Kind.TEXT, default = "1000"),
                Def("clear_image_cache", "حذف مخزّق الصور", "يمحو الصور المحفوظة مؤقتاً — لا يحذف تحميلاتك"),
                Def("clear_all_cache", "حذف كل المخزّق المؤقت", "يمحو ملفات المؤقّت كلها"),
                Def(
                    "full_sweep_cache_policy", "نمط التنظيف الشامل", "متى يبدأ", Kind.LIST,
                    entries = listOf("يدوي", "عند امتلاء القرص", "أسبوعياً", "شهرياً"),
                    values = listOf("manual", "low_disk", "weekly", "monthly"),
                    default = "manual"
                ),
                Def("restore_deleted_plugins", "استعادة الإضافات المحذوفة", "يرجع إضافات حذفتها بالخطأ"),
                Def("app_cache_size", "حجم مخزّق التطبيق", "يعرض كم يشغل التطبيق من مساحة", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════════════════════════════════════ (٧) المصادر

internal fun sourcesCategory() = Category(
    "المصادر",
    listOf(
        Group(
            "الاختيار والأولوية",
            listOf(
                Def(
                    "source_priority_mode", "نمط أولوية المصادر", "كيف يُختار المصدر", Kind.LIST,
                    entries = listOf("يدوي", "تلقائي", "الأسرع", "الأفضل"),
                    values = listOf("manual", "auto", "fastest", "best"),
                    default = "manual"
                ),
                Def("smart_source_priority", "أولوية ذكية للمصادر", "يتعلّم من نجاح المصادر السابقة"),
                Def("preferred_providers", "المصادر المفضّلة", "أسماء المصادر التي تقدّمها على غيرها", Kind.TEXT, default = ""),
                Def("avoid_providers", "المصادر المتجنَّبة", "أسماء مصادر لا يُستعلم عنها", Kind.TEXT, default = ""),
                Def("auto_select_source", "اختيار المصدر تلقائياً", "يفتح أول مصدر يعمل بلا سؤال"),
                Def("start_with_none_provider", "البدء بلا مصدر", "يبدأ بالكتالوج بدل مصدر معيّن"),
                Def("favorite_plugins", "الإضافات المفضّلة", "إضافات مثبّتة في الأعلى"),
                Def("show_favorite_plugin_icons", "إظهار أيقونات الإضافات المفضّلة", "صور الإضافات المفضّلة في القائمة"),
                Def("featured_play_list", "قائمة المصادر المميّزة", "يُبرز مصادر بعينها"),
                Def("show_extension_logo_on_content", "شعار الإضافة على المحتوى", "يضع شعار الإضافة على بطاقاتها"),
                Def("extension_logo_on_content", "حجم شعار الإضافة", "حجم الشعار المعروض", Kind.TEXT, default = "24")
            )
        ),
        Group(
            "عند فشل مصدر",
            listOf(
                Def("auto_next_source", "المصدر التالي عند الفشل", "ينتقل تلقائياً إن تعطّل مصدر"),
                Def("source_failover_max_retries", "عدد المحاولات", "كم مصدراً يجرّب قبل الاستسلام", Kind.TEXT, default = "3"),
                Def("source_failover_delay", "الفاصل بين المحاولات (ثوان)", "كم ينتظر قبل تجربة المصدر التالي", Kind.TEXT, default = "1"),
                Def("source_indicator", "مؤشّر المصدر الحالي", "يعرض المصدر المستعمل الآن"),
                Def("show_injection_badge", "شارة التطعيم", "يعرض شارة على المصادر المعدّلة")
            )
        ),
        Group(
            "الشبكة والاستعلام",
            listOf(
                Def("user_agent", "معرّف المتصفّح (User-Agent)", "يُرسل مع كل طلب — اتركه فارغاً للافتراضي", Kind.TEXT, default = ""),
                Def("http_headers", "ترويسات إضافية", "ترويسات مخصّصة تُضاف لكل طلب (سطر لكل ترويسة)", Kind.TEXT, default = ""),
                Def("request_headers", "إظهار الترويسات", "يعرض الترويسات المرسلة للتشخيص", Kind.INFO),
                Def("prefetch_distance", "التحميل المسبق", "يجهّز الصفحة التالية قبل وصولك إليها", Kind.TEXT, default = "0"),
                Def("cookie_string", "ملف تعريف الارتباط (Cookie)", "كوكي يُرسل مع الطلبات", Kind.TEXT, default = ""),
                Def("api_key", "مفتاح الواجهة (API Key)", "مفتاح المصادر التي تحتاجه", Kind.TEXT, default = "")
            )
        ),
        Group(
            "سجلّ البثّ",
            listOf(
                Def("network_streams_history", "سجلّ روابط البثّ", "يحفظ كل رابط بثّ شغّلته"),
                Def("network_streams_list", "عرض السجلّ", "قائمة الروابط المحفوظة", Kind.INFO),
                Def("copied_stream_url", "نسخ آخر رابط", "ينسخ آخر رابط تشغيل إلى الحافظة")
            )
        )
    )
)
