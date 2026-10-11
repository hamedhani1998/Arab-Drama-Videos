package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════════════════════════════════════ (٣) المظهر

internal fun appearanceCategory() = Category(
    "المظهر",
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
                Def("custom_font", "خط مخصّص", "يستعمل ملف خط تختاره أنت"),
                Def("custom_font_path", "ملف الخط", "مسار ملف الخط في الجهاز", Kind.TEXT, default = ""),
                Def("custom_font_scale", "حجم الخط المخصّص", "تكبير أو تصغير الخط المخصّص", Kind.TEXT, default = "1.0"),
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
                ),
                Def("body_font", "خط النصوص", "الخط المستعمل في الفقرات والقوائم"),
                Def("title_font", "خط العناوين", "الخط المستعمل في العناوين")
            )
        ),
        Group(
            "الصفحة الرئيسية",
            listOf(
                Def(
                    "home_columns", "عدد الأعمدة في الرئيسية", "كم بطاقة في الصف الواحد", Kind.LIST,
                    entries = listOf("عمودان", "٣ أعمدة", "٤ أعمدة", "٥ أعمدة"),
                    values = listOf("2", "3", "4", "5"),
                    default = "3"
                ),
                Def(
                    "poster_aspect_ratio", "نسبة صورة البطاقة", "شكل صور الأفلام والمسلسلات", Kind.LIST,
                    entries = listOf("افتراضي", "عامودي ٢:٣", "مربّع", "أفقي ١٦:٩"),
                    values = listOf("default", "2_3", "square", "16_9"),
                    default = "default"
                ),
                Def(
                    "card_corner_radius", "استدارة زوايا البطاقات", "مقدار انحناء الزوايا", Kind.LIST,
                    entries = listOf("حادة", "خفيفة", "متوسطة", "دائرية"),
                    values = listOf("0", "8", "16", "24"),
                    default = "8"
                ),
                Def("compact_grid", "شبكة مضغوطة", "بطاقات أصغر وعدد أكثر في الشاشة"),
                Def("marquee_titles", "عناوين متحرّكة", "العنوان الطويل يتحرّك بدل أن يُقطع"),
                Def("thumbnail_blur", "تمويه الصور", "يُموّه الصور كي لا تظهر المشاهد لأحد معك"),
                Def("thumbnail_blur_radius", "مقدار التمويه", "شدة التمويه", Kind.TEXT, default = "20"),
                Def("shimmer_loading", "وميض التحميل", "يُظهر هيكل البطاقات وامضاً أثناء انتظار التحميل"),
                Def("skeleton_loading", "هيكل عظمي للتحميل", "مربّعات رمادية مكان البطاقات أثناء التحميل"),
                Def("hide_home_fab", "إخفاء الزر العائم", "يُخفي الزر الدائري العائم في الرئيسية"),
                Def("poster_border", "إطار حول الصور", "يضع إطاراً حول بطاقات الأفلام"),
                ArabToolsCatalog.colorList("poster_border_color", "لون الإطار", "لون الإطار حول البطاقات"),
                Def("poster_border_width", "سماكة الإطار", "سماكة الإطار بالبكسل", Kind.TEXT, default = "1"),
                Def("background_poster_watermark_badge", "شارة مائية على الصور", "علامة صغيرة على البطاقة")
            )
        ),
        Group(
            "الانتقالات والحركة",
            listOf(
                Def("enable_nav_animations", "حركات الانتقال بين الشاشات", "تأثير عند الانتقال من شاشة إلى أخرى"),
                Def(
                    "nav_animation_speed", "سرعة حركات الانتقال", "كم تستغرق الحركة", Kind.LIST,
                    entries = listOf("سريعة", "عادية", "بطيئة"),
                    values = listOf("fast", "normal", "slow"),
                    default = "normal"
                ),
                Def("stagger_animations", "ظهور متتابع للبطاقات", "البطاقات تظهر واحدة بعد أخرى"),
                Def("stagger_delay", "تأخير التتابع (ملي ثانية)", "الفاصل بين بطاقة وأخرى", Kind.TEXT, default = "50"),
                Def("ripple_effect", "تأثير الموجة عند اللمس", "دائرة تظهر مكان لمسك"),
                Def("edge_to_edge", "ملء الحواف", "يمدّ المحتوى إلى حواف الشاشة"),
                Def("transparent_status_bar", "شريط حالة شفّاف", "يجعل خلفية شريط الحالة شفافة"),
                Def("scroll_animations", "حركات التمرير", "تأثير عند تمرير القوائم"),
                Def("smooth_scroll", "تمرير ناعم", "يجعل التمرير أكثر انسيابية")
            )
        )
    )
)

// ═══════════════════════════════════════════════════════ (٤) الرئيسية والتنقل

internal fun navigationCategory() = Category(
    "الرئيسية والتنقل",
    listOf(
        Group(
            "شريط التنقل",
            listOf(
                Def(
                    ArabToolsPrefs.NAV_HIDE_BAR, "إخفاء شريط التنقل السفلي",
                    "يُخفي الشريط كاملاً. التطبيق لا يملك قائمة جانبية، فإن أخفيته " +
                        "فاضغط ضغطاً مطوّلاً في مساحة فارغة لإرجاعه",
                    Kind.SWITCH, Impl.LIVE, false
                ),
                Def("show_nav_labels", "إظهار أسماء التبويبات", "النص تحت كل أيقونة في الشريط السفلي"),
                Def("nav_hide_labels", "إخفاء أسماء التبويبات", "يبقي الأيقونات بلا نصوص — شريط أنحف"),
                Def(
                    "nav_icon_style", "شكل أيقونات التبويبات", "نمط الرسم المستعمل", Kind.LIST,
                    entries = listOf("افتراضي", "ممتلئ", "محدّد", "دائري"),
                    values = listOf("default", "filled", "outlined", "rounded"),
                    default = "default"
                ),
                Def("nav_text_size", "حجم نص التبويبات", "حجم الأسماء تحت الأيقونات", Kind.TEXT, default = "12"),
                Def(
                    "nav_text_case", "حالة أحرف التبويبات", "شكل كتابة الأسماء", Kind.LIST,
                    entries = listOf("كما هي", "كبيرة", "صغيرة", "أول حرف كبير"),
                    values = listOf("none", "upper", "lower", "capitalize"),
                    default = "none"
                ),
                Def("nav_selected_animation", "حركة التبويب المختار", "تأثير عند التنقل بين التبويبات"),
                Def("nav_footer_profile_pic", "صورة الحساب أسفل الشريط", "يضع صورتك في نهاية شريط التنقل"),
                Def("nav_emoji_icons", "أيقونات تعبيرية", "يستعمل رموزاً بدل الأيقونات العادية"),
                Def("nav_bar_transparent", "شريط التنقل شفّاف", "يجعل خلفية الشريط شفافة"),
                Def("nav_tab_order", "ترتيب التبويبات", "رتّب تبويبات الشريط كما تريد", Kind.TEXT, default = "home,search,library,downloads,settings"),
                Def("bottom_navigation", "شريط التنقل السفلي", "استعمل الشريط السفلي (مقابل الشريط الجانبي في الأجهزة العريضة)"),
                Def("start_with_none_provider", "البدء بلا مصدر", "يفتح الرئيسية بلا مصدر مختار")
            )
        ),
        Group(
            "إخفاء تبويبات محددة",
            listOf(
                Def("hidden_nav_tabs", "التبويبات المخفيّة", "أسماء التبويبات التي أخفيتها — «الإعدادات» محميّ لا يُخفى", Kind.INFO)
            )
        ),
        Group(
            "صفوف الرئيسية",
            listOf(
                Def("category_rows", "إظهار صفوف الأقسام", "صفوف «الأحدث» و«الأكثر مشاهدة» وغيرها"),
                Def("category_rows_limit", "عدد الصفوف المعروضة", "الصفوف التي تُحمَّل في الرئيسية فقط", Kind.TEXT, default = "8"),
                Def("category_rows_items", "عدد العناصر في الصف", "كم بطاقة في كل صف", Kind.TEXT, default = "12"),
                Def("preview_carousel", "معرض المعاينة العلوي", "شريط الصور الكبيرة أعلى الرئيسية"),
                Def("continue_watching", "صف «متابعة المشاهدة»", "يضع الحلقات التي لم تكملها في الأعلى"),
                Def("recently_added", "صف «أُضيف حديثاً»", "أحدث ما أُضيف إلى المصادر"),
                Def("recently_watched", "صف «شوهد حديثاً»", "ما شاهدته قريباً"),
                Def("random_picks", "صف «اقتراح عشوائي»", "محتوى عشوائي من كل المصادر"),
                Def("infinite_scroll", "تمرير بلا نهاية", "يُحمّل مزيداً من البطاقات كلما نزلت"),
                Def("sticky_headers", "عناوين ثابتة", "يبقي اسم الصف ظاهراً أثناء التمرير"),
                Def("library_random", "زر الاختيار العشوائي", "يختار عملاً عشوائياً من مكتبتك"),
                Def("scroll_to_top_button", "زر الرجوع إلى الأعلى", "زر يقفز إلى أعلى القائمة")
            )
        ),
        Group(
            "الشريط العلوي",
            listOf(
                Def("hide_toolbar", "إخفاء الشريط العلوي", "يُخفي شريط العنوان تماماً"),
                Def("collapsing_toolbar", "شريط علوي منكمش", "يتقلّص الشريط عند النزول"),
                Def("toolbar_elevation", "ظل الشريط العلوي", "مقدار الظل تحت الشريط", Kind.TEXT, default = "4")
            )
        ),
        Group(
            "المكتبة",
            listOf(
                Def(
                    "library_columns", "أعمدة المكتبة", "كم بطاقة في الصف", Kind.LIST,
                    entries = listOf("عمودان", "٣ أعمدة", "٤ أعمدة", "٥ أعمدة"),
                    values = listOf("2", "3", "4", "5"),
                    default = "3"
                ),
                Def(
                    "library_sort", "ترتيب المكتبة", "الترتيب المستعمل في مكتبتك", Kind.LIST,
                    entries = listOf("أُضيف حديثاً", "أبجدي", "التقييم", "سنة الإصدار", "آخر مشاهدة"),
                    values = listOf("date_added", "alpha", "rating", "year", "watched"),
                    default = "date_added"
                ),
                Def("unwatched_first", "غير المُشاهَد أولاً", "يقدّم ما لم تشاهده بعد"),
                Def("library_loading_shimmer", "وميض أثناء تحميل المكتبة", "هيكل بطاقات وامض بدل الشاشة الفارغة"),
                Def("library_search_filter", "البحث داخل المكتبة", "شريط بحث في أعلى المكتبة"),
                Def("library_tab_layout", "تبويبات المكتبة", "أقسام المكتبة كأشرطة تبويب"),
                Def(
                    "continuous_playback_last_episode_action", "فعل آخر حلقة", "ماذا يفعل التطبيق عند انتهاء آخر حلقة", Kind.LIST,
                    entries = listOf("لا شيء", "التالي في المكتبة", "الصفحة الرئيسية"),
                    values = listOf("none", "next_in_library", "home"),
                    default = "none"
                )
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
