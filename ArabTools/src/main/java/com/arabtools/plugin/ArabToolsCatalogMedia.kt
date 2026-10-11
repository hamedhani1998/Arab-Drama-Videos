package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// هذا الملف: الصفوف، المصادر، المزامنة والمشاركة، النظام.
// (البحث والنتائج والتحميلات في `ArabToolsCatalogDevice.kt` والملفات الأخرى.)

// ═══════════════════════ (١٣) الصفوف — `category_rows`
// يقابل CSM: HomeUILayouts + LayoutCategory + Category Rows + HomeUIBuilder

internal fun rowsCategory() = Category(
    "rows", "الصفوف",
    listOf(
        Group(
            "شكل الصف",
            listOf(
                Def(
                    "row_layout", "شكل الصف", "كيف تُعرض صفوف المحتوى", Kind.LIST,
                    entries = listOf("أفقي", "شبكة", "معرض", "قائمة"),
                    values = listOf("horizontal", "grid", "gallery", "list"),
                    default = "horizontal"
                ),
                Def(
                    "row_item_size", "حجم عناصر الصف", "حجم البطاقات داخل الصف", Kind.LIST,
                    entries = listOf("صغيرة", "عادية", "كبيرة", "كبيرة جداً"),
                    values = listOf("small", "normal", "large", "xlarge"),
                    default = "normal"
                ),
                Def("row_corner_radius", "استدارة زوايا عناصر الصف", "مقدار الانحناء", Kind.TEXT, default = "12"),
                Def("row_spacing", "الفاصل بين البطاقات", "المسافة الأفقية بين البطاقة والأخرى", Kind.TEXT, default = "8"),
                Def("row_labels", "إظهار أسماء الصفوف", "اسم الصف فوق بطاقاته"),
                Def("row_item_count_badge", "شارة عدد العناصر", "يعرض عدد العناصر في الصف")
            )
        ),
        Group(
            "صفوف الأقسام",
            listOf(
                Def("category_rows", "إظهار صفوف الأقسام", "صفوف «الأحدث» و«الأكثر مشاهدة» وغيرها"),
                Def("category_rows_limit", "عدد الصفوف المعروضة", "الصفوف التي تُحمَّل في الرئيسية فقط", Kind.TEXT, default = "8"),
                Def("category_rows_items", "عدد العناصر في الصف", "كم بطاقة في كل صف", Kind.TEXT, default = "12"),
                Def("category_row_order", "ترتيب صفوف الأقسام", "رتّب صفوف الأقسام كما تريد", Kind.TEXT, default = ""),
                Def("category_row_source", "صفوف من مصدر واحد", "اجعل الصفوف كلها من مصدر واحد"),
                Def("category_row_first_only", "الصف الأول فقط", "يُظهر أول صف من كل قسم")
            )
        ),
        Group(
            "أقسام مخصّصة",
            listOf(
                Def("custom_rows_enabled", "أقسام مخصّصة", "أنشئ صفوفاً خاصة بك"),
                Def("custom_rows_list", "قائمة الأقسام المخصّصة", "اسم القسم ورابطه — سطر لكل قسم", Kind.TEXT, default = ""),
                Def("row_dynamic", "صفوف متغيّرة", "يتغيّر محتوى الصف مع الوقت"),
                Def("row_icon", "أيقونة الصف", "أيقونة صغيرة بجانب اسم الصف", Kind.TEXT, default = ""),
                Def("row_manual_assessment", "تقييم يدوي للصفوف", "رتّب الصفوف بتقييمك أنت"),
                Def("row_reorder_handle", "مقبض ترتيب الصفوف", "اسحب الصف لتغيير مكانه"),
                Def("row_pin", "تثبيت صف", "أبقِ صفاً في الأعلى دائماً"),
                Def("row_hide_title", "إخفاء عنوان الصف", "يُخفي اسم الصف ويُبقي البطاقات")
            )
        ),
        Group(
            "المحتوى داخل الصف",
            listOf(
                Def("row_show_progress", "إظهار شريط التقدّم", "يُظهر كم شاهدت من العمل على بطاقته"),
                Def("row_show_badges", "إظهار الشارات", "شارات مثل «جديد» أو «مكتمل»"),
                Def("row_card_style", "شكل البطاقة", "مظهر بطاقة الصف"),
                Def("row_blur_unwatched", "تمويه غير المُشاهَد", "يُموّه ما لم تشاهده بعد"),
            )
        )
    )
)

// ═══════════════════════ (١٤) المصادر — `category_sources`
// يقابل CSM: SourcesSettingsFragment + SourcesModitions + SourceFailoverModitions

internal fun sourcesCategory() = Category(
    "sources", "المصادر",
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
                Def("extension_logo_on_content", "حجم شعار الإضافة", "حجم الشعار المعروض", Kind.TEXT, default = "24"),
                Def("provider_icons", "أيقونات المصادر", "أيقونة مخصّصة لكل مصدر"),
                Def("show_provider_icons", "إظهار أيقونة المصدر", "أيقونة المصدر مع كل نتيجة")
            )
        ),
        Group(
            "عند فشل مصدر",
            listOf(
                Def("auto_next_source", "المصدر التالي عند الفشل", "ينتقل تلقائياً إن تعطّل مصدر"),
                Def("source_failover_max_retries", "عدد المحاولات", "كم مصدراً يجرّب قبل الاستسلام", Kind.TEXT, default = "3"),
                Def("source_failover_delay", "الفاصل بين المحاولات (ثوان)", "كم ينتظر قبل تجربة المصدر التالي", Kind.TEXT, default = "1"),
                Def("source_indicator", "مؤشّر المصدر الحالي", "يعرض المصدر المستعمل الآن"),
                Def("show_injection_badge", "شارة التطعيم", "يعرض شارة على المصادر المعدّلة"),
                Def(
                    "source_failover_mode", "نمط تجاوز الفشل", "كيف ينتقل بين المصادر", Kind.LIST,
                    entries = listOf("صامت", "بسؤال", "بعد مهلة"),
                    values = listOf("silent", "ask", "timeout"),
                    default = "silent"
                ),
                Def("live_tv_sources", "مصادر البث المباشر", "أظهر القنوات المباشرة في قائمة المصادر")
            )
        ),
        Group(
            "الشبكة والاستعلام",
            listOf(
                Def("multi_provider_search", "البحث في كل المصادر", "يستعلم كل المصادر في وقت واحد"),
                Def("user_agent", "معرّف المتصفّح (User-Agent)", "يُرسل مع كل طلب — اتركه فارغاً للافتراضي", Kind.TEXT, default = ""),
                Def("http_headers", "ترويسات إضافية", "ترويسات مخصّصة تُضاف لكل طلب (سطر لكل ترويسة)", Kind.TEXT, default = ""),
                Def("request_headers", "إظهار الترويسات", "يعرض الترويسات المرسلة للتشخيص", Kind.INFO),
                Def("prefetch_distance", "التحميل المسبق", "يجهّز الصفحة التالية قبل وصولك إليها", Kind.TEXT, default = "0"),
                Def("cookie_string", "ملف تعريف الارتباط (Cookie)", "كوكي يُرسل مع الطلبات", Kind.TEXT, default = ""),
                Def("api_key", "مفتاح الواجهة (API Key)", "مفتاح المصادر التي تحتاجه", Kind.TEXT, default = ""),
                Def("network_timeout", "مهلة الشبكة (ثوان)", "بعدها يُعتبر الطلب فاشلاً", Kind.TEXT, default = "30")
            )
        ),
        Group(
            "النتائج والتصفية",
            listOf(
                Def("result_count", "إظهار عدد النتائج", "رقم بجانب عنوان المصدر"),
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
                Def("rank_badge", "إظهار رقم الترتيب", "رقم المكان في قوائم التصنيف"),
                Def("synopsis_expand", "توسيع القصة", "زر لعرض القصة كاملة"),
                Def("synopsis_max_lines", "عدد أسطر القصة", "كم سطراً تظهر قبل التوسيع", Kind.TEXT, default = "4"),
                Def("search_highlight", "تظليل كلمة البحث", "يُبرز الكلمة التي بحثت عنها في النتائج"),
                Def("search_history_enabled", "حفظ سجلّ البحث", "يحفظ ما بحثت عنه للرجوع إليه"),
                Def("recent_searches_limit", "عدد عمليات البحث المحفوظة", "كم كلمة يبقى في السجلّ", Kind.TEXT, default = "20"),
                Def("auto_clear_search_history", "حذف السجلّ تلقائياً", "يمحو سجلّ البحث بعد المدّة المحدّدة"),
                Def("auto_clear_search_days", "مدّة حفظ السجلّ (أيام)", "بعدها يُحذف تلقائياً", Kind.TEXT, default = "30"),
                Def("pull_to_search", "السحب للبحث", "انزل من أعلى الشاشة لتفتح البحث"),
                Def("voice_search", "البحث الصوتي", "يبحث بالكلام"),
                Def("block_keywords", "كلمات محظورة", "نتائج تحتوي هذه الكلمات لا تظهر", Kind.TEXT, default = ""),
                Def("clear_search_history", "مسح سجلّ البحث الآن", "يحذف كل ما حفظه السجلّ فوراً")
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
                Def("episode_badge_text", "نص شارة الحلقة", "ما يُكتب على شارة الحلقة", Kind.TEXT, default = "الحلقة"),
                Def("episode_preview", "معاينة الحلقة", "صورة مصغّرة لكل حلقة"),
                Def("episode_play_expanded", "توسيع زر التشغيل", "زر تشغيل أكبر في بطاقة الحلقة"),
                Def("episode_thumb", "صورة الحلقة", "يُظهر صورة الحلقة في القائمة"),
                Def("episode_thumbnail_shield", "حماية صورة الحلقة", "يمنع استبدال صورة الحلقة الخاطئة")
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

// ═══════════════════════ (١٥) المزامنة والمشاركة — `category_sync`
// يقابل CSM: SyncSharingSettingsFragment + WatchProgressSyncModitions + SharingModitions + WatchParty

internal fun syncCategory() = Category(
    "sync", "المزامنة والمشاركة",
    listOf(
        Group(
            "مزامنة تقدّم المشاهدة",
            listOf(
                Def("watch_progress_sync", "مزامنة تقدّم المشاهدة", "يشارك «وصلت إلى هنا» بين أجهزتك"),
                Def("sync_interval", "مدّة المزامنة (دقائق)", "كل كم يزامن تلقائياً", Kind.TEXT, default = "15"),
                Def("sync_on_wifi_only", "المزامنة عبر الواي فاي فقط", "لا يزامن على بيانات الجوال"),
                Def("last_sync_time", "وقت آخر مزامنة", "متى تمّت آخر مزامنة", Kind.INFO),
                Def("sync_state", "حالة المزامنة", "هل المزامنة تعمل الآن؟", Kind.INFO)
            )
        ),
        Group(
            "الحساب",
            listOf(
                Def("cloud_sync", "المزامنة السحابية", "يزامن مكتبتك عبر خدمة سحابية"),
                Def("cloudstream_accounts", "حسابات CloudStream", "اربط حساباً للمزامنة"),
                Def("alternative_account", "حساب بديل", "حساب آخر للمزامنة"),
                Def("account_login", "تسجيل الدخول", "سجّل الدخول إلى حسابك"),
                Def("account_state", "حالة الحساب", "هل أنت مسجّل الدخول؟", Kind.INFO)
            )
        ),
        Group(
            "التصدير والاستيراد",
            listOf(
                Def("system_backup", "نسخة احتياطية كاملة", "يحفظ إعداداتك ومكتبتك وملفاتك الشخصية"),
                Def("export_settings", "تصدير الإعدادات فقط", "يحفظ الإعدادات بلا محتوى"),
                Def("export_include_player", "تضمين إعدادات المشغّل", "يضمّ إعدادات المشغّل في النسخة"),
                Def("export_include_discord", "تضمين إعدادات ديسكورد", "يضمّ إعدادات ديسكورد في النسخة"),
                Def("import_settings", "استعادة نسخة", "يرجع الإعدادات من ملف نسخة"),
                Def("exported_at", "تاريخ آخر تصدير", "متى أُنشئت آخر نسخة", Kind.INFO),
                Def("copy_settings_to_clipboard", "نسخ الإعدادات إلى الحافظة", "ينسخ كل الإعدادات نصّاً"),
                Def("restore_from_clipboard", "استعادة من الحافظة", "يقرأ إعدادات من الحافظة"),
                Def("reset_all", "حذف كل الإعدادات", "يمحو كل الإعدادات بلا رجعة")
            )
        ),
        Group(
            "المشاركة",
            listOf(
                Def("sharing_enabled", "تفعيل المشاركة", "يسمح بمشاركة الروابط والتقدّم"),
                Def("share_include_progress", "تضمين موضع المشاهدة", "يُرسل مع الرابط أين وصلت"),
                Def("sharing_require_approval", "طلب موافقة قبل الاستلام", "يسألك قبل قبول مشاركة"),
                Def("sharing_trusted_devices", "الأجهزة الموثوقة", "أجهزة سبق أن وافقت عليها", Kind.INFO),
                Def("sharing_discovery", "اكتشاف الأجهزة", "يجعل جهازك مرئياً لمن يشاركك"),
                Def("transfer_state", "حالة النقل الحالية", "هل هناك نقل جارٍ الآن؟", Kind.INFO),
                Def("sharing_wire", "نقل عبر الشبكة", "الطريقة المستعملة في الإرسال"),
                Def("result_share", "زر مشاركة النتيجة", "يشارك رابط العمل مع الآخرين"),
                Def("result_copy_title", "زر نسخ العنوان", "ينسخ اسم العمل إلى الحافظة")
            )
        ),
        Group(
            "مشاهدة جماعية",
            listOf(
                Def("watch_party_enabled", "المشاهدة الجماعية", "شاهد مع أصدقائك في وقت واحد"),
                Def("watch_party_server", "خادم الغرفة", "الخادم المستعمل للمشاهدة الجماعية", Kind.TEXT, default = ""),
                Def("watch_party_room", "اسم الغرفة", "الغرفة التي تنضم إليها", Kind.TEXT, default = ""),
                Def("watch_party_sync_tolerance", "سماحية التزامن (ثوان)", "مقدار الفرق المسموح بينكم", Kind.TEXT, default = "1"),
                Def("watch_party_status", "حالة الغرفة", "هل أنت داخل غرفة الآن؟", Kind.INFO),
                Def("watch_party_reconnect", "إعادة الاتصال تلقائياً", "يعيد الاتصال إن انقطع")
            )
        )
    )
)

// ═══════════════════════ (١٦) النظام — `category_system`
// يقابل CSM: SystemSettingsFragment + SystemAboutDialog + SystemBackupExportDialog + أدوات الصيانة

internal fun systemCategory() = Category(
    "system", "النظام",
    listOf(
        Group(
            "المظهر العام للتطبيق",
            listOf(
                Def("app_name", "اسم التطبيق الظاهر", "الاسم الذي يظهر على أيقونة التطبيق", Kind.TEXT, default = "CloudStream"),
                Def("custom_title", "عنوان مخصّص", "عنوان يظهر أعلى الشاشات", Kind.TEXT, default = ""),
                Def(
                    "system_locale", "لغة التطبيق", "فرض لغة معيّنة بدل لغة النظام", Kind.LIST,
                    entries = listOf("لغة النظام", "العربية", "الإنجليزية"),
                    values = listOf("system", "ar", "en"),
                    default = "system"
                ),
                Def("haptic_feedback", "الاهتزاز اللمسي", "اهتزاز خفيف عند لمس الأزرار"),
            )
        ),
        Group(
            "التشغيل والتحديث",
            listOf(
                Def("auto_update_enabled", "التحديث التلقائي للإضافات", "يحدّث إضافاتك تلقائياً عند فتح التطبيق"),
                Def("auto_cache_clean", "تنظيف المخزّق المؤقت تلقائياً", "يحذف ملفات الكاش القديمة عند تجاوز الحجم المحدّد"),
                Def("cache_clean_threshold_mb", "حدّ تنظيف المخزّق (ميجابايت)", "الحجم الذي بعده يبدأ التنظيف التلقائي", Kind.TEXT, default = "500"),
                Def("update_channel", "قناة التحديث", "نوع الإصدارات التي تصلك", Kind.LIST,
                    entries = listOf("مستقرّة", "تجريبية"),
                    values = listOf("stable", "beta"),
                    default = "stable"
                ),
                Def("startup_gate", "بوّابة البدء", "يفتح التطبيق بعد تجهيز الإضافات")
            )
        ),
        Group(
            "التخزين والحالة",
            listOf(
                Def("coil_cache", "حجم مخزّق الصور", "يعرض كم تشغل الصور المؤقّتة", Kind.INFO),
                Def("system_storage", "استعمال التخزين", "تفصيل المساحة المستعملة", Kind.INFO),
                Def("version_text", "نص الإصدار", "النص الظاهر في المعلومات", Kind.INFO),
                Def("installed_version", "الإصدار المثبّت", "رقم الإصدار الحالي", Kind.INFO),
                Def("os_version", "إصدار أندرويد", "نظام تشغيل هاتفك", Kind.INFO)
            )
        ),
        Group(
            "ما يخصّ هذه الإضافة",
            listOf(
                Def(ArabToolsPrefs.MASTER, "تفعيل التعديلات", "المفتاح الرئيسي — إيقافه يُلغي كل تعديلات الإضافة فوراً", Kind.SWITCH, Impl.LIVE, true),
                Def(ArabToolsPrefs.SAFE_MODE, "الوضع الآمن", "يوقف كل التعديلات مؤقتاً دون فقدان إعداداتها", Kind.SWITCH, Impl.LIVE, false),
                Def(ArabToolsPrefs.SHOW_TOAST, "رسالة تأكيد الإضافة", "رسالة قصيرة تُخبرك بتعديلات «أدوات عربية» المفعّلة", Kind.SWITCH, Impl.LIVE, true)
            )
        )
    )
)
