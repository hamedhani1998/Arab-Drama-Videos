package com.arabtools.plugin

import com.arabtools.plugin.ArabToolsCatalog.Category
import com.arabtools.plugin.ArabToolsCatalog.Def
import com.arabtools.plugin.ArabToolsCatalog.Group
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind

// كل ملف يحمل أربعة تصنيفات. هذا الملف: التنقّل، التنبيهات، الرقابة الأبوية، المشغّل.
// (الحركات والمظهر في `ArabToolsCatalogCore.kt` — قسمة الملفات على CSM لا على أسمائها.)

// ═══════════════════════ (٩) التنقّل — `category_navigation`
// يقابل CSM: NavigationSettingsFragment + NavigationModitions + NavigationBarSurface

internal fun navigationCategory() = Category(
    "navigation", "التنقّل",
    listOf(
        Group(
            "شريط التنقّل",
            listOf(
                Def(
                    ArabToolsPrefs.NAV_HIDE_BAR, "إخفاء شريط التنقّل السفلي",
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
                Def("nav_selected_animation", "حركة التبويب المختار", "تأثير عند التنقّل بين التبويبات"),
                Def("nav_footer_profile_pic", "صورة الحساب أسفل الشريط", "يضع صورتك في نهاية شريط التنقّل"),
                Def("nav_emoji_icons", "أيقونات تعبيرية", "يستعمل رموزاً بدل الأيقونات العادية"),
                Def("nav_bar_transparent", "شريط التنقّل شفّاف", "يجعل خلفية الشريط شفافة"),
                Def("nav_tab_order", "ترتيب التبويبات", "رتّب تبويبات الشريط كما تريد", Kind.TEXT, default = "home,search,library,downloads,settings"),
                Def("bottom_navigation", "شريط التنقّل السفلي", "استعمل الشريط السفلي (مقابل الشريط الجانبي في الأجهزة العريضة)"),
            )
        ),
        Group(
            "إخفاء تبويبات محددة",
            listOf(
                Def("hidden_nav_tabs", "التبويبات المخفيّة", "أسماء التبويبات التي أخفيتها — «الإعدادات» محميّ لا يُخفى", Kind.INFO)
            )
        ),
        Group(
            "البدء والشريط الجانبي",
            listOf(
                Def(
                    "startup_page", "صفحة البدء", "الشاشة التي يفتحها التطبيق أولاً", Kind.LIST,
                    entries = listOf("الرئيسية", "البحث", "المكتبة", "التحميلات", "الإعدادات"),
                    values = listOf("home", "search", "library", "downloads", "settings"),
                    default = "home"
                ),
                Def("side_panel_enabled", "اللوحة الجانبية", "لوحة تُفتح من الحافة"),
                Def("side_panel_width", "عرض اللوحة الجانبية", "عرضها بالبكسل", Kind.TEXT, default = "280"),
                Def("nav_underlay", "طبقة أسفل الشريط", "طبقة خلفية تظهر تحت شريط التنقّل"),
                Def("skip_hidden_destinations", "تخطّي التبويبات المخفيّة", "لا يقف التنقّل عند تبويب مخفي")
            )
        ),
        Group(
            "اللمس والإيماءات",
            listOf(
                Def("swipe_between_pages", "التبديل بين الصفحات بالسحب", "اسحب يميناً أو يساراً لتنتقل"),
                Def(
                    "swipe_sensitivity", "حساسية السحب", "كم يجب أن تسحب لتنتقل", Kind.LIST,
                    entries = listOf("منخفضة", "عادية", "عالية"),
                    values = listOf("low", "normal", "high"),
                    default = "normal"
                ),
                Def("double_tap_nav", "النقر المزدوج على الشريط", "انقر مرّتين على تبويب للعودة لأعلى"),
                Def("long_press_nav", "الضغط المطوّل على الشريط", "اضغط مطوّلاً لإظهار خيارات سريعة"),
                Def("confirm_exit_key", "تأكيد الخروج بزر الرجوع", "يسألك قبل إغلاق التطبيق بزر الرجوع")
            )
        )
    )
)

// ═══════════════════════ (١٠) التنبيهات — `category_notifications`
// يقابل CSM: NotificationsSettingsFragment + NotificationManager + NotificationUI

internal fun notificationsCategory() = Category(
    "notifications", "التنبيهات",
    listOf(
        Group(
            "إشعارات عامة",
            listOf(
                Def("update_notifications_enabled", "تنبيه وجود تحديث", "يُخبرك عند صدور تحديث للتطبيق أو الإضافة"),
                Def("new_episode_alerts", "تنبيه الحلقات الجديدة", "يُخبرك عند نزول حلقة جديدة لمسلسل تتابعه"),
                Def("notification_sound", "صوت التنبيه", "الصوت المرافق للتنبيهات"),
                Def("clear_notifications", "مسح كل التنبيهات", "يمحو التنبيهات الظاهرة الآن"),
                Def(
                    "dismissed_notifications", "التنبيهات المُستبعَدة", "تنبيهات أخفيتها — لا تُعرض مرة أخرى", Kind.INFO
                )
            )
        ),
        Group(
            "شكل التنبيه",
            listOf(
                Def("notification_card_style", "شكل بطاقة التنبيه", "مظهر التنبيه في شريط التنبيهات"),
                Def("notification_blur_enabled", "تمويه خلفية التنبيه", "خلفية ضبابية للتنبيه"),
                Def("notification_overlay_opacity", "شفافية طبقة التنبيه", "شدة ظهور التنبيه فوق التطبيق", Kind.TEXT, default = "80"),
                Def("notification_preview", "معاينة التنبيه", "يعرض معاينة للتنبيه الحالي", Kind.INFO)
            )
        ),
        Group(
            "تنبيهات المشاهدة",
            listOf(
                Def("status_notification", "تنبيه حالة التشغيل", "تنبيه ثابت أثناء تشغيل فيديو"),
                Def("now_playing_overlay_enabled", "طبقة «يُشغَّل الآن»", "طبقة تعرض ما يُشغَّل حالياً"),
            )
        )
    )
)

// ═══════════════════════ (١١) الرقابة الأبوية — `category_parental`
// يقابل CSM: ParentalSettingsFragment + ParentalPinStore + ParentalTimeLockModitions

internal fun parentalCategory() = Category(
    "parental", "الرقابة الأبوية",
    listOf(
        Group(
            "القفل والرمز",
            listOf(
                Def("parental_time_lock", "قفل وقت المشاهدة", "يحدّ وقت المشاهدة اليومي"),
                Def("parental_pin", "الرمز السرّي", "رمز من أرقام يحمي الإعدادات", Kind.TEXT, default = ""),
                Def("change_pin", "تغيير الرمز السرّي", "يستبدل الرمز الحالي"),
                Def("reset_pin", "إعادة تعيين الرمز", "يمسح الرمز إن نسيته — يحتاج تأكيداً"),
                Def("parental_recovery", "استعادة الرمز", "طريقة استرجاع الرمز عند نسيانه")
            )
        ),
        Group(
            "حدود المشاهدة",
            listOf(
                Def("parental_schedule", "جدول المشاهدة المسموح", "الوقت الذي يُسمح فيه بالمشاهدة"),
                Def("parental_time_lock_hour", "ساعة بدء السماح", "بصيغة ٢٤ ساعة", Kind.TEXT, default = "0"),
                Def("parental_time_lock_minute", "دقيقة بدء السماح", "الدقيقة", Kind.TEXT, default = "0"),
                Def(
                    "parental_time_lock_action", "فعل تجاوز الوقت", "ما يحدث عند انتهاء الوقت المسموح", Kind.LIST,
                    entries = listOf("إيقاف التشغيل", "إظهار تنبيه", "طلب الرمز"),
                    values = listOf("stop", "warn", "require_pin"),
                    default = "stop"
                ),
                Def("time_lock_temporarily_disable", "إيقاف القفل مؤقتاً", "يمنح استثناءً لمدّة محدّدة"),
                Def(
                    "parental_scope", "نطاق الرقابة", "ما تخضع له الرقابة", Kind.LIST,
                    entries = listOf("التطبيق كاملاً", "المشغّل فقط", "الإعدادات فقط"),
                    values = listOf("app", "player", "settings"),
                    default = "app"
                ),
                Def("maturity_filter_enabled", "تصفية التصنيف العمري", "يُخفي المحتوى فوق تصنيف محدّد"),
                Def("hide_adult_content", "إخفاء محتوى البالغين", "يمنع ظهور الأعمال المخصّصة للبالغين")
            )
        ),
        Group(
            "الحالة",
            listOf(
                Def("parental_state", "حالة القفل", "هل القفل مفعّل الآن؟", Kind.INFO),
                Def("parental_schedule_status", "حالة الجدول", "هل الوقت الآن مسموح؟", Kind.INFO),
                Def("pin_failed_attempts", "محاولات الرمز الخاطئة", "كم مرة أُدخل رمز خاطئ", Kind.INFO)
            )
        )
    )
)

// ═══════════════════════ (١٢) المشغّل — `category_player`
// يقابل CSM: PlayerSettingsFragment + PlayerModitions + AutoSkipModitions + AutoResumeModitions

internal fun playerCategory() = Category(
    "player", "المشغّل",
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
                Def("auto_fullscreen_on_play", "ملء الشاشة تلقائياً عند التشغيل", "يتجاوز الشاشة الصغيرة ويبدأ ملء الشاشة مباشرة"),
                Def(
                    "resize_mode", "نمط تحجيم الفيديو", "كيف يُحجَّم الفيديو داخل الإطار", Kind.LIST,
                    entries = listOf("ملاءمة", "تمديد", "قصّ", "ملء"),
                    values = listOf("fit", "stretch", "crop", "fill"),
                    default = "fit"
                ),
                Def("player_screenshot", "زر تصوير الشاشة", "يضيف زراً لالتقاط صورة من الفيديو"),
                Def("screenshot_prevention", "منع تصوير الشاشة", "يمنع التقاط صورة للفيديو")
            )
        ),
        Group(
            "الأزرار والتحكّم",
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
                Def("player_quick_actions", "لوحة الأفعال السريعة", "لوحة سريعة لأدوات المشغّل"),
                Def("player_episodes_button", "زر قائمة الحلقات", "يفتح قائمة الحلقات من داخل المشغّل"),
                Def("player_rotate_btt", "زر تدوير الشاشة", "يقلب الاتجاه أثناء التشغيل"),
                Def("pinch_to_zoom", "التكبير بإصبعين", "كبّر الصورة بإصبعيك"),
                Def("fullscreen_gesture", "إيماءة الدخول لملء الشاشة", "اسحب لإخفاء أشرطة النظام"),
                Def("player_buffering_timeout", "مهلة انتظار التحميل (ثوان)", "بعدها يعتبر المصدر متعطّلاً", Kind.TEXT, default = "30")
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
                Def("speed_indicator_badge", "شارة سرعة التشغيل", "يعرض السرعة الحالية على الشاشة"),
                Def("speed_indicator_duration", "مدّة ظهور الشارة (ملي ثانية)", "كم تبقى الشارة على الشاشة", Kind.TEXT, default = "1500")
            )
        ),
        Group(
            "التشغيل التلقائي والتخطي",
            listOf(
                Def("auto_skip_enabled", "تخطي المقدمة تلقائياً", "يتجاوز مقدمة كل حلقة بلا تدخّل منك"),
                Def("auto_skip_time", "مدّة المقدمة المحفوظة (ثوان)", "تُستعمل عندما لا يستطيع التطبيق كشف المقدمة بنفسه", Kind.TEXT, default = "85"),
                Def("auto_skip_keep_first_intro", "لا تتخطَّ مقدمة الحلقة الأولى", "يبقي مقدمة أول حلقة كي تراها"),
                Def("auto_skip_seen_series", "تخطي المقدمة في المسلسلات المتابَعة", "لا يخمّن في مسلسل جديد لم تشاهده بعد"),
                Def("auto_skip_outro", "تخطي النهاية تلقائياً", "يقفز إلى الحلقة التالية عند ظهور النهاية"),
                Def("auto_skip_show_button", "إظهار زر التخطي", "يُظهر زرّاً بدل التخطي الصامت"),
                Def("auto_resume", "استكمال من آخر موضع", "يفتح الحلقة من حيث توقّفت بدل بدايتها"),
                Def("auto_resume_show_toast", "إظهار رسالة عند الاستكمال", "يخبرك من أي دقيقة بدأ التشغيل"),
                Def("auto_resume_min_position", "أقل موضع يُحفظ (ثوان)", "لا يحفظ مواضع أقل من هذا", Kind.TEXT, default = "30"),
                Def("continuous_playback", "التشغيل المتواصل", "ينتقل تلقائياً إلى الحلقة التالية عند انتهاء الحالية"),
                Def("continuous_playback_countdown", "مدّة العدّ قبل الحلقة التالية (ثوان)", "كم ينتظر قبل التشغيل التلقائي", Kind.TEXT, default = "5"),
                Def("auto_mark_watched", "تعليم الحلقة كمُشاهَدة تلقائياً", "يعلّم الحلقة منتهية عند بلوغ نسبة معيّنة"),
                Def("auto_mark_watched_percent", "نسبة اعتبار الحلقة مُشاهَدة (٪)", "النسبة التي بعدها تُعلَّم الحلقة منتهية", Kind.TEXT, default = "90"),
                Def("continuous_playback_next", "التشغيل المتواصل ضمن المكتبة", "يستمرّ إلى العمل التالي في مكتبتك"),
                Def("auto_pause_interrupt", "إيقاف مؤقّت عند مقاطعة", "يوقف التشغيل عند وصول مكالمة أو تنبيه"),
                Def("player_skip_op", "زر تخطي المقدمة", "يُظهر زر تخطي في المشغّل"),
                Def("player_skip_next", "زر تخطي إلى التالية", "يُظهر زراً للانتقال للحلقة التالية"),
                Def("player_completion_action", "فعل انتهاء التشغيل", "ماذا يحدث عند انتهاء الفيديو"),
                Def("exit_confirm", "تأكيد الخروج من المشغّل", "يسألك قبل إغلاق المشغّل")
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
                Def("audio_boost", "تقوية الصوت العام", "يرفع مستوى الصوت الكلي"),
                Def("loudness_normalize", "توحيد مستوى الصوت", "يجعل الحلقات كلها بمستوى واحد"),
                Def("audio_output", "مخرج الصوت", "أين يُشغَّل الصوت"),
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
                Def("pitch_shift", "تغيير طبقة الصوت", "يرفع أو يخفض طبقة الصوت"),
                Def("subtitle_burn_in", "دمج الترجمة في الصورة", "تحرق الترجمة داخل الفيديو")
            )
        ),
        Group(
            "أدوات أثناء المشاهدة",
            listOf(
                Def("copy_current_stream_url", "زر نسخ رابط البث", "ينسخ رابط الفيديو الحالي إلى الحافظة"),
                Def("player_metadata_overlay", "طبقة معلومات الفيديو", "يعرض اسم المسلسل ورقم الحلقة فوق الفيديو"),
                Def("buffering_speed_display", "عرض سرعة التحميل", "يُظهر سرعة الشبكة أثناء التخزين المؤقت"),
                Def("video_info_overlay", "طبقة معلومات تقنية", "يعرض الدقّة والترميز ومعدّل الصور"),
                Def("sleep_timer_duration", "مؤقّت النوم (دقائق)", "يتوقف التشغيل بعد المدّة المحدّدة", Kind.TEXT, default = "30"),
                Def("quick_bookmark", "علامة سريعة", "يضع علامة عند اللحظة الحالية"),
                Def("bookmark_scenes_enabled", "علامات المشاهد", "احفظ مشاهد بعينها وارجع إليها"),
                Def("now_playing_minibar", "شريط التشغيل السفلي", "شريط صغير يعرض ما يُشغَّل"),
                Def("share_stream", "زر مشاركة البث", "يشارك رابط البث مع من تريد")
            )
        )
    )
)
