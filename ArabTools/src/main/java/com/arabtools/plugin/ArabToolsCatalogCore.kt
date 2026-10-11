package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// ═══════════════════════════════════════════════════════ (١) عام — `category_*`

internal fun generalCategory() = Category(
    "عام",
    listOf(
        Group(
            "المفتاح الرئيسي",
            listOf(
                Def(
                    ArabToolsPrefs.MASTER, "تفعيل التعديلات",
                    "المفتاح الرئيسي — إيقافه يُلغي كل التعديلات أدناه فوراً",
                    Kind.SWITCH, Impl.LIVE, true
                ),
                Def(
                    ArabToolsPrefs.SAFE_MODE, "الوضع الآمن",
                    "يوقف كل التعديلات مؤقتاً دون فقدان إعداداتها",
                    Kind.SWITCH, Impl.LIVE, false
                ),
                Def(
                    ArabToolsPrefs.SHOW_TOAST, "رسالة تأكيد عند فتح التطبيق",
                    "رسالة قصيرة مرة واحدة تُخبرك بالتعديلات المفعّلة فعلاً",
                    Kind.SWITCH, Impl.LIVE, true
                )
            )
        ),
        Group(
            "التشغيل والتحديث",
            listOf(
                Def("auto_update_enabled", "التحديث التلقائي للإضافات", "يحدّث إضافاتك تلقائياً عند فتح التطبيق"),
                Def(
                    "startup_page", "صفحة البدء", "الشاشة التي يفتحها التطبيق أولاً", Kind.LIST,
                    entries = listOf("الرئيسية", "البحث", "المكتبة", "التحميلات", "الإعدادات"),
                    values = listOf("home", "search", "library", "downloads", "settings"),
                    default = "home"
                ),
                Def("auto_cache_clean", "تنظيف المخزّق المؤقت تلقائياً", "يحذف ملفات الكاش القديمة عند تجاوز الحجم المحدّد"),
                Def("cache_clean_threshold_mb", "حدّ تنظيف المخزّق (ميجابايت)", "الحجم الذي بعده يبدأ التنظيف التلقائي", Kind.TEXT, default = "500"),
                Def("show_feature_status", "إظهار حالة الميزات", "يضع علامة على كل ميزة تُبيّن هل تعمل أم لا"),
                Def("reduce_motion", "تقليل الحركة", "يخفّف حركات الانتقال — مفيد للأجهزة الضعيفة"),
                Def("haptic_feedback", "الاهتزاز اللمسي", "اهتزاز خفيف عند لمس الأزرار"),
                Def("confirm_exit_key", "تأكيد الخروج بزر الرجوع", "يسألك قبل إغلاق التطبيق بزر الرجوع")
            )
        ),
        Group(
            "اللغة والخط",
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
                Def(
                    "system_locale", "لغة التطبيق", "فرض لغة معيّنة بدل لغة النظام", Kind.LIST,
                    entries = listOf("لغة النظام", "العربية", "الإنجليزية"),
                    values = listOf("system", "ar", "en"),
                    default = "system"
                )
            )
        )
    )
)

// ═══════════════════════════════════════════════════════ (٢) المشغّل — `category_player`

internal fun playerCategory() = Category(
    "المشغّل",
    listOf(
        Group(
            "الشاشة والاتجاه",
            listOf(
                Def(
                    ArabToolsPrefs.PLAYER_LANDSCAPE, "تدوير تلقائي إلى الشاشة الأفقية",
                    "يعمل عند بدء التشغيل فقط، ويعود الوضع الطبيعي عند الخروج منه",
                    Kind.SWITCH, Impl.LIVE, false
                ),
                Def("immersive_mode", "ملء الشاشة أثناء التشغيل", "يُخفي أشرطة النظام كي لا تُقطع الصورة"),
                Def(ArabToolsPrefs.HIDE_STATUS, "إخفاء شريط الحالة", "يُخفي شريط الوقت والبطارية أعلى الشاشة", Kind.SWITCH, Impl.LIVE, false),
                ArabToolsCatalog.colorList(ArabToolsPrefs.STATUS_COLOR, "لون شريط الحالة", "لا يعمل على أندرويد 15 وما بعده (النظام يتجاهل اللون)", Impl.LIVE),
                ArabToolsCatalog.colorList(ArabToolsPrefs.NAV_COLOR, "لون شريط أزرار النظام", "هذا شريط أندرويد السفلي، لا شريط تبويبات التطبيق", Impl.LIVE),
                Def(
                    "preferred_aspect_ratio", "نسبة العرض الافتراضية", "كيف تُعرض الصورة داخل المشغّل", Kind.LIST,
                    entries = listOf("تلقائي", "ملء الشاشة", "16:9", "4:3", "21:9", "مربّع"),
                    values = listOf("auto", "fill", "16_9", "4_3", "21_9", "square"),
                    default = "auto"
                ),
                Def("auto_fullscreen_on_play", "ملء الشاشة تلقائياً عند التشغيل", "يتجاوز الشاشة الصغيرة ويبدأ ملء الشاشة مباشرة")
            )
        ),
        Group(
            "الأزرار والتحكم",
            listOf(
                Def("auto_hide_controls", "إخفاء الأزرار تلقائياً", "تُخفى أزرار المشغّل بعد فترة سكون"),
                Def("auto_hide_delay", "مدّة إخفاء الأزرار (ثوان)", "كم ينتظر قبل إخفاء الأزرار", Kind.TEXT, default = "4"),
                Def("keep_controls_visible", "إبقاء الأزرار ظاهرة دائماً", "يلغي الإخفاء التلقائي — الأزرار لا تختفي أبداً"),
                Def(
                    "double_tap_action", "فعل النقر المزدوج", "ماذا يحدث عند النقر مرّتين على الشاشة", Kind.LIST,
                    entries = listOf("تقديم/تأخير ١٠ ثوانٍ", "ملء الشاشة", "تشغيل/إيقاف", "لا شيء"),
                    values = listOf("seek", "fullscreen", "play_pause", "none"),
                    default = "seek"
                ),
                Def(
                    "long_press_action", "فعل الضغط المطوّل", "ماذا يحدث عند الضغط المطوّل على الشاشة", Kind.LIST,
                    entries = listOf("تسريع التشغيل", "تغيير السرعة", "لا شيء"),
                    values = listOf("speed_up", "speed_change", "none"),
                    default = "speed_up"
                ),
                Def("player_mute_button", "زر كتم الصوت", "يضيف زراً لكتم الصوت بلمسة"),
                Def("player_zoom_buttons", "أزرار التكبير والتصغير", "أزرار لتقريب الصورة"),
                Def("player_quick_actions", "لوحة الأفعال السريعة", "لوحة سريعة لأدوات المشغّل")
            )
        ),
        Group(
            "السرعة والتقديم",
            listOf(
                Def("custom_player_speeds", "سرعات تشغيل مخصّصة", "السرعات المتاحة في زر السرعة", Kind.TEXT, default = "0.25,0.5,0.75,1,1.25,1.5,1.75,2,3"),
                Def(
                    "player_speed_default", "سرعة التشغيل الافتراضية", "السرعة التي يبدأ بها كل تشغيل", Kind.LIST,
                    entries = listOf("عادية (١×)", "٠٫٧٥×", "١٫٢٥×", "١٫٥×", "٢×"),
                    values = listOf("1.0", "0.75", "1.25", "1.5", "2.0"),
                    default = "1.0"
                ),
                Def(
                    "long_press_seek_speed", "سرعة التقديم عند الضغط المطوّل",
                    "مضاعِف السرعة أثناء الضغط المطوّل على الشاشة", Kind.LIST,
                    entries = listOf("٢×", "٣×", "٤×", "٥×"),
                    values = listOf("2", "3", "4", "5"),
                    default = "2"
                ),
                Def(
                    "swipe_seek_sensitivity", "حساسية السحب للتقديم", "كم يتحرّك الشريط مقابل سحب الإصبع", Kind.LIST,
                    entries = listOf("منخفضة", "عادية", "عالية", "عالية جداً"),
                    values = listOf("low", "normal", "high", "very_high"),
                    default = "normal"
                ),
                Def("remember_zoom", "تذكّر التكبير", "يحفظ مقدار التكبير ويعيده في المرة القادمة"),
                Def("player_zoom_scale", "مقدار التكبير", "نسبة تكبير الصورة", Kind.TEXT, default = "1.0"),
                Def("speed_lock_enabled", "قفل السرعة", "يمنع تغيير السرعة بالخطأ"),
                Def("speed_indicator_badge", "شارة سرعة التشغيل", "يعرض السرعة الحالية على الشاشة")
            )
        ),
        Group(
            "التشغيل التلقائي والتخطي",
            listOf(
                Def("auto_skip_enabled", "تخطي المقدمة تلقائياً", "يتجاوز مقدمة كل حلقة بلا تدخّل منك"),
                Def("auto_skip_time", "مدّة المقدمة المحفوظة (ثوان)", "تُستعمل عندما لا يستطيع التطبيق كشف المقدمة بنفسه", Kind.TEXT, default = "85"),
                Def("auto_skip_keep_first_intro", "لا تتخطَّ مقدمة الحلقة الأولى", "يبقي مقدمة أول حلقة كي تراها"),
                Def("auto_skip_seen_series", "تخطي المقدمة في المسلسلات المتابَعة", "لا يخمّن في مسلسل جديد لم تشاهده بعد"),
                Def("auto_resume", "استكمال من آخر موضع", "يفتح الحلقة من حيث توقّفت بدل بدايتها"),
                Def("auto_resume_show_toast", "إظهار رسالة عند الاستكمال", "يخبرك من أي دقيقة بدأ التشغيل"),
                Def("continuous_playback", "التشغيل المتواصل", "ينتقل تلقائياً إلى الحلقة التالية عند انتهاء الحالية"),
                Def("continuous_playback_countdown", "مدّة العدّ قبل الحلقة التالية (ثوان)", "كم ينتظر قبل التشغيل التلقائي", Kind.TEXT, default = "5"),
                Def("auto_mark_watched", "تعليم الحلقة كمُشاهَدة تلقائياً", "يعلّم الحلقة منتهية عند بلوغ نسبة معيّنة"),
                Def("auto_mark_watched_percent", "نسبة اعتبار الحلقة مُشاهَدة (٪)", "النسبة التي بعدها تُعلَّم الحلقة منتهية", Kind.TEXT, default = "90"),
                Def("player_skip_op", "زر تخطي المقدمة", "يُظهر زر تخطي في المشغّل"),
                Def("player_skip_next", "زر تخطي إلى التالية", "يُظهر زراً للانتقال للحلقة التالية")
            )
        ),
        Group(
            "الصوت والصورة",
            listOf(
                Def("background_audio_enabled", "الصوت في الخلفية", "يستمر الصوت وصورة الشاشة مغلقة"),
                Def("background_audio_show_notification", "تنبيه الصوت في الخلفية", "يُظهر التحكّم في شريط التنبيهات"),
                Def("audio_ducking", "خفض الصوت عند وجود تنبيه", "ينخفض صوت الفيديو تلقائياً عند وصول تنبيه"),
                Def("audio_duck_level", "مقدار خفض الصوت (٪)", "إلى أي نسبة ينخفض الصوت", Kind.TEXT, default = "40"),
                Def("equalizer_enabled", "مُعدِّل الصوت", "تحكّم في النغمات داخل المشغّل"),
                Def(
                    "equalizer_preset", "نمط مُعدِّل الصوت", "النمط الجاهز المستعمل", Kind.LIST,
                    entries = listOf("بدون", "عادي", "بوب", "روك", "جاز", "كلاسيكي", "كلام"),
                    values = listOf("none", "normal", "pop", "rock", "jazz", "classical", "speech"),
                    default = "none"
                ),
                Def(
                    "bass_boost", "تقوية الجهير", "يزيد عمق الأصوات المنخفضة", Kind.LIST,
                    entries = listOf("بدون", "خفيف", "متوسط", "قوي"),
                    values = listOf("0", "1", "2", "3"),
                    default = "0"
                ),
                Def("player_bg_dim", "تعتيم خلفية المشغّل", "يُعتّم ما حول الفيديو في الوضع العامودي"),
                Def("player_bg_dim_level", "مقدار التعتيم (٪)", "شدة التعتيم", Kind.TEXT, default = "50"),
                Def(
                    "player_color_filter", "فلتر ألوان الفيديو", "يضبط السطوع والتشبّع", Kind.LIST,
                    entries = listOf("بدون", "سطوع عالٍ", "تباين عالٍ", "دافئ", "بارد", "رمادي"),
                    values = listOf("none", "bright", "contrast", "warm", "cool", "mono"),
                    default = "none"
                ),
                Def(
                    "video_effect_preset", "تأثيرات الفيديو", "تأثير بصري على الصورة", Kind.LIST,
                    entries = listOf("بدون", "خفيف", "متوسط", "قوي"),
                    values = listOf("none", "light", "medium", "strong"),
                    default = "none"
                ),
                Def("vocal_booster", "تقوية الأصوات", "يُبرز الكلام على الموسيقى"),
                Def("pitch_shift", "تغيير طبقة الصوت", "يرفع أو يخفض طبقة الصوت")
            )
        ),
        Group(
            "أدوات أثناء المشاهدة",
            listOf(
                Def("player_screenshot", "زر تصوير الشاشة", "يضيف زراً لالتقاط صورة من الفيديو"),
                Def("copy_current_stream_url", "زر نسخ رابط البث", "ينسخ رابط الفيديو الحالي إلى الحافظة"),
                Def("player_metadata_overlay", "طبقة معلومات الفيديو", "يعرض اسم المسلسل ورقم الحلقة فوق الفيديو"),
                Def("buffering_speed_display", "عرض سرعة التحميل", "يُظهر سرعة الشبكة أثناء التخزين المؤقت"),
                Def("video_info_overlay", "طبقة معلومات تقنية", "يعرض الدقّة والترميز ومعدّل الصور"),
                Def("sleep_timer_duration", "مؤقّت النوم (دقائق)", "يتوقف التشغيل بعد المدّة المحدّدة", Kind.TEXT, default = "30"),
                Def("quick_bookmark", "علامة سريعة", "يضع علامة عند اللحظة الحالية"),
                Def("bookmark_scenes_enabled", "علامات المشاهد", "احفظ مشاهد بعينها وارجع إليها"),
                Def("player_episodes_button", "زر قائمة الحلقات", "يفتح قائمة الحلقات من داخل المشغّل"),
                Def("pinch_to_zoom", "التكبير بإصبعين", "كبّر الصورة بإصبعيك"),
                Def("player_rotate_btt", "زر تدوير الشاشة", "يقلب الاتجاه أثناء التشغيل")
            )
        )
    )
)
