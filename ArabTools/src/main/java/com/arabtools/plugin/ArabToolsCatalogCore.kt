package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════ (١) الإنجازات — `category_advancements`
// يقابل شاشة CSM: AdvancementsSettingsFragment + AdvancementCatalog + AdvancementStore

internal fun advancementsCategory() = Category(
    "advancements", "الإنجازات",
    listOf(
        Group(
            "الشارات والميداليات",
            listOf(
                Def("achievements_enabled", "تفعيل الإنجازات", "يمنحك شارات على ما تشاهده وتنجزه"),
                Def("advancement_medals", "إظهار الميداليات", "شارات ذهبية وفضّية بجانب اسمك"),
                Def("advancement_progress", "إظهار شريط التقدّم", "كم بقي لتحصل على الشارة التالية", Kind.INFO),
                Def("advancement_show_locked", "إظهار الإنجازات المقفلة", "يُظهر ما لم تحصل عليه بعد باهتاً"),
                Def(
                    "advancement_notify", "تنبيه عند نيل إنجاز", "يُخبرك فوراً عند الحصول على شارة", Kind.LIST,
                    entries = listOf("تنبيه ورسالة", "رسالة فقط", "بلا تنبيه"),
                    values = listOf("toast_and_notification", "toast", "none"),
                    default = "toast"
                )
            )
        ),
        Group(
            "حالة الميزات",
            listOf(
                Def("show_feature_status", "إظهار حالة الميزات", "يضع علامة على كل ميزة تُبيّن هل تعمل أم لا"),
                Def("feature_status_report", "تقرير حالة الميزات", "ملخّص كم ميزة تعمل وكم لا", Kind.INFO),
                Def("option_counts", "عدّ الخيارات", "كم خياراً في كل تصنيف", Kind.INFO),
                Def("feature_readiness", "جاهزية الميزات", "هل الميزات المطلوبة مكتملة؟", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════ (٢) الحركات — `category_animations`
// يقابل شاشة CSM: AnimationsSettingsFragment + AnimationModitions + MotionPolicy + SettingsMotion

internal fun animationsCategory() = Category(
    "animations", "الحركات",
    listOf(
        Group(
            "الانتقالات",
            listOf(
                Def("enable_nav_animations", "حركات الانتقال بين الشاشات", "تأثير عند الانتقال من شاشة إلى أخرى"),
                Def(
                    "nav_animation_speed", "سرعة حركات الانتقال", "كم تستغرق الحركة", Kind.LIST,
                    entries = listOf("سريعة", "عادية", "بطيئة"),
                    values = listOf("fast", "normal", "slow"),
                    default = "normal"
                ),
                Def(
                    "settings_page_animation", "حركة صفحات الإعدادات", "طريقة انتقال صفحات الإعدادات", Kind.LIST,
                    entries = listOf("انزلاق", "تلاشٍ", "بلا حركة"),
                    values = listOf("slide", "fade", "none"),
                    default = "slide"
                ),
                Def(
                    "settings_motion", "حركة الواجهة", "مقدار الحركة في الواجهة كلها", Kind.LIST,
                    entries = listOf("كاملة", "مخفّفة", "بلا حركة"),
                    values = listOf("full", "reduced", "none"),
                    default = "full"
                ),
                Def("motion_text_reveal", "ظهور النصوص تدريجياً", "يكتب العناوين حرفاً بعد حرف"),
                Def(
                    "loading_transition", "حركة شاشة التحميل", "شكل الانتقال عند فتح المحتوى", Kind.LIST,
                    entries = listOf("دوّار", "هيكل عظمي", "تلاشٍ", "بلا"),
                    values = listOf("spinner", "skeleton", "fade", "none"),
                    default = "spinner"
                )
            )
        ),
        Group(
            "حركات القوائم",
            listOf(
                Def("stagger_animations", "ظهور متتابع للبطاقات", "البطاقات تظهر واحدة بعد أخرى"),
                Def("stagger_delay", "تأخير التتابع (ملي ثانية)", "الفاصل بين بطاقة وأخرى", Kind.TEXT, default = "50"),
                Def("scroll_animations", "حركات التمرير", "تأثير عند تمرير القوائم"),
                Def("smooth_scroll", "تمرير ناعم", "يجعل التمرير أكثر انسيابية"),
                Def(
                    "smooth_scroll_speed", "سرعة التمرير الناعم", "مدى نعومة التمرير", Kind.LIST,
                    entries = listOf("بطيئة", "عادية", "سريعة"),
                    values = listOf("slow", "normal", "fast"),
                    default = "normal"
                ),
                Def("ripple_effect", "تأثير الموجة عند اللمس", "دائرة تظهر مكان لمسك"),
                Def("scroll_to_top_button", "زر الرجوع إلى الأعلى", "زر يقفز إلى أعلى القائمة")
            )
        )
    )
)

// ═══════════════════════ (٣) المظهر — `category_appearance`
// يقابل شاشة CSM: AppearanceSettingsFragment + ThemeModitions + FontModitions + AppIconSettingsFragment

internal fun appearanceCategory() = Category(
    "appearance", "المظهر",
    listOf(
        Group(
            "الوضع والألوان",
            listOf(
                Def(
                    ArabToolsPrefs.NIGHT_MODE, "وضع العرض",
                    "فرض الوضع الليلي أو النهاري على التطبيق كله",
                    Kind.LIST, Impl.LIVE, "auto",
                    entries = listOf("تلقائي (دون تغيير)", "ليلي دائماً", "نهاري دائماً"),
                    values = listOf("auto", "dark", "light")
                ),
                Def("theme_auto_switch", "تبديل الوضع تلقائياً حسب الوقت", "يُبدّل بين الليلي والنهاري في وقت تحدّده"),
                Def("theme_dark_start_hour", "ساعة بدء الوضع الليلي", "بصيغة ٢٤ ساعة", Kind.TEXT, default = "19"),
                Def("theme_light_start_hour", "ساعة بدء الوضع النهاري", "بصيغة ٢٤ ساعة", Kind.TEXT, default = "7"),
                ArabToolsCatalog.colorList("primary_color", "اللون الأساسي للتطبيق", "لون الأزرار والعناوين"),
                ArabToolsCatalog.colorList("accent_color", "لون التمييز", "لون العناصر المختارة"),
                ArabToolsCatalog.colorList("card_background_color", "لون خلفية البطاقات", "خلفية بطاقات الأفلام والمسلسلات"),
                ArabToolsCatalog.colorList("background_color", "لون خلفية الشاشات", "الخلفية العامة لكل الشاشات"),
                ArabToolsCatalog.colorList("toolbar_color", "لون الشريط العلوي", "لون شريط العنوان"),
                ArabToolsCatalog.colorList("splash_color", "لون شاشة البداية", "شاشة التطبيق الأولى عند الفتح"),
                Def("amoled_black", "أسود كامل (AMOLED)", "يجعل الخلفية سوداء تماماً لتوفير البطارية"),
                Def("material_you", "ألوان النظام (Material You)", "يأخذ الألوان من خلفية هاتفك — أندرويد ١٢+"),
                Def("high_contrast", "تباين عالٍ", "نصوص وألوان أوضح"),
                Def("clear_custom_colors", "مسح الألوان المخصّصة", "يرجع كل الألوان إلى ألوان التطبيق الأصلية")
            )
        ),
        Group(
            "الخطوط",
            listOf(
                Def(
                    "app_font", "خط التطبيق", "الخط المستعمل في كل واجهات التطبيق", Kind.LIST,
                    entries = listOf("افتراضي", "Roboto", "Open Sans", "Noto Sans", "Cairo"),
                    values = listOf("default", "roboto", "open_sans", "noto_sans", "cairo"),
                    default = "default"
                ),
                Def(
                    "font_scale", "حجم الخط العام", "تكبير أو تصغير كل النصوص", Kind.LIST,
                    entries = listOf("صغير جداً", "صغير", "عادي", "كبير", "كبير جداً"),
                    values = listOf("0.85", "0.92", "1.0", "1.1", "1.25"),
                    default = "1.0"
                ),
                Def("custom_font", "خط مخصّص", "يستعمل ملف خط تختاره أنت"),
                Def("custom_font_path", "ملف الخط", "مسار ملف الخط في الجهاز", Kind.TEXT, default = ""),
                Def("custom_font_scale", "حجم الخط المخصّص", "تكبير أو تصغير الخط المخصّص", Kind.TEXT, default = "1.0"),
                Def("body_font", "خط النصوص", "الخط المستعمل في الفقرات والقوائم"),
                Def("title_font", "خط العناوين", "الخط المستعمل في العناوين"),
                Def(
                    "letter_spacing", "تباعد الحروف", "يزيد أو ينقص المسافة بين الحروف", Kind.LIST,
                    entries = listOf("مضغوط", "عادي", "واسع", "واسع جداً"),
                    values = listOf("0.9", "1.0", "1.1", "1.2"),
                    default = "1.0"
                ),
                Def(
                    "line_height", "ارتفاع السطر", "المسافة بين الأسطر", Kind.LIST,
                    entries = listOf("مضغوط", "عادي", "واسع"),
                    values = listOf("1.0", "1.2", "1.4"),
                    default = "1.2"
                )
            )
        ),
        Group(
            "الشكل العام",
            listOf(
                Def(
                    "card_corner_radius", "استدارة زوايا البطاقات", "مقدار انحناء الزوايا", Kind.LIST,
                    entries = listOf("حادة", "خفيفة", "متوسطة", "دائرية"),
                    values = listOf("0", "8", "16", "24"),
                    default = "8"
                ),
                Def(
                    "poster_aspect_ratio", "نسبة صورة البطاقة", "شكل صور الأفلام والمسلسلات", Kind.LIST,
                    entries = listOf("افتراضي", "عامودي ٢:٣", "مربّع", "أفقي ١٦:٩"),
                    values = listOf("default", "2_3", "square", "16_9"),
                    default = "default"
                ),
                Def("poster_border", "إطار حول الصور", "يضع إطاراً حول بطاقات الأفلام"),
                ArabToolsCatalog.colorList("poster_border_color", "لون الإطار", "لون الإطار حول البطاقات"),
                Def("poster_border_width", "سماكة الإطار", "سماكة الإطار بالبكسل", Kind.TEXT, default = "1"),
                Def("background_poster_watermark_badge", "شارة مائية على الصور", "علامة صغيرة على البطاقة"),
                Def("thumbnail_blur", "تمويه الصور", "يُموّه الصور كي لا تظهر المشاهد لأحد معك"),
                Def("thumbnail_blur_radius", "مقدار التمويه", "شدة التمويه", Kind.TEXT, default = "20"),
                Def("shimmer_loading", "وميض التحميل", "يُظهر هيكل البطاقات وامضاً أثناء انتظار التحميل"),
                Def("skeleton_loading", "هيكل عظمي للتحميل", "مربّعات رمادية مكان البطاقات أثناء التحميل"),
                Def("marquee_titles", "عناوين متحرّكة", "العنوان الطويل يتحرّك بدل أن يُقطع"),
                Def("edge_to_edge", "ملء الحواف", "يمدّ المحتوى إلى حواف الشاشة"),
                Def("transparent_status_bar", "شريط حالة شفّاف", "يجعل خلفية شريط الحالة شفافة"),
                Def(
                    "icon_theme", "نمط الأيقونات", "ما تُرسم به أيقونات التطبيق", Kind.LIST,
                    entries = listOf("افتراضي", "ممتلئ", "محدّد", "دائري"),
                    values = listOf("default", "filled", "outlined", "rounded"),
                    default = "default"
                ),
                ArabToolsCatalog.colorList("icon_tint", "لون الأيقونات", "صبغة تُطبَّق على كل الأيقونات")
            )
        ),
        Group(
            "أيقونة التطبيق",
            listOf(
                Def("custom_app_icon", "أيقونة التطبيق", "أيقونة بديلة تختارها"),
                Def("app_icon_color", "لون أيقونة التطبيق", "لون الأيقونة المختصرة"),
                Def(
                    "app_icon_shape", "شكل الأيقونة", "شكل خلفية الأيقونة", Kind.LIST,
                    entries = listOf("افتراضي", "دائري", "مربّع", "حافّ"),
                    values = listOf("default", "circle", "square", "squircle"),
                    default = "default"
                ),
                Def("app_shortcut", "اختصار التطبيق", "أضف اختصاراً سريعاً على الشاشة الرئيسية"),
                Def("create_default_shortcut", "إنشاء اختصار بدء عادي", "اختصار يفتح التطبيق مباشرة"),
                Def("create_custom_shortcut", "إنشاء اختصار مخصّص", "اختصار يفتح شاشة بعينها"),
                Def("app_shortcut_search", "اختصار البحث", "اختصار يفتح البحث مباشرة")
            )
        ),
        Group(
            "إمكانية الوصول",
            listOf(
                Def("reduce_motion", "تقليل الحركة", "يخفّف الحركات — مفيد لمن يتعب من الحركة"),
                Def("reduce_transparency", "تقليل الشفافية", "يجعل الخلفيات معتمة لا شفافة"),
                Def("screen_reader_hints", "تلميحات قارئ الشاشة", "نصوص صوتية إضافية لقارئ الشاشة"),
                Def("large_touch_targets", "أهداف لمس كبيرة", "أزرار أكبر يسهل لمسها"),
                Def("haptic_feedback_enabled", "الاهتزاز اللمسي", "اهتزاز خفيف عند لمس الأزرار"),
                Def("hide_microphone", "إخفاء زر الميكروفون", "يُخفي زر البحث الصوتي"),
                Def("mono_audio", "صوت أحادي القناة", "يدمج القناتين — مفيد لمن بسمع في أذن واحدة"),
                Def("vocal_booster", "تقوية الأصوات", "يُبرز الكلام على الموسيقى")
            )
        ),
        Group(
            "الملفات الشخصية",
            listOf(
                Def("profile_order", "ترتيب الملفات الشخصية", "رتّب ملفاتك كما تريد", Kind.TEXT, default = ""),
                Def("hidden_profiles", "الملفات المخفيّة", "ملفات لا تظهر في القائمة", Kind.TEXT, default = ""),
                Def("custom_profile_picture_enabled", "صورة ملف شخصية مخصّصة", "يستعمل صورة تختارها أنت بدل الافتراضية"),
                Def("profile_picture", "ملف الصورة", "مساره في الجهاز", Kind.TEXT, default = ""),
                Def("default_profile", "الملف الافتراضي", "الملف الذي يُفتح عند بدء التطبيق", Kind.TEXT, default = ""),
                Def("hide_profiles", "إخفاء شريط الملفات الشخصية", "يُخفي شريط تبديل الملفات من الأعلى")
            )
        )
    )
)

// ═══════════════════════ (٤) المطوّر — `category_developer`
// يقابل شاشة CSM: DeveloperSettingsFragment + RuntimeDiagnosticsDashboard + CsmSelfTestEngine

internal fun developerCategory() = Category(
    "developer", "المطوّر",
    listOf(
        Group(
            "أدوات المطوّر",
            listOf(
                Def("developer_mode", "وضع المطوّر", "يُظهر أدوات متقدّمة للتشخيص"),
                Def("csm_debug_logging", "سجلّ التشخيص", "يكتب تفاصيل العمل في السجلّ"),
                Def("debug_overlay", "طبقة التشخيص", "يعرض معلومات تقنية فوق التطبيق"),
                Def("view_inspector", "مفتّش الواجهة", "يُظهر معرّفات العناصر عند لمسها"),
                Def("view_dump", "تفريغ شجرة الواجهة", "يسجّل كل عناصر الشاشة الحالية", Kind.INFO),
                Def("runtime_diagnostics", "لوحة التشخيص الحيّة", "مقاييس التشغيل أثناء العمل", Kind.INFO),
                Def("runtime_metrics", "مقاييس الأداء", "الذاكرة والاستجابة ومعدّل الإطارات", Kind.INFO)
            )
        ),
        Group(
            "الاختبار الذاتي",
            listOf(
                Def("self_test", "الاختبار الذاتي", "يفحص الإضافة والمحرّك ويخبرك بما يعمل"),
                Def("self_test_run", "تشغيل الاختبار الآن", "يبدأ الفحص فوراً"),
                Def("self_test_export", "تصدير نتيجة الفحص", "يحفظ التقرير نصّاً"),
                Def("self_test_scope", "نطاق الفحص", "ما يشمله الاختبار", Kind.INFO),
                Def("konami_panel", "لوحة مخفيّة", "لوحة تجارب تُفتح بلمسات متتالية")
            )
        ),
        Group(
            "التقارير",
            listOf(
                Def("bug_report", "إبلاغ عن مشكلة", "يفتح صفحة الإبلاغ مع تفاصيل جهازك"),
                Def("system_report", "تقرير النظام", "ملخّص معلومات الجهاز والتطبيق", Kind.INFO),
                Def("showcase_enabled", "الوضع الاستعراضي", "يعرض مزايا الإضافة على الشاشة"),
                Def("experimental_flags", "ميزات تجريبية", "ميزات لم تكتمل — قد تتغيّر أو تختفي"),
                Def("reset_settings", "إعادة ضبط كل الإعدادات", "يرجع كل شيء إلى الافتراضي — لا يمكن التراجع")
            )
        )
    )
)
